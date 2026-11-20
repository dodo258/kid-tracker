package org.traccar.client

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

@SuppressLint("MissingPermission")
class AndroidLocationSource(
    scope: ComponentCoroutineScope,
    context: Context,
    state: StateFlow<State>,
) : LocationSource {

    private val appContext = context.applicationContext
    private val locationManager: LocationManager = checkNotNull(appContext.getSystemService())

    override val positions = MutableSharedFlow<Position>(extraBufferCapacity = 8)

    private var listener: LocationListenerCompat? = null

    init {
        scope.observeState(state, State::locationMode, inactive = LocationMode.Off) { mode ->
            try {
                if (mode == LocationMode.Active) ensureStarted() else ensureStopped()
            } catch (e: SecurityException) {
                Log.log("Location permission missing: $e")
            }
        }
    }

    private fun ensureStarted() {
        if (listener != null) return
        startUpdates()
    }

    // 原版停之前再取最后一点；现在转静止、屋里时报哪个点由判断层定，这里只停
    private fun ensureStopped() {
        if (listener == null) return
        stopUpdates()
    }

    // 原版拿不到新点时退回系统缓存的最后位置（getLastKnownLocation），那可能是几小时前、几公里外的点，
    // 心跳和「发送位置」会把它当成现在的位置报上去（项目约定不许）：拿不到就是 null，只报时间
    override suspend fun fetchOnce(): Position? = fetchFrom(provider(LocationManager.GPS_PROVIDER))

    // 只用网络定位；手机没有网络定位（没有 Google 服务的手机、AOSP 模拟器常见）就是没拿到，不开卫星顶替
    override suspend fun fetchNetworkOnce(): Position? =
        LocationManager.NETWORK_PROVIDER.takeIf { it in locationManager.allProviders }?.let { fetchFrom(it) }

    private suspend fun fetchFrom(provider: String): Position? = try {
        awaitCurrentLocation(provider)?.toPosition()
    } catch (e: SecurityException) {
        Log.log("Location permission missing: $e")
        null
    }

    private fun startUpdates() {
        val newListener = LocationListenerCompat { location ->
            positions.tryEmit(location.toPosition())
        }
        // 只在判断层说在走路、坐车时开：卫星 1 秒一个点，多久报一次由判断层定
        locationManager.requestLocationUpdates(
            provider(LocationManager.GPS_PROVIDER),
            CONTINUOUS_INTERVAL_MILLIS,
            0f,
            newListener,
            Looper.getMainLooper(),
        )
        listener = newListener
        Log.log("Location updates started")
    }

    private fun stopUpdates() {
        listener?.let {
            locationManager.removeUpdates(it)
            Log.log("Location updates stopped")
        }
        listener = null
    }

    // 每次请求用自己的取消信号、自己的时限。原版全实例共用一个信号，后来的请求把前一个取消掉，被取消的请求不再回调；
    // 停连续定位前取最后一点那次又没设时限，就永远挂着（根目录旧 App 11-18 修过同一个问题）
    // LocationManagerCompat 自己 30 秒没点就回调 null（正常没定到）；多等 [ANSWER_MARGIN] 还没回调才算定位源不理人（LocationNoAnswer）
    private suspend fun awaitCurrentLocation(provider: String): Location? {
        val signal = CancellationSignal()
        return awaitAnswer("System location single fix", LOCATION_FETCH_TIMEOUT + ANSWER_MARGIN) {
            suspendCancellableCoroutine { continuation ->
                LocationManagerCompat.getCurrentLocation(
                    locationManager,
                    provider,
                    signal,
                    ContextCompat.getMainExecutor(appContext),
                ) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
                continuation.invokeOnCancellation { signal.cancel() }
            }
        }
    }

    private fun Location.toPosition(): Position = Position(
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy.toDouble().takeIf { hasAccuracy() && it.isFinite() },
        time = time,
        altitude = altitude.takeIf { hasAltitude() && it.isFinite() },
        speed = speed.toDouble().takeIf { hasSpeed() && it.isFinite() },
        bearing = bearing.toDouble().takeIf { hasBearing() && it.isFinite() },
        provider = if (provider == LocationManager.GPS_PROVIDER) Provider.GPS else Provider.NETWORK,
    )

    private fun provider(preferred: String): String {
        val available = locationManager.allProviders
        // 要的没有时先退到 GPS、网络定位，最后才是别的：原版直接取列表第一个，没有网络定位的手机（AOSP 模拟器实测）
        // 取到的是 passive，它自己不定位，高德切过来后一个点都没有
        return preferred.takeIf { it in available }
            ?: listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { it in available }
            ?: available.firstOrNull()
            ?: throw IllegalStateException("No location provider available")
    }

    private companion object {
        const val CONTINUOUS_INTERVAL_MILLIS = 1000L
        val ANSWER_MARGIN = 5.seconds
    }
}
