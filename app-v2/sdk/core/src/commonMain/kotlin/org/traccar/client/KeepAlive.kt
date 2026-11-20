package org.traccar.client

import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.traccar.client.policy.ReportPolicy

/** 平台唯一的定时唤醒（Android：精确、打盹时也响的闹钟）。再挂一次就替换上一次 */
interface WakeAlarm {
    fun scheduleIn(delayMillis: Long)
    fun cancel()
}

/** 前台服务：在不在跑、拉起来 */
interface ForegroundService {
    val running: Boolean
    /**
     * 服务实例还在、却已经不是前台服务（电池「受限」时系统把它降成普通服务，不经过服务自己，[running] 照旧为真）。
     * 问不到的平台当没降级
     */
    val demoted: Boolean get() = false
    fun start()
}

/** 呼吸时临时拿的唤醒锁：每次都带上限，忘了放系统也会到点放 */
interface WakeHold {
    fun hold(limitMillis: Long)
    fun release()
}

/**
 * 保活要引擎做的：呼吸一次（判断层看看到点没有）、等这一次的取点和上传做完；[reports] 每产出一条上报加一；
 * [quiet] 为真时（飞行模式）判断层说全停，没有上报是应该的
 */
internal interface Breather {
    val reports: StateFlow<Long>
    val quiet: Boolean get() = false
    suspend fun breathe()
    suspend fun awaitSettled()
}

/**
 * 保活（R4）：原版的前台服务为底，加一个呼吸闹钟，兼做看门狗。
 *
 * - **只有一个定时唤醒**：追踪开着就挂着，每产出一条上报就往后推到 5 分钟后（[breathEvery]）。在动时 10 秒一条，它永远不响；
 *   静止、屋里时 5 分钟响一次，叫醒判断层呼吸 / 取网络定位；5 分钟一条都没报（卡住、进程没了）它也会响，这就是看门狗。
 *   挂在「上一条报完 5 分钟后」而不是按状态各挂各的：判断层的到点是从上一次报算的，闹钟只要不早于它就不会白响一次、
 *   把间隔拖成 10 分钟（屋里转静止时状态没换「卫星关着」，按状态挂就会这样）。
 * - 响了：先挂下一次（处理中途卡住也不断），前台服务不在、或被系统降成了普通服务就拉起来，让引擎呼吸一次。一直在上报时闹钟不响，
 *   所以上报时也隔一阵看一眼前台服务，不在了就挂一个马上响的闹钟、不再往后推（[serviceLost]）；
 *   [ackLimit] 内引擎没接，1 分钟后再来，连续 [STUCK_LIMIT] 次没接就交给看门狗（[onStuck]）。接了就拿着唤醒锁等这次的取点、上传做完再放，最多 [holdLimit]。
 * - 不长期拿唤醒锁：只在闹钟响到这次的活干完之间拿，有上限。
 * - **多久没上报**：追踪开着、不在飞行模式，[noReportLimit] 一条上报都没产出（判断层卡在某个状态、引擎的循环停了、时间被改乱了），
 *   也交给看门狗。闹钟响时看一次；另有一个进程里的定时器每 [checkEvery] 看一次（闹钟被系统清了时，进程只要还醒着就能发现，
 *   顺手把闹钟重挂）。
 * - 看门狗（[onStuck]）按次升级：重建定位源 → 重建整套跟踪 → 报告警（见 [Ladder]）。
 *
 * 原版只有前台服务和按设置的不精确心跳闹钟（默认不开），靠 Google 活动识别叫醒。不加会怎样：国内手机静止、屋里时 CPU 睡着，
 * 判断层没人叫，打盹时不精确闹钟会被拖到 9–15 分钟以上；定位源或引擎卡住时一直卡着（P20 2026-11-18 一整夜不报）。
 */
internal class KeepAlive(
    private val scope: CoroutineScope,
    private val state: StateFlow<State>,
    private val engine: Breather,
    private val alarm: WakeAlarm,
    private val service: ForegroundService,
    private val wake: WakeHold,
    fired: Flow<Unit>,
    private val onStuck: (reason: String) -> Unit,
    private val breathEvery: Long = ReportPolicy.BREATH_EVERY + ALARM_MARGIN,
    private val retryAfter: Long = BREATH_RETRY,
    private val ackLimit: Duration = BREATH_ACK_LIMIT,
    private val holdLimit: Duration = BREATH_HOLD_LIMIT,
    private val noReportLimit: Duration = NO_REPORT_LIMIT,
    private val checkEvery: Duration = CHECK_EVERY,
    private val serviceCheckEvery: Long = SERVICE_CHECK_EVERY,
    private val promoteAfter: Long = PROMOTE_AFTER,
    /** 开机以来的毫秒数（手机睡着也在走），见 [Elapsed] */
    private val elapsed: () -> Long = Elapsed::now,
) {
    private val notHandled = StuckWatch("breath not handled", onStuck = onStuck)
    @Volatile
    private var lastReport = elapsed()
    @Volatile
    private var lastBreath = elapsed()
    @Volatile
    private var lastTrouble: Long? = null
    @Volatile
    private var lastServiceCheck: Long? = null
    /** 发现前台服务不在 / 被降级、挂了短闹钟去拉起来：闹钟响之前上报不再把它往后推 */
    @Volatile
    private var promotePending = false

    init {
        scope.launch {
            state.map { it.enabled }.distinctUntilChanged().collect { on ->
                if (on) {
                    alarm.scheduleIn(breathEvery)
                    Log.log("Breath alarm in ${breathEvery / 1000}s")
                } else {
                    // 只在主人关追踪时撤；组件重建、改设置时作用域取消不撤，新的一套自己会重挂，进程要死了闹钟留着正好把它叫回来
                    alarm.cancel()
                    Log.log("Breath alarm cancelled")
                }
            }
        }
        // 每条上报往后推，不写日志（在动时 10 秒一次）
        scope.launch {
            engine.reports.drop(1).collect {
                lastReport = elapsed()
                if (state.value.enabled && !serviceLost()) alarm.scheduleIn(breathEvery)
            }
        }
        // 刚开追踪（从关到开）重新计时
        scope.launch {
            state.map { it.enabled }.distinctUntilChanged().drop(1).collect {
                lastReport = elapsed()
                lastBreath = elapsed()
                promotePending = false
            }
        }
        scope.launch { fired.collect { onAlarm() } }
        scope.launch {
            while (true) {
                delay(checkEvery)
                check()
            }
        }
    }

    /**
     * 看一眼有没有卡住：追踪开着、不在飞行模式，[noReportLimit] 没产出上报就交给看门狗（两次之间至少隔 [breathEvery]，
     * 给上一次处理留出时间）；闹钟超过两个周期没响也没上报，多半是被系统清了，重挂。
     */
    private fun check() {
        if (!state.value.enabled) return
        val now = elapsed()
        val idle = now - maxOf(lastReport, lastBreath)
        if (idle > breathEvery * 2) {
            Log.log("Breath alarm overdue (${idle / 1000}s without breath or report), rescheduling")
            alarm.scheduleIn(retryAfter)
        }
        if (engine.quiet) {
            lastReport = now
            return
        }
        val silent = (now - lastReport).milliseconds
        if (silent <= noReportLimit) return
        if (lastTrouble?.let { now - it < breathEvery } == true) return
        lastTrouble = now
        onStuck("no report for ${silent.inWholeMinutes} minutes")
    }

    /**
     * 上报时顺便看前台服务（最多每 [serviceCheckEvery] 一次）：不在、或被系统降成了普通服务，就挂一个 [promoteAfter] 后的闹钟走呼吸里拉起来的路，
     * 之后的上报不再把它往后推。不这样的话一直在报（走路、坐车、屋里走动）时呼吸闹钟永远不响，降级一直恢复不了（审计：30 分钟 0 次）。
     * 已经挂了返回 true
     */
    private fun serviceLost(): Boolean {
        if (promotePending) return true
        val now = elapsed()
        if (lastServiceCheck?.let { now - it < serviceCheckEvery } == true) return false
        lastServiceCheck = now
        if (service.running && !service.demoted) return false
        Log.log("Service not in the foreground while reporting, promoting in ${promoteAfter / 1000}s")
        promotePending = true
        alarm.scheduleIn(promoteAfter)
        return true
    }

    private suspend fun onAlarm() {
        if (!state.value.enabled) {
            alarm.cancel()
            wake.release()
            return
        }
        val started = TimeSource.Monotonic.markNow()
        lastBreath = elapsed()
        promotePending = false
        wake.hold(holdLimit.inWholeMilliseconds)
        alarm.scheduleIn(breathEvery)
        if (!service.running) {
            Log.log("Breath: service was not running, starting")
            service.start()
        } else if (service.demoted) {
            // 降级期间后台定位受限、进程容易被杀；重新拉成前台，接着这次呼吸补一条（旧版 24c85fa，压测 5b 修前 20 分钟不恢复）
            Log.log("Breath: service is no longer in the foreground, promoting")
            service.start()
        }
        if (withTimeoutOrNull(ackLimit) { engine.breathe() } == null) {
            Log.log("Breath: not handled in $ackLimit, checking again in ${retryAfter / 1000}s")
            alarm.scheduleIn(retryAfter)
            // 不放唤醒锁：可能马上要重建，锁到上限自己放
            notHandled.stuck()
            return
        }
        notHandled.ok()
        val left = holdLimit - started.elapsedNow()
        if (withTimeoutOrNull(left) { engine.awaitSettled() } == null) {
            Log.log("Breath: work not done in $holdLimit, releasing wake lock")
        }
        check()
        wake.release()
    }

    internal companion object {
        /** 闹钟比判断层的到点晚这么一点：两边各自取的时间差几毫秒，早了判断层会说还没到、白响一次 */
        const val ALARM_MARGIN = 2_000L
        /** 引擎没接住呼吸时隔多久再看一次（连着三次就重建） */
        const val BREATH_RETRY = 60_000L
        /** 引擎接住一次呼吸最多等多久：正常只是锁里的几毫秒判断，取点、上传都不在锁里 */
        val BREATH_ACK_LIMIT: Duration = 20.seconds
        /** 一次呼吸最多拿唤醒锁这么久：网络定位最多 40 秒（30 秒 + 护栏 10 秒），再留上传的时间 */
        val BREATH_HOLD_LIMIT: Duration = 75.seconds

        /** 这么久一条上报都没产出算卡住：判断层最慢 5 分钟一条（静止呼吸），两个周期都没有就不对了 */
        val NO_REPORT_LIMIT: Duration = 10.minutes + 30.seconds

        /** 上报时最多隔这么久看一次前台服务被没被降级（问系统要列一遍在跑的服务，不每 10 秒问） */
        const val SERVICE_CHECK_EVERY = 300_000L

        /** 上报时发现前台服务被降级：这么久后响闹钟拉起来（走呼吸那条路，带唤醒锁） */
        const val PROMOTE_AFTER = 5_000L

        /** 进程里的定时检查：只在 CPU 醒着时跑，不叫醒手机 */
        val CHECK_EVERY: Duration = 5.minutes
    }
}
