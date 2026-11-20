package org.traccar.client.sense

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import java.net.Inet4Address
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.traccar.client.ComponentCoroutineScope
import org.traccar.client.Log
import org.traccar.client.SensorSource
import org.traccar.client.State
import org.traccar.client.observeState

/**
 * 判断层的传感器快照（方案第 3 节 sense/、3b 节取数原则），追踪开着时在自己的线程上采：
 * - 加速度、陀螺、朝向用批量上报（[BATCH_LATENCY_US]，约 10 秒交一批）：传感器芯片攒着，CPU 醒一次处理一批；按事件自己的时间戳
 *   切成 [PERIOD_MILLIS] 一窗，一批来时一次出几份快照。手机有唤醒型（wake-up）加速度就用它，到点叫醒 CPU；没有就用普通的，
 *   CPU 睡着时它不交数据，醒着时也一直挂着显著运动兜底（[wantSignificantMotion]）。不拿长期唤醒锁。
 * - 静止、飞行模式时（[setSleeping]）关掉这些，只挂显著运动（[Sensor.TYPE_SIGNIFICANT_MOTION]，一次性、几乎不耗电）；
 *   它触发时拿 [WAKE_GRACE_MILLIS] 的短唤醒锁、重开加速度，这段时间内不再睡，让判断层看清是不是真在动。手机没有显著运动传感器就不睡。
 * - 计步器只读累计数（华为攒着报，只当事后核对）；被动定位白捡别的 App（和自己的高德）拿到的点。
 * - 屏幕、充电、飞行模式用广播，联网方式、Wi-Fi、网关用 NetworkCallback，都是事件来了才更新，不轮询；Wi-Fi 不扫描。
 *   睡着时没有窗口快照：联网方式、连着的 Wi-Fi 变了（以及刚睡下时判断层还没收到过快照）马上出一份只带系统状态的快照（[emitIfAsleep]），
 *   不然判断层手里一直是睡前那份（11.0.16 实测：重启后接着静止、一份快照都没有，上报一直 net=none）。
 *   基站只读系统缓存的（[CellReader]，10 秒最多读一次），不让基带重新扫。
 * - 磁场和朝向一起开关（旋转矢量本来就用着磁力计），每窗记强度的波动；卫星状态回调一直挂着、不为它发定位请求，
 *   卫星被谁开着（自己的高德、别的 App）就记谁的，没开就没有（方案 docs/specs/2026-11-19-sky-quality.md 第 8 节第 1 条，只采数据）。
 */
class SnapshotSampler(
    scope: ComponentCoroutineScope,
    context: Context,
    state: StateFlow<State>,
) : SensorSource {

    override val snapshots = MutableSharedFlow<Snapshot>(extraBufferCapacity = 64)

    private val appContext = context.applicationContext
    private val sensorManager: SensorManager? = appContext.getSystemService()
    private val connectivity: ConnectivityManager? = appContext.getSystemService()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    // 下面这些只在采样线程上读写
    private val stats = WindowStats()
    private var windowEnd: Long? = null
    private var motionRegistered = false
    private var accelWakeUp = false
    private var sleeping = false
    private var awakeUntil = 0L
    private var triggerArmed = false
    private var triggerRefused = false
    private var stepsTotal: Long? = null
    private var lastFix: Location? = null
    private var screen: Boolean? = null
    private var charging: Boolean? = null
    private var airplane = false
    private var net = Snapshot.Net.NONE
    private var wifiMetered: Boolean? = null
    private var wifiRssi: Int? = null
    private var wifiBssid = ""
    private var wifiSsid = ""
    private var wifiGateway = ""
    /** 默认网络连上过几次：同一个 Wi-Fi 断开重连 BSSID 不变，靠它让判断层知道断过（[Snapshot.netLink]） */
    private var netLink = 0
    /** 上一份发出去的快照：睡着时看联网有没有变 */
    private var emitted: Snapshot? = null
    private val cells = CellReader(appContext)
    private val satellites = SatelliteLog()
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)

    override fun setSleeping(sleeping: Boolean) {
        handler?.post { applySleeping(sleeping) }
    }

    @Synchronized
    private fun start() {
        if (thread != null) return
        val t = HandlerThread("snapshot-sampler").apply { start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        h.post {
            readInitialState()
            registerMotion(h)
            syncTrigger()
            registerSteps(h)
            registerPassiveLocation(h)
            registerGnssStatus(h)
            registerSystemEvents(h)
            Log.log("Sensors started")
        }
    }

    @Synchronized
    private fun stop() {
        val h = handler ?: return
        val t = thread
        h.post {
            sensorManager?.unregisterListener(motionListener)
            sensorManager?.unregisterListener(stepListener)
            cancelTrigger()
            motionRegistered = false
            sleeping = false
            windowEnd = null
            stats.close()
            try {
                appContext.getSystemService<LocationManager>()?.removeUpdates(locationListener)
            } catch (e: SecurityException) {
                Log.log("Sensors: remove passive location: $e")
            }
            appContext.getSystemService<LocationManager>()?.unregisterGnssStatusCallback(gnssCallback)
            satellites.clear()
            emitted = null
            runCatching { appContext.unregisterReceiver(systemReceiver) }
            runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
            Log.log("Sensors stopped")
            t?.quitSafely()
        }
        thread = null
        handler = null
    }

    private fun applySleeping(want: Boolean) {
        val h = handler ?: return
        if (want == sleeping) return
        if (want && SystemClock.elapsedRealtime() < awakeUntil) return
        if (want) {
            val sm = sensorManager ?: return
            // 先挂上唤醒再关加速度：挂不上就不睡，不然再也没人叫醒
            if (!armTrigger()) return
            sleeping = true
            sm.unregisterListener(motionListener)
            motionRegistered = false
            windowEnd = null
            stats.close()
            Log.log("Sensors sleeping until significant motion")
            emitIfAsleep()
        } else {
            sleeping = false
            registerMotion(h)
            syncTrigger()
            Log.log("Sensors awake")
        }
    }

    /** 按 [wantSignificantMotion] 挂上或撤下显著运动 */
    private fun syncTrigger() {
        val inGrace = SystemClock.elapsedRealtime() < awakeUntil
        if (wantSignificantMotion(sleeping, accelWakeUp, inGrace)) armTrigger() else cancelTrigger()
    }

    /** 挂上显著运动（已挂着就不重挂：同一个监听重复挂系统返回 false）；挂不上返回 false */
    private fun armTrigger(): Boolean {
        if (triggerArmed) return true
        val sm = sensorManager ?: return false
        val trigger = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        if (trigger == null || !sm.requestTriggerSensor(triggerListener, trigger)) {
            if (!triggerRefused) Log.log("Sensors: significant motion not armed")
            triggerRefused = true
            return false
        }
        triggerArmed = true
        return true
    }

    private fun cancelTrigger() {
        if (!triggerArmed) return
        sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)?.let { sensorManager.cancelTriggerSensor(triggerListener, it) }
        triggerArmed = false
    }

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            val h = handler ?: return
            h.post {
                triggerArmed = false
                awakeUntil = SystemClock.elapsedRealtime() + WAKE_GRACE_MILLIS
                appContext.getSystemService<PowerManager>()
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "traccar:motion-wake")
                    ?.acquire(WAKE_GRACE_MILLIS)
                Log.log("Significant motion")
                sleeping = false
                registerMotion(h)
                // 一次性传感器：宽限期过了再按需重挂（加速度不是唤醒型时醒着也要挂）
                h.postDelayed({ if (handler === h) syncTrigger() }, WAKE_GRACE_MILLIS)
            }
        }
    }

    private fun registerMotion(h: Handler) {
        val sm = sensorManager ?: return
        if (motionRegistered) return
        fun reg(type: Int, periodUs: Int): Sensor? {
            val sensor = sm.getDefaultSensor(type, true) ?: sm.getDefaultSensor(type) ?: return null
            sm.registerListener(motionListener, sensor, periodUs, BATCH_LATENCY_US, h)
            return sensor
        }
        accelWakeUp = reg(Sensor.TYPE_ACCELEROMETER, MOTION_RATE_US)?.isWakeUpSensor == true
        reg(Sensor.TYPE_GYROSCOPE, MOTION_RATE_US)
        reg(Sensor.TYPE_ROTATION_VECTOR, SLOW_RATE_US)
        reg(Sensor.TYPE_MAGNETIC_FIELD, SLOW_RATE_US)
        motionRegistered = true
    }

    private fun registerSteps(h: Handler) {
        val sm = sensorManager ?: return
        sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sm.registerListener(stepListener, it, SensorManager.SENSOR_DELAY_NORMAL, BATCH_LATENCY_US, h)
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerPassiveLocation(h: Handler) {
        try {
            appContext.getSystemService<LocationManager>()
                ?.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 0f, locationListener, h.looper)
        } catch (e: SecurityException) {
            Log.log("Sensors: passive location: $e")
        }
    }

    // 只挂回调不请求定位：卫星没人开时系统不回调，快照里就是空的
    @SuppressLint("MissingPermission")
    private fun registerGnssStatus(h: Handler) {
        try {
            val ok = appContext.getSystemService<LocationManager>()?.registerGnssStatusCallback(gnssCallback, h) == true
            if (!ok) Log.log("Sensors: satellite status not available")
        } catch (e: SecurityException) {
            Log.log("Sensors: satellite status: $e")
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val n = status.satelliteCount
            val cn0 = DoubleArray(n) { status.getCn0DbHz(it).toDouble() }
            val used = BooleanArray(n) { status.usedInFix(it) }
            satellites.add(SystemClock.elapsedRealtimeNanos(), SatelliteStatus.of(cn0, used))
        }

        override fun onStopped() = satellites.clear()
    }

    private fun registerSystemEvents(h: Handler) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(appContext, systemReceiver, filter, null, h, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            connectivity?.registerDefaultNetworkCallback(networkCallback, h)
        } else {
            connectivity?.registerDefaultNetworkCallback(networkCallback)
        }
    }

    private fun readInitialState() {
        screen = appContext.getSystemService<PowerManager>()?.isInteractive
        charging = appContext.getSystemService<BatteryManager>()?.isCharging
        airplane = Settings.Global.getInt(appContext.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> screen = true
                Intent.ACTION_SCREEN_OFF -> screen = false
                Intent.ACTION_POWER_CONNECTED -> charging = true
                Intent.ACTION_POWER_DISCONNECTED -> charging = false
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    airplane = intent.getBooleanExtra("state", false)
                    // 睡着时没有窗口快照：飞行模式开关马上出一份（没有加速度数据），判断层立刻知道
                    if (!motionRegistered) emit(systemSnapshot())
                }
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        // 默认网络换成了一条新连接（断开后重连同一个 Wi-Fi 也是新的 Network）
        override fun onAvailable(network: Network) {
            netLink++
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            net = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Snapshot.Net.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Snapshot.Net.CELL
                else -> Snapshot.Net.OTHER
            }
            if (net != Snapshot.Net.WIFI) {
                clearWifi()
            } else {
                wifiMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                wifiRssi = caps.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
                readWifiInfo()
            }
            emitIfAsleep()
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            wifiGateway = properties.routes
                .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway?.hostAddress.orEmpty()
        }

        override fun onLost(network: Network) {
            net = Snapshot.Net.NONE
            clearWifi()
            emitIfAsleep()
        }
    }

    private fun clearWifi() {
        wifiMetered = null
        wifiRssi = null
        wifiBssid = ""
        wifiSsid = ""
        wifiGateway = ""
    }

    // 只读系统已有的连接信息（要定位权限才有 BSSID），不扫描
    @Suppress("DEPRECATION")
    private fun readWifiInfo() {
        val info = try {
            appContext.getSystemService<WifiManager>()?.connectionInfo
        } catch (e: SecurityException) {
            null
        }
        wifiBssid = info?.bssid?.takeIf { it != "02:00:00:00:00:00" }.orEmpty()
        wifiSsid = info?.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" }.orEmpty()
        if (wifiRssi == null) wifiRssi = info?.rssi
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastFix = location
            // 卫星点附带的参与定位卫星数（高德的 getSatellites 读的也是它）：卫星状态回调没给时，至少有个数
            val count = location.extras?.getInt("satellites", -1) ?: -1
            if (location.provider == LocationManager.GPS_PROVIDER && count >= 0) {
                satellites.add(location.elapsedRealtimeNanos, SatelliteStatus(count, null))
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            stepsTotal = event.values[0].toLong()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val motionListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    advanceWindow(event.timestamp)
                    stats.onAccelerometer(event.timestamp / 1_000_000, v[0], v[1], v[2])
                }
                Sensor.TYPE_GYROSCOPE -> stats.onGyroscope(v[0], v[1], v[2])
                Sensor.TYPE_MAGNETIC_FIELD -> stats.onMagnetic(v[0], v[1], v[2])
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotation, v)
                    SensorManager.getOrientation(rotation, orientation)
                    stats.onAzimuth(orientation[0].toDouble())
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** 按加速度事件的时间戳切窗：过了这一窗的结尾就收一份快照；中间断了一大段（没有事件）就从这个事件重新起窗 */
    private fun advanceWindow(nanos: Long) {
        val end = windowEnd
        if (end == null || nanos - end > PERIOD_NANOS * MAX_SKIPPED_WINDOWS) {
            if (end != null) stats.close()
            windowEnd = nanos + PERIOD_NANOS
            return
        }
        if (nanos < end) return
        emit(snapshot(end))
        var next = end + PERIOD_NANOS
        while (nanos >= next) next += PERIOD_NANOS
        windowEnd = next
    }

    private fun snapshot(endNanos: Long): Snapshot {
        val w = stats.close()
        val wall = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - endNanos) / 1_000_000
        val sky = satellites.at(endNanos)
        return Snapshot(
            time = wall,
            elapsed = endNanos / 1_000_000,
            accMean = w.accMean,
            accStd = w.accStd,
            accMax = w.accMax,
            accN = w.accN,
            accPeaks = w.accPeaks,
            gyroMean = w.gyroMean,
            gyroMax = w.gyroMax,
            heading = w.heading,
            headingN = w.headingN,
            magStd = w.magStd,
            satUsed = sky?.used,
            cn0Top4 = sky?.cn0Top4,
        ).let(systemState()::applyTo)
    }

    /**
     * 采样器手里的系统状态。不能写成 `Snapshot` 的扩展函数再 `copy(screen = screen)`：那样右边的 `screen` 取到的是快照自己的
     * 空字段，屏幕、充电、飞行模式、联网、Wi-Fi、步数一直是空的（第三步时就是这样，R6 录制时发现）。
     */
    private fun systemState() = SystemState(
        screen = screen,
        charging = charging,
        airplane = airplane,
        stepsTotal = stepsTotal,
        net = net,
        wifiBssid = wifiBssid,
        wifiSsid = wifiSsid,
        wifiRssi = wifiRssi,
        wifiMetered = wifiMetered,
        wifiGateway = wifiGateway,
        netLink = netLink,
        cell = cells.current(),
        fix = lastFix?.let {
            Snapshot.Fix(
                provider = it.provider.orEmpty(),
                time = it.time,
                latitude = it.latitude,
                longitude = it.longitude,
                accuracy = it.takeIf { l -> l.hasAccuracy() }?.accuracy?.toDouble(),
                speed = it.takeIf { l -> l.hasSpeed() }?.speed?.toDouble(),
                bearing = it.takeIf { l -> l.hasBearing() }?.bearing?.toDouble(),
                elapsed = it.elapsedRealtimeNanos / 1_000_000,
            )
        },
    )

    /** 没有加速度数据、只带系统状态的快照 */
    private fun systemSnapshot(): Snapshot =
        systemState().applyTo(Snapshot(time = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime()))

    /** 睡着时（加速度关着，没有窗口快照）联网方式、连着的 Wi-Fi 和上一份快照不一样了，马上出一份 */
    private fun emitIfAsleep() {
        if (motionRegistered || !systemState().linkChanged(emitted)) return
        emit(systemSnapshot())
    }

    private fun emit(snapshot: Snapshot) {
        emitted = snapshot
        if (!snapshots.tryEmit(snapshot)) Log.log("Sensors: snapshot dropped, judgement busy")
    }

    // 放在所有属性（监听器、接收器）后面：追踪已经开着时这里马上在采样线程上注册监听，属性还没初始化就是 null，
    // 注册时抛 invalid null listener 把进程带崩（R6 模拟器上冷启动撞到过）
    init {
        scope.observeState(state, { it.enabled }, inactive = false) { enabled ->
            if (enabled) start() else stop()
        }
    }

    companion object {
        const val PERIOD_MILLIS = 2_000L
        private const val PERIOD_NANOS = PERIOD_MILLIS * 1_000_000
        private const val MAX_SKIPPED_WINDOWS = 5
        private const val MOTION_RATE_US = 40_000
        private const val SLOW_RATE_US = 200_000
        private const val BATCH_LATENCY_US = 10_000_000
        private const val WAKE_GRACE_MILLIS = 30_000L
    }
}

/**
 * 显著运动要不要挂着。睡着（静止、飞行模式）时要：只有它能叫醒。醒着时，加速度是唤醒型的就不用（它到点叫醒 CPU 交数据）；
 * 不是唤醒型的也要挂：屋里走动（FIDGET）时卫星关着、没人拿唤醒锁，CPU 一睡普通加速度就不交数据，出门走路只能靠它叫醒。
 * 刚被它叫醒的 30 秒宽限期里先不挂：一直在走时它会接连触发，每次都拿短唤醒锁。
 */
internal fun wantSignificantMotion(sleeping: Boolean, accelWakeUp: Boolean, inGrace: Boolean): Boolean =
    sleeping || (!accelWakeUp && !inGrace)

/** 屏幕、充电、飞行模式、联网、Wi-Fi、步数、最近一个定位：事件来了才更新，每切一窗盖到快照上 */
internal data class SystemState(
    val screen: Boolean?,
    val charging: Boolean?,
    val airplane: Boolean,
    val stepsTotal: Long?,
    val net: Snapshot.Net,
    val wifiBssid: String,
    val wifiSsid: String,
    val wifiRssi: Int?,
    val wifiMetered: Boolean?,
    val wifiGateway: String,
    val netLink: Int,
    /** 登记着的基站：跟踪区、小区号、信号强度（[CellReader]） */
    val cell: Cell = Cell(),
    val fix: Snapshot.Fix?,
) {
    /** 联网方式、连着的 Wi-Fi、连接代次和快照 [last] 不一样（还没有快照也算）；信号强度、网关、基站变了不算 */
    fun linkChanged(last: Snapshot?): Boolean =
        last == null || net != last.net || wifiBssid != last.wifiBssid || wifiSsid != last.wifiSsid ||
            wifiMetered != last.wifiMetered || netLink != last.netLink

    fun applyTo(window: Snapshot): Snapshot = window.copy(
        screen = screen,
        charging = charging,
        airplane = airplane,
        stepsTotal = stepsTotal,
        net = net,
        wifiBssid = wifiBssid,
        wifiSsid = wifiSsid,
        wifiRssi = wifiRssi,
        wifiMetered = wifiMetered,
        wifiGateway = wifiGateway,
        netLink = netLink,
        cellTac = cell.tac,
        cellId = cell.id,
        cellDbm = cell.dbm,
        fix = fix,
    )
}
