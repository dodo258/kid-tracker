package org.traccar.client

import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationClientOption.AMapLocationMode
import com.amap.api.location.AMapLocationListener
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine

private const val AMAP_KEY_META_DATA = "com.amap.api.v2.apikey"

/** App 清单 meta-data 里的高德 Key；打包时没有 Key 就是 null。 */
internal fun amapApiKey(context: Context): String? = try {
    context.packageManager
        .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        .metaData?.get(AMAP_KEY_META_DATA)?.toString()?.takeIf { it.isNotBlank() }
} catch (e: PackageManager.NameNotFoundException) {
    null
}

/** 连续报错这么多次（中间没定到过）就在这一段追踪里改用系统定位 */
private const val AMAP_MAX_CONSECUTIVE_ERRORS = 5

/**
 * 高德的报错什么时候切系统定位：Key 或绑定不对、SDK 初始化失败、APSService 没声明，重试也没用，立刻切；没网时的网络错误、
 * 13 没有 Wi-Fi 列表和基站、18 飞行模式、19 没插卡又关了 Wi-Fi 说的是手机所处环境，换系统定位也没用，不算；其他错误连续 5 次切。
 * 和根目录旧 App 同一套（P20 实测过：开机没网、没插卡关 Wi-Fi 时以前会误切）。
 */
internal fun amapFallbackPolicy() = ProviderFallbackPolicy(
    fatalErrors = setOf(
        AMapLocation.ERROR_CODE_FAILURE_AUTH,
        AMapLocation.ERROR_CODE_FAILURE_INIT,
        AMapLocation.ERROR_CODE_SERVICE_FAIL,
    ),
    maxConsecutiveErrors = AMAP_MAX_CONSECUTIVE_ERRORS,
    offlineErrors = setOf(AMapLocation.ERROR_CODE_FAILURE_CONNECTION),
    environmentErrors = setOf(
        AMapLocation.ERROR_CODE_FAILURE_NOWIFIANDAP,
        AMapLocation.ERROR_CODE_AIRPLANEMODE_WIFIOFF,
        AMapLocation.ERROR_CODE_NOCGI_WIFIOFF,
    ),
)

/**
 * 高德定位，给没有 Google 服务的国内手机用。高德报的 GCJ-02 坐标在这里转成 WGS-84，引擎和服务器只见 WGS-84。
 * 建不起高德客户端时构造就抛异常，由 [FallbackLocationSource] 改用系统定位。每次定位回调都把错误码
 * （定到了是 [AMapLocation.LOCATION_SUCCESS]）交给 [onResult]。
 *
 * 原版没有它：原版靠 Fused（Google 服务），国内手机没有或用不了，只能退到系统定位，室内没有网络定位就基本没点。
 *
 * 单次取点每次新建一个高德客户端、用完销毁：不同取点之间不共用客户端、监听和锁，一次取点卡住不会挡住下一次，
 * 取消或超时时停掉并销毁它（根目录旧 App 共用一个单次客户端加锁，锁被占着时后面的取点一直等，见
 * docs/reports/2026-11-18-p20-zone-fix-hang.md）。
 */
class AMapLocationSource(
    scope: ComponentCoroutineScope,
    context: Context,
    state: StateFlow<State>,
    private val onResult: (errorCode: Int) -> Unit = {},
) : LocationSource {

    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val client: AMapLocationClient

    override val positions = MutableSharedFlow<Position>(extraBufferCapacity = 8)

    private var started = false

    init {
        // 自用 App：主人装好并开追踪就算同意隐私政策。两句都要在建任何 AMapLocationClient 之前调
        AMapLocationClient.updatePrivacyShow(appContext, true, true)
        AMapLocationClient.updatePrivacyAgree(appContext, true)
        client = AMapLocationClient(Looper.getMainLooper(), appContext)
        // 连续定位里高德的「前次定位结果」和缓存结果（[amapRepeatedResult]）是旧坐标配新时间，不当新点
        client.setLocationListener { location ->
            onResult(location.errorCode)
            location.toPositionOrNull()
                ?.takeUnless { amapRepeatedResult(location.locationType) }
                ?.let { positions.tryEmit(it) }
        }
        scope.observeState(state, State::locationMode, inactive = LocationMode.Off) { mode ->
            if (mode == LocationMode.Active) ensureStarted() else ensureStopped()
        }
        // 改设置（Tracker.updateConfig）会取消作用域、新建定位源：销毁旧的高德客户端
        scope.coroutineContext.job.invokeOnCompletion { mainExecutor.execute { client.onDestroy() } }
    }

    private fun ensureStarted() {
        if (started) return
        client.setLocationOption(continuousOption())
        client.startLocation()
        started = true
        Log.log("Location updates started")
    }

    // 原版停连续定位前再取最后一点；现在判断层转静止、屋里时自己决定报哪个点（policy/ReportPolicy），这里只停
    private fun ensureStopped() {
        if (!started) return
        client.stopLocation()
        started = false
        Log.log("Location updates stopped")
    }

    // 单次取点要这一刻的点，GPS 也开（P20 没插卡骑车时只用网络定位拿到的是家里的缓存点）；拿不到就是 null，不拿旧位置充数
    override suspend fun fetchOnce(): Position? = awaitFix(onceOption(AMapLocationMode.Hight_Accuracy), LOCATION_FETCH_TIMEOUT)

    // 判断层在屋里、静止时要的点：只用 Wi-Fi 和基站，不开卫星
    override suspend fun fetchNetworkOnce(): Position? = awaitFix(onceOption(AMapLocationMode.Battery_Saving), LOCATION_FETCH_TIMEOUT)

    private suspend fun awaitFix(option: AMapLocationClientOption, timeout: Duration): Position? {
        val once = try {
            AMapLocationClient(Looper.getMainLooper(), appContext)
        } catch (e: Exception) {
            Log.log("AMap single fix: client failed: $e")
            return null
        }
        var listener: AMapLocationListener? = null
        try {
            // 回调了就有答案（定到的点，或报错时的 null）；等到时限都没回调才是超时，抛 LocationNoAnswer 给看门狗数
            return awaitAnswer("AMap single fix", timeout) {
                suspendCancellableCoroutine<Position?> { continuation ->
                    listener = AMapLocationListener { location ->
                        onResult(location.errorCode)
                        // 单次定位保留类型 2：高德按传感器判断位置没变给的上一次结果，丢掉会被当成定位失败（根目录旧 App 的 P20 经验）；
                        // 4、9 是缓存，不当这一刻的点
                        val position = location.toPositionOrNull()?.takeUnless {
                            amapCachedResult(location.locationType).also { cached ->
                                if (cached) Log.log("AMap single fix: cached result (type ${location.locationType}), dropped")
                            }
                        }
                        if (continuation.isActive) continuation.resume(position)
                    }.also(once::setLocationListener)
                    once.setLocationOption(option)
                    once.startLocation()
                }
            }
        } finally {
            // 不管是拿到、超时还是被取消，都停掉并销毁这次的客户端；放到主线程做，不在这里等它
            val registered = listener
            mainExecutor.execute {
                registered?.let(once::unRegisterLocationListener)
                once.stopLocation()
                once.onDestroy()
            }
        }
    }

    // 连续定位只在判断层说在走路、坐车时开：高精度 1 秒一个点（方案第 3 节），卫星没定到时高德给 Wi-Fi / 基站的点（标了来源），
    // 多久报一次由判断层定。设置里的精度、间隔不再管这里
    private fun continuousOption(): AMapLocationClientOption = baseOption(AMapLocationMode.Hight_Accuracy)
        .setInterval(CONTINUOUS_INTERVAL_MILLIS)
        .setLocationCacheEnable(false)

    private fun onceOption(mode: AMapLocationMode) = baseOption(mode)
        .setOnceLocation(true)
        .setOnceLocationLatest(true)
        .setLocationCacheEnable(false)

    private fun baseOption(mode: AMapLocationMode) = AMapLocationClientOption()
        .setLocationMode(mode)
        .setSensorEnable(true)
        .setNeedAddress(false)
        .setMockEnable(false)

    private fun AMapLocation.toPositionOrNull(): Position? {
        if (errorCode != AMapLocation.LOCATION_SUCCESS) {
            Log.log("AMap error $errorCode${errorHint(errorCode)}: $errorInfo")
            return null
        }
        val converted = if (coordType == AMapLocation.COORD_TYPE_GCJ02) {
            gcj02ToWgs84(latitude, longitude)
        } else {
            LatLon(latitude, longitude)
        }
        return Position(
            latitude = converted.latitude,
            longitude = converted.longitude,
            accuracy = accuracy.toDouble().takeIf { hasAccuracy() && it.isFinite() },
            time = time,
            altitude = altitude.takeIf { hasAltitude() && it.isFinite() },
            speed = speed.toDouble().takeIf { hasSpeed() && it.isFinite() },
            bearing = bearing.toDouble().takeIf { hasBearing() && it.isFinite() },
            provider = amapProvider(locationType),
        )
    }

    private fun errorHint(code: Int): String = when (code) {
        AMapLocation.ERROR_CODE_FAILURE_AUTH -> " (check AMap key, signing SHA1 and package name)"
        AMapLocation.ERROR_CODE_FAILURE_LOCATION_PERMISSION -> " (location permission missing)"
        AMapLocation.ERROR_CODE_SERVICE_FAIL -> " (APSService not declared)"
        else -> ""
    }


    private companion object {
        const val CONTINUOUS_INTERVAL_MILLIS = 1000L
    }
}

/** 高德官方定位类型 4「缓存定位结果」、9「最后位置缓存」：以前的结果，不是这一刻定的位。 */
internal fun amapCachedResult(locationType: Int): Boolean =
    locationType == AMapLocation.LOCATION_TYPE_FIX_CACHE ||
        locationType == AMapLocation.LOCATION_TYPE_LAST_LOCATION_CACHE

/** 连续定位里不当新点的：缓存，加上类型 2「前次定位结果」（位置变化很小时高德返回上一次的）。 */
internal fun amapRepeatedResult(locationType: Int): Boolean =
    locationType == AMapLocation.LOCATION_TYPE_SAME_REQ || amapCachedResult(locationType)

/** 高德 locationType 对应的 [Provider]：卫星、Wi-Fi、基站，别的（离线、模糊定位等）算网络定位 */
internal fun amapProvider(locationType: Int): String = when (locationType) {
    AMapLocation.LOCATION_TYPE_GPS -> Provider.GPS
    AMapLocation.LOCATION_TYPE_WIFI -> Provider.WIFI
    AMapLocation.LOCATION_TYPE_CELL -> Provider.CELL
    else -> Provider.NETWORK
}
