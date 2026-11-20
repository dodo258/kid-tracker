package org.traccar.client

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.traccar.client.alert.Alarm
import org.traccar.client.alert.AlertOutbox

class Tracker internal constructor(
    val config: Config,
    private val stateStore: StateStore,
    internal val locationSource: LocationSource,
    private val batteryProcessor: PositionProcessor,
    private val uploader: Uploader,
    internal val componentScope: ComponentCoroutineScope,
    private val engine: TrackerEngine,
    internal val alerts: AlertOutbox,
) {
    val state: StateFlow<State> = stateStore.state

    suspend fun start() = sharedMutex.withLock {
        Log.log("Tracker start ${config.serverUrl} ${config.deviceId}")
        stateStore.update { it.copy(enabled = true) }
    }

    /** 关追踪：开着时先记一条 trackingOff 告警告诉家长（存好就关，后台去发，断网联网后补发） */
    suspend fun stop() = sharedMutex.withLock {
        Log.log("Tracker stop")
        if (stateStore.state.value.enabled) alerts.raise(Alarm.TRACKING_OFF, "tracking turned off in app")
        stateStore.update { it.copy(enabled = false, paused = false) }
    }

    /**
     * 求救（首页按钮、桌面快捷）：先存本机（还有没发出去的求救就重发那一条），[locate] 为真（有定位权限）时现取一次位置，
     * 只带新鲜的卫星点；然后发一次。返回这条的编号和服务器收到没有：没收到的留着，联网后自动补发，界面带 [retry] 再按是重发同一条。
     * 追踪开没开都能用。
     */
    suspend fun sendSos(locate: Boolean, retry: Long? = null): SosSend {
        // 界面上「再发一次」带着上次那条的编号：只重发那一条，后台已经补发出去了就直接说发出去了，不另起一条
        val id = retry?.takeIf { alerts.exists(it) } ?: alerts.sos { if (locate) locationSource.fetchOnce() else null }
        // 只等这一条求救：发出去就返回，不等后面排着的普通告警
        if (!alerts.isSent(id)) alerts.flush(until = id)
        return SosSend(id, alerts.isSent(id))
    }

    /** 这条告警服务器收到了没有（求救界面在「还没发出去」时隔几秒问一次，后台补发成功了就改口） */
    suspend fun isAlertSent(id: Long): Boolean = alerts.isSent(id)

    /**
     * 防护被动了（设备管理员被停用、App 密码连续输错 5 次）：记一条 tampering 告警，先存再发，和别的告警一样联网补发。
     * 追踪开没开都报（关追踪本身另有一条 trackingOff）
     */
    suspend fun reportTampering(reason: String): Long = alerts.raise(Alarm.TAMPERING, reason, reported = reason)

    /** 把没发出去的告警发一遍（联网任务叫起进程时用）；全发完返回 true */
    suspend fun flushAlerts(): Boolean = alerts.flush()

    suspend fun requestPosition(alarm: String? = null): Boolean {
        Log.log("Position requested${alarm?.let { " alarm=$it" } ?: ""}")
        val raw = locationSource.fetchOnce() ?: run {
            Log.log("Position request: no fix")
            return false
        }
        // 日志会被家长导出分享：不写坐标（审计低 1）
        Log.log("Position fetched (${raw.provider ?: "unknown"}, accuracy ${raw.accuracy?.toInt() ?: "-"} m)")
        val processed = batteryProcessor.process(engine.current(raw))?.copy(alarm = alarm) ?: return false
        return uploader.upload(processed)
    }

    suspend fun getLogs(): List<LogEntry> = Log.store?.all() ?: emptyList()

    suspend fun clearLogs() = Log.store?.clear()

    suspend fun updateConfig(newConfig: Config): Tracker = sharedMutex.withLock { replace(newConfig) }

    /**
     * 停掉这一套组件、按 [newConfig] 建新的；调用方拿着 sharedMutex。旧组件最多等 [COMPONENT_STOP_TIMEOUT]：卡死的组件
     * （不理取消的取点、卡住的锁）不能把改设置和重建也拖住。原版一直等（cancelAndJoin）。
     */
    internal suspend fun replace(newConfig: Config): Tracker {
        val job = componentScope.coroutineContext.job
        job.cancel()
        withTimeoutOrNull(COMPONENT_STOP_TIMEOUT) { job.join() }
            ?: Log.log("Tracker components not stopped in $COMPONENT_STOP_TIMEOUT, starting new ones anyway")
        sharedTrackerInstance = null
        return bootstrap(newConfig)
    }
}

/** 一次求救：哪一条、服务器收到没有 */
data class SosSend(val id: Long, val sent: Boolean)

internal val COMPONENT_STOP_TIMEOUT: Duration = 10.seconds

/**
 * 按同样的配置重建一遍跟踪组件（定位源、引擎、保活全部新建；追踪开关、Wi-Fi 记忆、离线队列在库里接着用）：
 * 开机后没解锁时建的那一套在解锁后换成完整的（UserUnlock.kt）。看门狗出手走 [rescueSharedTracker]。
 * 在组件作用域外面跑（重建会取消调用方自己），不等它。[scope] 是要换掉的那一套的作用域，已经换了一套就不再重建。
 */
internal fun rebuildSharedTracker(scope: ComponentCoroutineScope, reason: String) {
    CoroutineScope(Dispatchers.Default).launch {
        sharedMutex.withLock {
            val tracker = sharedTrackerInstance
            if (tracker == null || tracker.componentScope !== scope) return@withLock
            Log.log("Rebuilding tracker: $reason")
            tracker.replace(tracker.config)
        }
    }
}

/**
 * 看门狗出手（取点连续超时、呼吸连续没接住、很久没产出上报、组件里有没接住的异常）：按 [Ladder] 一级级升——
 * 重建定位源 → 重建整套跟踪 → 报一条 fault 告警（先存再发，断网联网后补发）并重建。每次都记异常（日志、自检页「最近一次异常」）。
 * 在组件作用域外面跑（重建会取消调用方自己），不等它。[scope] 是出问题的那一套的作用域，已经换了一套就不管。
 * [wholeTracker]：第一级也重建整套（组件里有协程异常退出了，不知道是哪一个，只重建定位源救不回来）。
 */
internal fun rescueSharedTracker(scope: ComponentCoroutineScope, reason: String, wholeTracker: Boolean = false) {
    CoroutineScope(Dispatchers.Default).launch {
        sharedMutex.withLock {
            val tracker = sharedTrackerInstance
            if (tracker == null || tracker.componentScope !== scope) return@withLock
            val rescue = ladder.next(Elapsed.now())
            val location = tracker.locationSource as? RebuildableLocationSource
            when {
                rescue == Rescue.LOCATION && location != null && !wholeTracker -> {
                    Log.fault(Fault.RESCUE, "level 1, rebuilding location source: $reason")
                    location.rebuild()
                }
                rescue == Rescue.ALERT -> {
                    Log.fault(Fault.RESCUE, "level 3, alerting parent and rebuilding tracker: $reason")
                    try {
                        tracker.alerts.raise(Alarm.FAULT, "watchdog: $reason", reported = "watchdog")
                    } catch (e: Exception) {
                        Log.fault(Fault.STORAGE, "watchdog alert not stored: $e")
                    }
                    tracker.replace(tracker.config)
                }
                else -> {
                    Log.fault(Fault.RESCUE, "level 2, rebuilding tracker: $reason")
                    tracker.replace(tracker.config)
                }
            }
        }
    }
}

private val ladder = Ladder()

private val sharedMutex = Mutex()
private var sharedTrackerInstance: Tracker? = null

suspend fun sharedTracker(): Tracker? = sharedMutex.withLock {
    sharedTrackerInstance?.let { return@withLock it }
    val koin = openKoin()
    val persisted = koin.get<ConfigStore>().load() ?: return@withLock null
    install(koin, persisted)
}

suspend fun sharedTracker(config: Config): Tracker = sharedMutex.withLock {
    sharedTrackerInstance?.let { return@withLock it }
    bootstrap(config)
}

private suspend fun bootstrap(config: Config): Tracker {
    val koin = openKoin()
    koin.get<ConfigStore>().save(config)
    return install(koin, config)
}

private suspend fun openKoin() = withContext(Dispatchers.IO) {
    val koin = koinApplication { modules(coreModule(), platformModule()) }.koin
    Log.store = LogStore(koin.get())
    koin
}

private suspend fun install(koin: Koin, config: Config): Tracker {
    val stateStore = StateStore.create(koin.get())
    koin.declare(stateStore)
    koin.declare(config)
    if (!config.location.stopDetection) {
        stateStore.update { it.copy(paused = false) }
    }
    koin.get<TrackerEngine>()
    // 告警发件箱：建起来就把上次没发出去的（关机前的、断网时的）接着发
    koin.get<AlertOutbox>()
    // 告警的系统广播（电量、关机、定位开关）：只有 Android 装
    koin.getOrNull<AlertSources>()
    // 保活（呼吸闹钟兼看门狗）：只有 Android 装
    koin.getOrNull<KeepAlive>()
    return koin.get<Tracker>().also { sharedTrackerInstance = it }
}
