package org.traccar.client

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.core.location.LocationManagerCompat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.traccar.client.alert.Alarm
import org.traccar.client.alert.AlertOutbox
import org.traccar.client.alert.LocationAlarmPolicy
import org.traccar.client.alert.LocationProblem
import org.traccar.client.alert.LowBatteryPolicy

/**
 * 追踪开着时盯电量、关机、定位开关，按 alert/ 的规则记告警，交给 [AlertOutbox] 存好再发（和根目录旧 App 一样的种类和门槛）。
 * 这几个广播只能动态注册：电量变化不能在清单里收，关机广播带 FLAG_RECEIVER_REGISTERED_ONLY（AOSP ShutdownThread），
 * 定位开关不在隐式广播豁免名单里。追踪开着时前台服务让进程活着，收得到。
 * 「这一轮报过了」存在 SharedPreferences（键名和旧 App 一样，覆盖安装后接着用），撤销权限时系统杀进程，重启后不重报。
 */
internal class AlertMonitor(
    scope: ComponentCoroutineScope,
    context: Context,
    state: StateFlow<State>,
    private val outbox: AlertOutbox,
) : AlertSources {

    private val appContext = context.applicationContext
    // 广播里起的活不跟组件作用域走：组件重建时正在记的告警、关机时正在发的告警要做完
    private val receiverScope = alertReceiverScope()
    private val mutex = Mutex()
    private val lowBattery = LowBatteryPolicy()
    private var receivers: List<BroadcastReceiver> = emptyList()

    init {
        scope.observeState(state, { it.enabled }, inactive = false) { enabled ->
            if (enabled) register() else unregister()
        }
    }

    private fun register() {
        if (receivers.isNotEmpty()) return
        val battery = receiver { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return@receiver
            val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            receiverScope.launch { onBattery(level * 100 / scale, charging) }
        }
        val location = receiver { receiverScope.launch { checkLocation() } }
        val shutdown = receiver { intent ->
            // 系统发完关机广播最多等 10 秒：goAsync 撑住进程，存好告警再试着发一次，发不出去开机联网后补发
            val pending = goAsync()
            receiverScope.launch {
                try {
                    outbox.raise(Alarm.POWER_OFF, "shutdown (${intent.action})")
                    withTimeoutOrNull(SHUTDOWN_SEND_LIMIT) { outbox.flush() }
                } finally {
                    pending.finish()
                }
            }
        }
        // 电量变化是粘性广播，注册时马上收到当前电量
        listen(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        listen(
            location,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) addAction(LocationManager.MODE_CHANGED_ACTION)
            },
        )
        listen(
            shutdown,
            IntentFilter(Intent.ACTION_SHUTDOWN).apply {
                // 部分国产机和 HTC 快速关机发的是这两个
                addAction("android.intent.action.QUICKBOOT_POWEROFF")
                addAction("com.htc.intent.action.QUICKBOOT_POWEROFF")
            },
        )
        receivers = listOf(battery, location, shutdown)
        receiverScope.launch { checkLocation() }
    }

    private fun unregister() {
        receivers.forEach { appContext.unregisterReceiver(it) }
        receivers = emptyList()
    }

    private suspend fun onBattery(level: Int, charging: Boolean) = mutex.withLock {
        val preferences = preferences()
        val reported = preferences.getBoolean(LOW_BATTERY_REPORTED_KEY, false)
        val decision = lowBattery.next(reported, level, charging)
        // 电量用触发判断的这个广播值，和报上去的一致；存进发件箱就算记下了，再记「报过」
        if (decision.report) outbox.raise(Alarm.LOW_BATTERY, "battery $level%", level, charging)
        if (decision.reported != reported) {
            preferences.edit(commit = true) { putBoolean(LOW_BATTERY_REPORTED_KEY, decision.reported) }
        }
    }

    private suspend fun checkLocation() = mutex.withLock {
        val preferences = preferences()
        val reported = preferences.getStringSet(LOCATION_REPORTED_KEY, emptySet()).orEmpty()
            .mapNotNull { name -> LocationProblem.entries.find { it.name == name } }.toSet()
        val decision = LocationAlarmPolicy.next(reported, locationProblems())
        if (decision.report.isNotEmpty()) {
            outbox.raise(Alarm.GENERAL, decision.report.joinToString(",") { it.reason })
        }
        if (decision.reported != reported) {
            preferences.edit(commit = true) { putStringSet(LOCATION_REPORTED_KEY, decision.reported.map { it.name }.toSet()) }
        }
    }

    private fun locationProblems(): Set<LocationProblem> {
        val manager = appContext.getSystemService<LocationManager>()
        return LocationAlarmPolicy.problems(
            locationEnabled = manager != null && LocationManagerCompat.isLocationEnabled(manager),
            fine = hasPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION),
            coarse = hasPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION),
            background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                hasPermission(appContext, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        )
    }

    // 这几个都是系统广播：别的 App 发不了，标成不导出也照样收得到
    private fun listen(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
    }

    private fun receiver(onReceive: BroadcastReceiver.(Intent) -> Unit) = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onReceive(intent)
    }

    private fun preferences() = DeviceStorage.preferences(appContext, PREFERENCES_NAME)

    private companion object {
        const val PREFERENCES_NAME = "traccar-client-sdk"
        const val LOW_BATTERY_REPORTED_KEY = "alarm-low-battery-reported"
        const val LOCATION_REPORTED_KEY = "alarm-location-reported"
        val SHUTDOWN_SEND_LIMIT = 8.seconds
    }
}

/**
 * 告警广播里起活用的作用域。低电、关定位、关机三个入口都在这里记告警：存储满时写库抛错，没接住的话落到线程默认处理，
 * 进程直接崩；电量广播是粘性的，重启后一注册马上再收到、再崩，低电又存储满的手机就一直起不来。这里接住记一条存储异常，
 * 告警这次没记上，下一次广播（电量变化、定位开关）再判。
 */
internal fun alertReceiverScope(): CoroutineScope = CoroutineScope(
    SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
        Log.fault(Fault.STORAGE, "alert not saved: ${e.rootCause()}")
    },
)
