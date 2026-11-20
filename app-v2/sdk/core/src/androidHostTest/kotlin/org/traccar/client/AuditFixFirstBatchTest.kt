package org.traccar.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.traccar.client.db.Database
import org.traccar.client.sense.Snapshot

/**
 * 上报端 v2 审计第一批（docs/specs/2026-11-19-v2-audit-fixes.md）：
 * 高 7 前台服务被降级后呼吸重新拉成前台；中 6 离线时呼吸入队后不白拿唤醒锁；高 4 告警写库抛错不崩进程。
 * 装机候选版审计（同一方案「装机候选版审计」）：4 一直在上报时降级也能恢复；9 服务器失败退避期间呼吸入库就放锁。
 * 引擎、判断层、状态库用真的；闹钟、前台服务、唤醒锁、离线队列、网络换成假实现。
 */
class AuditFixFirstBatchTest {

    private val scope = ComponentCoroutineScope()
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
    private var clock = 1_800_000_000_000L
    private val uploaded = Collections.synchronizedList(mutableListOf<Position>())
    private val queued = Collections.synchronizedList(mutableListOf<Position>())
    private val online = MutableStateFlow(true)
    /** 服务器回不回成功（联网但服务器坏了 / 拒收） */
    @Volatile private var serverUp = true
    private val snapshots = MutableSharedFlow<Snapshot>(extraBufferCapacity = 256)
    private val fired = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    private val alarm = object : WakeAlarm {
        @Volatile var at: Long? = null
        override fun scheduleIn(delayMillis: Long) {
            at = clock + delayMillis
        }
        override fun cancel() {
            at = null
        }
    }

    /** 实例一直在（running 为真）；demoted 由测试拨，start() 重新起前台服务就不再是降级的 */
    private val service = object : ForegroundService {
        @Volatile override var running = true
        @Volatile override var demoted = false
        @Volatile var starts = 0
        override fun start() {
            starts++
            running = true
            demoted = false
        }
    }

    private val wake = object : WakeHold {
        @Volatile var held = false
        override fun hold(limitMillis: Long) {
            held = true
        }
        override fun release() {
            held = false
        }
    }

    private val still = Snapshot(time = 0, accMean = 0.4, accStd = 0.007, accN = 50, gyroMean = 0.001, stepsTotal = 1000)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        driver.close()
        Log.faultSink = null
    }

    private fun store() = StateStore(Database(driver).stateQueries, State(enabled = true, paused = true))

    private fun engine(stateStore: StateStore, initialBackoff: Duration = 5.seconds) = TrackerEngine(
        stateStore = stateStore,
        queue = object : PositionQueue {
            override suspend fun enqueue(position: Position) {
                queued += position
            }
            override suspend fun peek(): Position? = queued.firstOrNull()
            override suspend fun removeFirst() {
                queued.removeAt(0)
            }
        },
        network = object : NetworkMonitor {
            override val isOnline = online
        },
        locationSource = object : LocationSource {
            override val positions = emptyFlow<Position>()
            override suspend fun fetchOnce(): Position? = null
            override suspend fun fetchNetworkOnce(): Position? = null
        },
        sensors = object : SensorSource {
            override val snapshots = this@AuditFixFirstBatchTest.snapshots
            override fun setSleeping(sleeping: Boolean) = Unit
        },
        signalSources = emptyList(),
        processors = emptyList(),
        uploader = object : Uploader {
            override suspend fun upload(position: Position): Boolean {
                if (!online.value || !serverUp) return false
                uploaded += position
                return true
            }
        },
        buffer = true,
        scope = scope,
        now = { clock },
        initialBackoff = initialBackoff,
    )

    private fun keepAlive(
        state: StateStore,
        engine: Breather,
        holdLimit: Duration = 5.seconds,
        elapsed: () -> Long = Elapsed::now,
    ) = KeepAlive(
        scope = scope,
        state = state.state,
        engine = engine,
        alarm = alarm,
        service = service,
        wake = wake,
        fired = fired,
        onStuck = {},
        ackLimit = 2.seconds,
        holdLimit = holdLimit,
        elapsed = elapsed,
    )

    private suspend fun feedStill(seconds: Int) {
        repeat(seconds / 2) {
            clock += 2_000
            snapshots.emit(still.copy(time = clock))
            delay(2)
        }
    }

    private suspend fun waitFor(what: String, limit: Duration = 5.seconds, condition: () -> Boolean) {
        withTimeoutOrNull(limit) { while (!condition()) delay(10) }
        assertTrue(condition(), what)
    }

    private suspend fun fireAlarm() {
        clock = checkNotNull(alarm.at) { "闹钟没挂" }
        wake.hold(KeepAlive.BREATH_HOLD_LIMIT.inWholeMilliseconds) // 接收器先拿锁
        fired.emit(Unit)
    }

    /** 高 7：服务实例还在、已不是前台（电池「受限」降级）时，呼吸闹钟响了要重新拉成前台，并照常呼吸补一条 */
    @Test
    fun breathPromotesADemotedForegroundService(): Unit = runBlocking {
        val state = store()
        keepAlive(state, engine(state))
        feedStill(140)
        waitFor("转静止报一条") { uploaded.lastOrNull()?.state == "still" }

        service.demoted = true
        val before = uploaded.size
        fireAlarm()
        waitFor("降级了的前台服务重新拉成前台") { service.starts == 1 }
        assertTrue(!service.demoted)
        waitFor("拉回后这次呼吸照常补一条") { uploaded.size == before + 1 }
        waitFor("呼吸完放锁") { !wake.held }

        // 没降级时不重复拉
        fireAlarm()
        waitFor("下一次呼吸也报了") { uploaded.size == before + 2 }
        waitFor("放锁") { !wake.held }
        assertEquals(1, service.starts, "前台服务好好的就不再起")
    }

    /**
     * 中 6：没网、队列里已有点、发送循环在等联网时，呼吸产出的点入了库就算这次的活干完，马上放唤醒锁；
     * 修之前入队把「没活」改回 false，发送循环卡在等联网收不到唤醒，一直拿到唤醒锁上限
     */
    @Test
    fun offlineBreathReleasesTheWakeLockOnceThePointIsQueued(): Unit = runBlocking {
        online.value = false
        val state = store()
        keepAlive(state, engine(state), holdLimit = 30.seconds)
        feedStill(140)
        waitFor("没网时转静止那一条进了离线队列") { queued.any { it.state == "still" } }
        delay(200) // 发送循环看到队列有点、没网，开始等联网

        val before = queued.size
        fireAlarm()
        waitFor("呼吸这一条入了库") { queued.size == before + 1 }
        waitFor("入库就放唤醒锁，不等到上限（30 秒）") { !wake.held }
        assertTrue(uploaded.isEmpty(), "没网时一条也发不出去")

        // 联网后照常发完
        online.value = true
        waitFor("联网后离线队列发完") { queued.isEmpty() && uploaded.size == before + 1 }
    }

    /** 高 4：告警写库抛错（存储满）时只记一条存储异常，不落到线程默认处理把进程弄崩；低电、关定位、关机三个入口都用这个作用域 */
    @Test
    fun alertSaveFailureDoesNotCrashTheProcess(): Unit = runBlocking {
        val faults = Collections.synchronizedList(mutableListOf<Fault>())
        Log.faultSink = { _, kind, _ -> faults += kind }
        val uncaught = CompletableDeferred<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.complete(e) }
        try {
            val receiverScope = alertReceiverScope()
            repeat(3) {
                receiverScope.launch { throw IllegalStateException("database or disk is full (code 13 SQLITE_FULL)") }
            }
            waitFor("记成存储异常") { faults.size == 3 }
            assertTrue(faults.all { it == Fault.STORAGE })
            delay(200)
            assertTrue(!uncaught.isCompleted, "异常没落到线程默认处理（Android 上那就是进程崩溃）")
            // 作用域还能用：下一次广播照常记
            var ran = false
            receiverScope.launch { ran = true }.join()
            assertTrue(ran)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    /**
     * 审计 4：服务降级了，但一直在走路、每分钟报一条（没卫星只报时间），每条都把呼吸闹钟往后推，闹钟永远不响，30 分钟恢复 0 次。
     * 上报时隔 5 分钟看一眼前台服务，降级了就挂短闹钟走呼吸里拉起来的路：5 分钟内拉回前台
     */
    @Test
    fun demotedServiceIsPromotedWhileReportsKeepComing(): Unit = runBlocking {
        val swing = Snapshot(time = 0, accMean = 2.5, accStd = 6.0, accN = 50, gyroMean = 2.0)
        val state = store()
        keepAlive(state, engine(state), elapsed = { clock })
        var demotedAt: Long? = null
        var promotedAt: Long? = null
        repeat(900) { i ->
            clock += 2_000
            snapshots.emit(swing.copy(time = clock))
            delay(2)
            if (i == 30) {
                service.demoted = true
                demotedAt = clock
            }
            val at = alarm.at
            if (at != null && clock >= at) {
                wake.hold(KeepAlive.BREATH_HOLD_LIMIT.inWholeMilliseconds)
                fired.emit(Unit)
                waitFor("呼吸完放锁") { !wake.held }
                if (service.starts > 0 && promotedAt == null) promotedAt = clock
            }
        }
        assertTrue(uploaded.size >= 25, "走路 30 分钟一直在报：${uploaded.size}")
        val promoted = promotedAt
        assertTrue(promoted != null, "一直在上报，降级 30 分钟都没拉回前台")
        assertTrue(promoted - demotedAt!! <= 300_000 + KeepAlive.PROMOTE_AFTER + 2_000, "降级 ${(promoted - demotedAt!!) / 1000} 秒后才拉回")
        assertEquals(1, service.starts, "拉回一次就够了")
    }

    /**
     * 审计 9：联网但服务器一直失败，发送循环在退避（这里 10 分钟）。呼吸产出的点入了库，这次的活就干完了（试发一次、失败就放锁），
     * 不等到唤醒锁上限；修之前入队把「没活」改回 false，退避期间收不到唤醒
     */
    @Test
    fun breathDuringUploadBackoffReleasesTheWakeLock(): Unit = runBlocking {
        serverUp = false
        val state = store()
        keepAlive(state, engine(state, initialBackoff = 600.seconds), holdLimit = 30.seconds)
        feedStill(140)
        waitFor("转静止那一条进了离线队列") { queued.any { it.state == "still" } }
        delay(200) // 发送循环试发失败，进入退避

        val before = queued.size
        fireAlarm()
        waitFor("呼吸这一条入了库") { queued.size == before + 1 }
        waitFor("入库、试发失败就放唤醒锁，不等到上限（30 秒）") { !wake.held }
        assertTrue(uploaded.isEmpty())

        // 服务器好了：下一次呼吸带着把队列发完，不用等完 10 分钟退避
        serverUp = true
        fireAlarm()
        waitFor("服务器好了，呼吸时把队列发完") { queued.isEmpty() && uploaded.size == before + 2 }
    }
}
