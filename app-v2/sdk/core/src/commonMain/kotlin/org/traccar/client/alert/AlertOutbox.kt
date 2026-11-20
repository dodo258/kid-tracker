package org.traccar.client.alert

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.traccar.client.Fault
import org.traccar.client.Log
import org.traccar.client.Position
import org.traccar.client.PositionProcessor
import org.traccar.client.Provider
import org.traccar.client.Uploader

/** 进程被杀、断网时还有没发出去的告警：平台在联网时把进程叫起来补发（Android：要联网的 JobScheduler 任务） */
fun interface AlertRetry {
    fun schedule()
}

/**
 * 告警发件箱（R5）：求救、关追踪、低电、关机、关定位都走这里。
 *
 * - **先存再发**：告警先写进本机库（[AlertStore]），服务器回 2xx 才记「已报」；断网、超时、非 2xx 都留着，联网后补发。
 *   进程被杀、关机重启后，下次跟踪组件建起来（开机自启、打开 App、[AlertRetry] 的联网任务）接着发。
 * - **不重复**：发送全进程一把锁（组件重建时新旧两套会短暂并存，也不会把同一条发两次）；没发出去的求救再按一次是重发那一条，
 *   不另起一条。服务器回了 2xx 但本机没来得及记就被杀的，下次会再发一次（宁可重一次也不漏）。
 * - **位置只用新鲜的卫星点**（[freshGpsFix]，审计 M4）：引擎手里 [ALERT_FIX_FRESH] 内的卫星点就带上（时间用定位时间）；
 *   求救另外现取一次；都没有就只报时间。
 * - 发的顺序：求救先，其余按先后；每发完一条重新挑，中途来的求救排到下一条。一条发不出去就停（多半是断网），等联网或退避后再来。
 *
 * 原版没有：原版的求救走单次上报，发不出去就丢了，桌面快捷求救还看不到结果（审计 M3）；根目录旧 App 的设备告警
 * 先发后存、失败才进位置队列，管理策略告警先记「报过」再发（审计 M5）。
 */
class AlertOutbox(
    private val store: AlertStore,
    private val uploader: Uploader,
    private val processor: PositionProcessor,
    private val isOnline: StateFlow<Boolean>,
    scope: CoroutineScope,
    /** 引擎最近收到的卫星点 */
    private val latestGps: () -> Position? = { null },
    /** 带上现在的状态、运动、联网方式（引擎的 current） */
    private val describe: (Position) -> Position = { it },
    private val retry: AlertRetry? = null,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val uploadTimeout: Duration = UPLOAD_TIMEOUT,
    private val initialBackoff: Duration = 5.seconds,
    private val maxBackoff: Duration = 5.minutes,
    private val offlineRecheck: Duration = 5.minutes,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    // 求救一次只记一条：两处同时按（首页和桌面快捷）也只有一条
    private val sosLock = Mutex()

    init {
        scope.launch { run() }
    }

    /**
     * 记一条告警（低电、关机、关定位、关追踪、防护被动）：带上电量、新鲜的卫星点，存好就返回，由后台去发。
     * [reason] 写日志；[reported] 不为空时作为 `reason` 一起报给服务器（tampering 按它分文案）
     */
    suspend fun raise(
        alarm: String,
        reason: String,
        battery: Int? = null,
        charging: Boolean? = null,
        reported: String? = null,
    ): Long {
        val at = now()
        val fix = freshGpsFix(latestGps(), at)
        val id = store(build(alarm, at, fix, battery, charging).copy(reason = reported), ready = true)
        Log.log("Alert $alarm #$id stored: $reason${if (fix == null) ", time only" else ""}")
        return id
    }

    /**
     * 求救：还有没发出去的求救就重发那一条；否则新记一条。手里没有新鲜卫星点时先存一条只报时间的（取点时进程被杀也不丢），
     * 再用 [locate] 现取一次，取到新鲜卫星点就换上。返回这条求救的编号，调用方再 [flush]、[isSent] 看结果。
     */
    suspend fun sos(locate: suspend () -> Position?): Long = sosLock.withLock {
        store.pending(Alarm.SOS)?.let {
            Log.log("Alert sos #${it.id} still not sent, sending it again")
            return@withLock it.id
        }
        val at = now()
        freshGpsFix(latestGps(), at)?.let { fix ->
            return@withLock store(build(Alarm.SOS, at, fix), ready = true).also { Log.log("Alert sos #$it stored with GPS") }
        }
        val id = store(build(Alarm.SOS, at, null), ready = false)
        val fix = try {
            freshGpsFix(locate(), now())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.log("Alert sos #$id locate failed: $e")
            null
        }
        store.locate(id, build(Alarm.SOS, at, fix))
        wake.trySend(Unit)
        Log.log("Alert sos #$id stored${if (fix == null) ", time only" else " with GPS"}")
        id
    }

    suspend fun isSent(id: Long): Boolean = store.get(id)?.sentAt != null

    suspend fun exists(id: Long): Boolean = store.get(id) != null

    /**
     * 把没发出去的发一遍：全发完（只剩还在等定位的求救不算）返回 true，有一条发不出去就停、返回 false。
     * 一次只拿一条：每发完一条重新读待发的、求救先挑（审计高 12：以前一开始就读好整批，新求救排在整批旧告警后面），
     * 发送的锁也只在发这一条时拿着，别的入口（App 里按求救）发完当前这条就能插进来。给了 [until] 时那一条发出去就返回 true。
     */
    suspend fun flush(until: Long? = null): Boolean {
        while (true) {
            when (sendLock.withLock { sendNext() }) {
                Step.SENT -> if (until != null && isSent(until)) return true
                Step.DONE -> return true
                Step.WAITING, Step.FAILED -> return false
            }
        }
    }

    private enum class Step { SENT, DONE, WAITING, FAILED }

    private suspend fun sendNext(): Step {
        val pending = store.pending()
        // 时间被往回改过时差是负的，也当等够了，不让这条一直等下去
        val alert = pending.filter { it.ready || now() - it.time !in 0 until LOCATE_LIMIT }
            .minWithOrNull(compareBy<Alert> { it.alarm != Alarm.SOS }.thenBy { it.id })
            ?: return if (pending.isEmpty()) Step.DONE else Step.WAITING
        val sent = withTimeoutOrNull(uploadTimeout) { uploader.upload(alert.position) } == true
        if (!sent) {
            Log.log("Alert ${alert.alarm} #${alert.id} not sent, will retry")
            retry?.schedule()
            return Step.FAILED
        }
        store.markSent(alert.id, now())
        Log.log("Alert ${alert.alarm} #${alert.id} sent")
        return Step.SENT
    }

    private suspend fun store(position: Position, ready: Boolean): Long {
        val id = store.insert(position, ready)
        val dropped = store.trim(KEEP_SENT, KEEP_PENDING)
        if (dropped > 0) Log.fault(Fault.DROPPED, "alert outbox full: dropped $dropped oldest unsent")
        retry?.schedule()
        if (ready) wake.trySend(Unit)
        return id
    }

    private suspend fun build(alarm: String, at: Long, fix: Position?, battery: Int? = null, charging: Boolean? = null): Position {
        val base = Position(
            time = fix?.time ?: at,
            latitude = fix?.latitude,
            longitude = fix?.longitude,
            accuracy = fix?.accuracy,
            altitude = fix?.altitude,
            speed = fix?.speed,
            bearing = fix?.bearing,
            provider = fix?.let { Provider.GPS },
            battery = battery,
            charging = charging,
            alarm = alarm,
        )
        return describe(processor.process(base) ?: base)
    }

    /**
     * 后台：联网就发（系统说没网时最多等 [offlineRecheck] 也试一次，联网回调漏了不一直等）；发不出去按 5 秒起翻倍、
     * 最多 5 分钟退避；有新告警马上再试。读写库出错（存储满）也退避后再来，循环不停。
     */
    private suspend fun run() {
        var backoff = initialBackoff
        while (true) {
            if (!isOnline.value && withTimeoutOrNull(offlineRecheck) { isOnline.first { it } } != null) backoff = initialBackoff
            val done = try {
                flush()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.fault(Fault.STORAGE, "alert outbox error: $e")
                false
            }
            if (done) {
                backoff = initialBackoff
                wake.receive()
                continue
            }
            withTimeoutOrNull(backoff) { wake.receive() }
            backoff = (backoff * 2).coerceAtMost(maxBackoff)
        }
    }

    companion object {
        /** 一条告警的上传最多等这么久（HTTP 客户端默认等 100 秒） */
        val UPLOAD_TIMEOUT: Duration = 15.seconds

        /** 求救现取位置最多等这么久（取点 30 秒 + 护栏 10 秒，再留余量）：超过还没定下来（取点时进程被杀）就只报时间发出去 */
        const val LOCATE_LIMIT = 90_000L

        const val KEEP_SENT = 50L
        const val KEEP_PENDING = 200L
    }
}

// 全进程一把：组件重建时新旧两个发件箱、联网任务会同时来发
private val sendLock = Mutex()
