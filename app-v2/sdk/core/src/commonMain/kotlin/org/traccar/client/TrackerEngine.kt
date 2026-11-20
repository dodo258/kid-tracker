package org.traccar.client

import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.traccar.client.motion.Motion
import org.traccar.client.policy.Decision
import org.traccar.client.policy.Judge
import org.traccar.client.sense.Sky
import org.traccar.client.sense.Snapshot

/**
 * 引擎：按判断层（policy/[Judge]）说的做——开关卫星连续定位（[State.paused]）、取网络定位、把要报的点交给原版的上报和离线队列。
 * 替换原版的跟踪模式判断：原版靠 Google 活动识别和地理围栏进出静止、静止时按设置的心跳间隔报，在动时按距离 / 时间过滤
 * （LocationFilter），国内手机用不了 Google 那两样，就一直连续定位。现在什么时候开卫星、多久报一次全由判断层定（方案第 3 节）。
 *
 * 执行层保留原版：定位源（R2 的高德 / 系统定位，取点都有时限）、上报（[Uploader]）、离线队列（[PositionQueue]）。
 * 所有输入（传感器快照、定位点、心跳、取点结果）在一把锁里按到达顺序喂给判断层；取点和上传都不在锁里做。
 * 保活（[KeepAlive]）从这里看「报了没有」（[reports]）、叫它呼吸（[breathe]）、等这一次的活干完再放唤醒锁（[awaitSettled]）。
 */
class TrackerEngine internal constructor(
    private val stateStore: StateStore,
    private val queue: PositionQueue,
    private val network: NetworkMonitor,
    private val locationSource: LocationSource,
    private val sensors: SensorSource,
    signalSources: List<SignalSource>,
    private val processors: List<PositionProcessor>,
    private val uploader: Uploader,
    private val buffer: Boolean,
    private val scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val initialBackoff: Duration = 5.seconds,
    private val maxBackoff: Duration = 5.minutes,
    private val uploadLimit: Duration = UPLOAD_LIMIT,
    private val offlineRecheck: Duration = OFFLINE_RECHECK,
    /** 开机以来的毫秒数（[Elapsed]）：和系统时间比，发现时间被改了；不给就只能靠判断层自己发现往回改 */
    private val elapsed: (() -> Long)? = null,
) : Breather {
    private val mutex = Mutex()
    private val outbox = Channel<Position>(Channel.UNLIMITED)
    private val pipelineWakeUp = Channel<Unit>(Channel.CONFLATED)
    // 服务器失败退避时，能让发送循环提前试发的只有呼吸和网络恢复（[backOff]）
    private val breathWakeUp = Channel<Unit>(Channel.CONFLATED)
    private val networkRestored = Channel<Unit>(Channel.CONFLATED)
    // 进程被杀、重启、看门狗重建后接着上次的静止（别的运动状态快照几秒到一两分钟就重新判出来，静止要安静两分钟、还会先报一条「在附近活动」）
    private var judge = stateStore.state.value.let { Judge(it.wifi, restoredMotion(it), it.main) }
    private val reportCount = MutableStateFlow(0L)
    override val reports: StateFlow<Long> = reportCount.asStateFlow()
    // 还没做完的取点、还没走完处理流程（进离线队列或发出）的点
    private val inFlight = MutableStateFlow(0)
    // 离线队列这边没活（空了、没网在等、失败了在等重试）；有点要发时为 false
    private val syncIdle = MutableStateFlow(false)
    // 发送循环正在等联网：这时新入队的点已经安全进了库，也算这边没活。不单独记的话，入队把 syncIdle 设成 false，
    // 发送循环卡在等联网收不到唤醒，每次离线呼吸都白拿满唤醒锁（75 秒）
    private val waitingForNetwork = MutableStateFlow(false)

    private val enabled: Boolean get() = stateStore.state.value.enabled

    /** 最近收到的卫星点：告警（alert/）拿它看有没有新鲜的位置可带 */
    @Volatile
    var latestGps: Position? = null
        private set

    init {
        // 判断层建引擎时就有（接着状态库里的 Wi-Fi 记忆）；之后每次从关到开追踪重新判。不在第一次收到「开着」时重建：
        // 那时快照可能已经进来、判断层已经报过状态，再建一个会把状态重报一遍
        scope.launch {
            mutex.withLock { apply() }
            stateStore.state.map { it.enabled }.distinctUntilChanged().drop(1).collect { on ->
                if (on) mutex.withLock { restart() }
            }
        }
        scope.launch { signalSources.map { it.signals }.merge().collect { handle(it) } }
        scope.launch { sensors.snapshots.collect { onSnapshot(it) } }
        scope.launch { locationSource.positions.collect { onFix(it) } }
        scope.launch { pipelineLoop() }
        scope.launch { syncLoop() }
        scope.launch { network.isOnline.drop(1).filter { it }.collect { networkRestored.trySend(Unit) } }
    }

    /** 原版的进出静止信号（iOS 的活动识别、地理围栏）不用：判断层自己判断。心跳（iOS 的后台任务）和呼吸一样 */
    suspend fun handle(signal: Signal) {
        if (signal == Signal.HeartbeatTick) breathe()
    }

    /** 呼吸闹钟响了（静止、屋里时 CPU 睡着，没有快照）：让策略看看到点没有 */
    override suspend fun breathe() = mutex.withLock {
        if (!enabled) return@withLock
        Log.log("Breath")
        breathWakeUp.trySend(Unit)
        val before = judge.motion
        val decision = judge.onTick(clock())
        if (judge.motion != before) Log.log("Motion ${before ?: "-"} -> ${judge.motion} (no sensor data)")
        execute(decision)
    }

    /** 飞行模式：判断层说全停，没有上报是应该的，看门狗不当卡住 */
    override val quiet: Boolean get() = judge.motion == Motion.OFF

    override suspend fun awaitSettled() {
        combine(inFlight, syncIdle, waitingForNetwork) { n, idle, waiting -> n == 0 && (idle || waiting) }.first { it }
    }

    suspend fun onSnapshot(snapshot: Snapshot) = mutex.withLock {
        if (!enabled) return@withLock
        val before = judge.motion
        val decision = judge.onSnapshot(clock(), snapshot)
        if (judge.motion != before) Log.log("Motion ${before ?: "-"} -> ${judge.motion}")
        execute(decision)
    }

    suspend fun onFix(position: Position) = mutex.withLock {
        if (!enabled) return@withLock
        // 告警缓存只收能当主坐标的卫星点：屋里误差几公里的点不能顶掉刚才那个好点，也不能被求救带出去（审计 11.0.11 第 1 条）
        if (position.provider == Provider.GPS && Sky.mainCoordinate(position.accuracy)) latestGps = position
        val decision = judge.onFix(clock(), position, elapsed?.invoke())
        if (decision == null) {
            Log.log("Wi-Fi fix dropped: on a hotspot")
            return@withLock
        }
        execute(decision)
    }

    private var lastWall: Long? = null
    private var lastElapsed: Long? = null

    /**
     * 现在的系统时间（锁里调）。和开机时长对一下：系统时间比开机时长多走或少走了 [CLOCK_JUMP] 以上，就是时间被改了，
     * 告诉判断层把记的时间整体挪过去，节奏不变（往回改一天不会一天不报，往前改也不会马上多报一条）。
     */
    private fun clock(): Long {
        val wall = now()
        val e = elapsed?.invoke() ?: return wall
        val lw = lastWall
        val le = lastElapsed
        lastWall = wall
        lastElapsed = e
        if (lw != null && le != null) {
            val drift = wall - (lw + (e - le))
            if (drift > CLOCK_JUMP || drift < -CLOCK_JUMP) {
                Log.log("Clock changed by ${drift / 1000}s, shifting judgement timers")
                judge.clockShifted(drift)
            }
        }
        return wall
    }

    /** App 里「发送位置」这类不走策略的点：带上现在的状态和联网方式 */
    fun current(position: Position): Position = judge.current(position)

    private suspend fun restart() {
        judge = stateStore.state.value.let { Judge(it.wifi, main = it.main) }
        Log.log("Judgement restarted")
        apply()
    }

    private suspend fun execute(decision: Decision) {
        decision.report?.let { out ->
            val position = judge.describe(out)
            val where = position.provider ?: position.netSource?.let { "$it (net only)" } ?: "time only"
            // 带上这一条的时间：压测（tools/stability/v2_stress.py）拿它和服务器收到的逐条对，算丢没丢
            Log.log("Report ${position.state} ${out.reason} $where at ${position.time}")
            inFlight.update { it + 1 }
            reportCount.update { it + 1 }
            outbox.send(position)
        }
        if (decision.fetch) {
            val owner = judge
            inFlight.update { it + 1 }
            scope.launch {
                try {
                    val fix = try {
                        locationSource.fetchNetworkOnce()
                    } catch (e: CancellationException) {
                        // 引擎自己停了：照常结束。不是的话是定位源被看门狗重建、旧的取点跟着取消了：当这次没取到交回判断层，
                        // 不然判断层一直以为还在取点，之后屋里、在路上没卫星时一条都不报
                        currentCoroutineContext().ensureActive()
                        Log.log("Network fix cancelled: location source rebuilt")
                        null
                    } catch (e: Exception) {
                        Log.log("Network fix failed: $e")
                        null
                    }
                    // 每次取网络定位都记一行：真机日志里能数静止时一小时取了几次（每小时最多 4 次，见 ReportPolicy）
                    Log.log("Network fix: ${fix?.let { "${it.provider} ${it.accuracy?.toInt()}m" } ?: "none"}")
                    mutex.withLock {
                        if (enabled && owner === judge) execute(judge.onFetched(clock(), fix))
                    }
                } finally {
                    inFlight.update { it - 1 }
                }
            }
        }
        apply()
    }

    /** 把判断层的意思落到执行层：Wi-Fi 记忆、上一个主坐标存起来、卫星开关、传感器睡不睡 */
    private suspend fun apply() {
        if (judge.wifiChanged) {
            judge.wifiChanged = false
            val memory = judge.wifiMemory
            stateStore.update { it.copy(wifi = memory) }
        }
        if (judge.mainChanged) {
            judge.mainChanged = false
            val main = judge.mainFix
            stateStore.update { it.copy(main = main) }
        }
        val paused = !judge.gps
        if (stateStore.state.value.paused != paused) {
            Log.log(if (paused) "GPS off" else "GPS on")
            stateStore.update { it.copy(paused = paused) }
        }
        val motion = judge.motion?.name
        if (stateStore.state.value.motion != motion) stateStore.update { it.copy(motion = motion) }
        sensors.setSleeping(judge.sleeping)
    }

    /**
     * 产出的点 → 电量等处理 → 进离线队列（或不缓冲时直接发）。一个点出错只丢这一个点、记下来，循环接着跑：
     * 原版没接异常，库写不进去一次整个循环就停了，之后一个点都不报。
     */
    private suspend fun pipelineLoop() {
        for (incoming in outbox) {
            try {
                var current: Position? = incoming
                for (processor in processors) {
                    current = processor.process(current ?: break)
                }
                val final = current ?: continue
                if (buffer) {
                    queue.enqueue(final)
                    syncIdle.value = false
                    pipelineWakeUp.trySend(Unit)
                } else {
                    upload(final)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.fault(Fault.DROPPED, "point at ${incoming.time} not queued: ${e.rootCause()}")
            } finally {
                inFlight.update { it - 1 }
            }
        }
    }

    /** 一次上传最多 [uploadLimit]：HTTP 客户端自己也有时限，这里再兜一层，服务器卡着不回不能把发送停住 */
    private suspend fun upload(position: Position): Boolean =
        withTimeoutOrNull(uploadLimit) { uploader.upload(position) } ?: run {
            Log.log("Upload timed out after $uploadLimit")
            false
        }

    /**
     * 离线队列 → 服务器。没网时等联网，但最多等 [offlineRecheck] 就试一次（系统的联网回调漏了也不会一直等）；
     * 发不出去、读写队列出错都退避后再来，循环不停。系统说没网（Wi-Fi 没标「已验证」）、试发却成功了：这个网络到得了服务器，
     * 接着把队列发完，不再每条等 [offlineRecheck]（审计高 11）；发失败了、或者系统那边网络变了（标成在线）再照常等。
     */
    private suspend fun syncLoop() {
        var backoff = initialBackoff
        var offline = false
        var reachable = false
        while (currentCoroutineContext().isActive) {
            if (network.isOnline.value) reachable = false
            val sent = try {
                val pending = queue.peek()
                when {
                    pending == null -> {
                        syncIdle.value = true
                        pipelineWakeUp.receive()
                        null
                    }
                    !network.isOnline.value && !offline && !reachable -> {
                        waitingForNetwork.value = true
                        Log.log("Offline, waiting for network")
                        offline = withTimeoutOrNull(offlineRecheck) { network.isOnline.first { it } } == null
                        waitingForNetwork.value = false
                        syncIdle.value = false
                        if (!offline) Log.log("Network restored")
                        null
                    }
                    upload(pending) -> {
                        queue.removeFirst()
                        true
                    }
                    else -> false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.fault(Fault.STORAGE, "offline queue error: ${e.rootCause()}")
                false
            }
            when (sent) {
                true -> {
                    backoff = initialBackoff
                    if (!network.isOnline.value) reachable = true
                    offline = false
                }
                false -> {
                    reachable = false
                    Log.log("Upload failed, retrying in $backoff")
                    backOff(backoff)
                    backoff = (backoff * 2).coerceAtMost(maxBackoff)
                    // 系统说没网时这次试过了，下一轮接着等联网
                    offline = false
                }
                null -> Unit
            }
        }
    }

    /**
     * 服务器失败后的退避。这边没活（呼吸拿的唤醒锁不用等到退避结束）；到期（按开机时长算：手机深睡时 delay 不走，醒来时已经过了就马上试）、
     * 网络恢复、呼吸来了且离上次试发满 [BREATH_RETRY_GAP] 才提前试发。新入库的点不打断：服务器持续失败、行车每 10 秒一个点时，
     * 以前每个点都试发一次同一个队首（审计 11.0.11 第 8 条），入库只把「没活」标回来。
     */
    private suspend fun backOff(backoff: Duration) {
        val tried = monotonic()
        val due = tried + backoff.inWholeMilliseconds
        networkRestored.tryReceive()
        syncIdle.value = true
        withTimeoutOrNull(backoff) {
            while (monotonic() < due) {
                val early = select {
                    networkRestored.onReceive { true }
                    breathWakeUp.onReceive { monotonic() - tried >= BREATH_RETRY_GAP }
                    pipelineWakeUp.onReceive { false }
                }
                if (early) break
                syncIdle.value = true
            }
        }
        syncIdle.value = false
    }

    /** 开机以来的毫秒数（手机睡着也在走）；测试没给时用系统时间 */
    private fun monotonic(): Long = elapsed?.invoke() ?: now()

    internal companion object {
        /** 服务器失败退避期间，呼吸最多隔这么久提前试发一次 */
        const val BREATH_RETRY_GAP = 60_000L

        /** 一次上传最多等这么久（HTTP 客户端的整次请求时限 30 秒，再留一点） */
        val UPLOAD_LIMIT: Duration = 40.seconds

        /** 系统说没网时最多等这么久就不管它、试发一次 */
        val OFFLINE_RECHECK: Duration = 5.minutes

        /** 系统时间和开机时长差出这么多才算被改了（自动对时一般是几秒以内的微调） */
        const val CLOCK_JUMP = 30_000L
    }
}

/** 重启后接着用的运动判断：只接静止（见 [State.motion]） */
private fun restoredMotion(state: State): Motion? = Motion.STILL.takeIf { state.enabled && state.motion == it.name }
