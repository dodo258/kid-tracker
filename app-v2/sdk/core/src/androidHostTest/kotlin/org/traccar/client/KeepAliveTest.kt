package org.traccar.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.traccar.client.db.Database
import org.traccar.client.policy.ReportPolicy
import org.traccar.client.sense.Snapshot

/**
 * 保活（R4）：呼吸闹钟到点叫醒判断层呼吸、开机 / 更新后按存着的状态自启、取点或呼吸连续卡住就重建跟踪。
 * 闹钟、前台服务、唤醒锁换成记录调用的假实现，引擎、判断层、状态库用真的。
 */
class KeepAliveTest {

    private val scope = ComponentCoroutineScope()
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
    private var clock = 1_800_000_000_000L
    private val uploaded = Collections.synchronizedList(mutableListOf<Position>())
    private val snapshots = MutableSharedFlow<Snapshot>(extraBufferCapacity = 256)
    private val fired = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private val rebuilds = Collections.synchronizedList(mutableListOf<String>())
    /** 上传卡在这里，测「活干完才放唤醒锁」 */
    private var uploadGate: CompletableDeferred<Unit>? = null

    private val alarm = object : WakeAlarm {
        @Volatile var at: Long? = null
        @Volatile var lastDelay: Long? = null
        override fun scheduleIn(delayMillis: Long) {
            lastDelay = delayMillis
            at = clock + delayMillis
        }
        override fun cancel() {
            at = null
        }
    }

    private val service = object : ForegroundService {
        @Volatile override var running = true
        @Volatile var starts = 0
        override fun start() {
            starts++
            running = true
        }
    }

    private val wake = object : WakeHold {
        @Volatile var held = false
        @Volatile var limit = 0L
        override fun hold(limitMillis: Long) {
            held = true
            limit = limitMillis
        }
        override fun release() {
            held = false
        }
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        driver.close()
    }

    private fun engine(stateStore: StateStore) = TrackerEngine(
        stateStore = stateStore,
        queue = object : PositionQueue {
            override suspend fun enqueue(position: Position) = error("unused")
            override suspend fun peek(): Position? = null
            override suspend fun removeFirst() = error("unused")
        },
        network = object : NetworkMonitor {
            override val isOnline = MutableStateFlow(true)
        },
        locationSource = object : LocationSource {
            override val positions = emptyFlow<Position>()
            override suspend fun fetchOnce(): Position? = null
            override suspend fun fetchNetworkOnce(): Position? = null
        },
        sensors = object : SensorSource {
            override val snapshots = this@KeepAliveTest.snapshots
            override fun setSleeping(sleeping: Boolean) = Unit
        },
        signalSources = emptyList(),
        processors = emptyList(),
        uploader = object : Uploader {
            override suspend fun upload(position: Position): Boolean {
                uploadGate?.await()
                uploaded += position
                return true
            }
        },
        buffer = false,
        scope = scope,
        now = { clock },
    )

    private fun keepAlive(state: StateStore, engine: Breather, ackLimit: kotlin.time.Duration = 2.seconds) = KeepAlive(
        scope = scope,
        state = state.state,
        engine = engine,
        alarm = alarm,
        service = service,
        wake = wake,
        fired = fired,
        onStuck = { rebuilds += it },
        ackLimit = ackLimit,
        holdLimit = 5.seconds,
    )

    private val still = Snapshot(time = 0, accMean = 0.4, accStd = 0.007, accN = 50, gyroMean = 0.001, stepsTotal = 1000)
    private val fidget = Snapshot(time = 0, accMean = 0.4, accStd = 0.3, accN = 50, gyroMean = 0.08, stepsTotal = 1000)

    private suspend fun feed(seconds: Int, base: Snapshot) {
        repeat(seconds / 2) {
            clock += 2_000
            snapshots.emit(base.copy(time = clock))
            delay(2)
        }
    }

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        withTimeoutOrNull(5.seconds) { while (!condition()) delay(10) }
        assertTrue(condition(), what)
    }

    /** 闹钟到点：把时钟拨到闹钟挂的时间、让它响 */
    private suspend fun fireAlarm() {
        clock = checkNotNull(alarm.at) { "闹钟没挂" }
        wake.hold(KeepAlive.BREATH_HOLD_LIMIT.inWholeMilliseconds) // 接收器先拿锁
        fired.emit(Unit)
    }

    private fun store(enabled: Boolean = true) = StateStore(Database(driver).stateQueries, State(enabled = enabled, paused = true))

    @Test
    fun breathAlarmComesDueEveryFiveMinutesWhileStill(): Unit = runBlocking {
        val state = store()
        keepAlive(state, engine(state))
        waitFor("追踪开着就挂呼吸闹钟") { alarm.at != null }
        // 屋里走动一会儿再放下：状态从「在附近活动」转「静止」时卫星一直关着，闹钟要按最后一条上报往后推，不然会早于判断层的到点、白响一次
        feed(20, fidget)
        waitFor("屋里走动报一条") { uploaded.any { it.state == "zone" } }
        feed(140, still)
        waitFor("转静止报一条") { uploaded.lastOrNull()?.state == "still" }
        val stillAt = uploaded.last().time
        waitFor("闹钟不早于判断层的呼吸到点（转静止那一条后 5 分钟）") { alarm.at!! >= stillAt + ReportPolicy.BREATH_EVERY }
        assertTrue(alarm.at!! <= clock + ReportPolicy.BREATH_EVERY + KeepAlive.ALARM_MARGIN, "也不晚：5 分钟一次")

        repeat(3) { round ->
            val before = uploaded.size
            fireAlarm()
            waitFor("第 ${round + 1} 次闹钟到点就呼吸一次（只报时间）") { uploaded.size == before + 1 }
            val breath = uploaded.last()
            assertEquals("still", breath.state)
            assertNull(breath.latitude)
            waitFor("呼吸报完放唤醒锁") { !wake.held }
            assertEquals(clock + ReportPolicy.BREATH_EVERY + KeepAlive.ALARM_MARGIN, alarm.at, "下一次 5 分钟后")
        }
        assertTrue(rebuilds.isEmpty())
    }

    @Test
    fun wakeLockIsHeldUntilTheBreathIsUploaded(): Unit = runBlocking {
        val state = store()
        keepAlive(state, engine(state))
        feed(140, still)
        waitFor("转静止报一条") { uploaded.lastOrNull()?.state == "still" }
        val gate = CompletableDeferred<Unit>().also { uploadGate = it }
        val before = uploaded.size
        fireAlarm()
        delay(300)
        assertTrue(wake.held, "上传还没做完不放唤醒锁")
        assertTrue(wake.limit <= KeepAlive.BREATH_HOLD_LIMIT.inWholeMilliseconds, "唤醒锁带上限")
        gate.complete(Unit)
        waitFor("呼吸上传了") { uploaded.size == before + 1 }
        waitFor("做完就放") { !wake.held }
    }

    @Test
    fun alarmFollowsTheLastReportWhileMovingSoItOnlyFiresWhenReportsStop(): Unit = runBlocking {
        val state = store()
        val engine = engine(state)
        keepAlive(state, engine)
        waitFor("挂上") { alarm.at != null }
        val first = alarm.at!!
        feed(20, fidget)
        waitFor("报了一条") { engine.reports.value > 0 }
        waitFor("每条上报都把闹钟往后推") { alarm.at!! > first }
    }

    @Test
    fun breathNotHandledThreeTimesInARowRebuildsTracking(): Unit = runBlocking {
        val state = store()
        val stuckEngine = object : Breather {
            override val reports = MutableStateFlow(0L)
            var hang = true
            var breaths = 0
            override suspend fun breathe() {
                breaths++
                if (hang) awaitCancellation()
            }
            override suspend fun awaitSettled() = Unit
        }
        keepAlive(state, stuckEngine, ackLimit = 100.milliseconds)
        waitFor("挂上") { alarm.at != null }

        repeat(2) {
            fireAlarm()
            waitFor("引擎没接住：1 分钟后再来看") { alarm.lastDelay == KeepAlive.BREATH_RETRY }
            alarm.lastDelay = null
        }
        assertTrue(rebuilds.isEmpty(), "两次还不重建")
        // 中间有一次接住了就重新数
        stuckEngine.hang = false
        fireAlarm()
        waitFor("接住了") { stuckEngine.breaths == 3 && alarm.lastDelay == ReportPolicy.BREATH_EVERY + KeepAlive.ALARM_MARGIN }
        stuckEngine.hang = true
        repeat(3) {
            fireAlarm()
            waitFor("没接住") { alarm.lastDelay == KeepAlive.BREATH_RETRY }
            alarm.lastDelay = null
        }
        // 闹钟先改挂 1 分钟后、再数这一次：等它数完
        waitFor("连着三次没接住交给看门狗") { rebuilds.isNotEmpty() }
        assertEquals(listOf("breath not handled $STUCK_LIMIT times in a row"), rebuilds.toList(), "连着三次没接住交给看门狗一次")
        assertTrue(wake.held, "要重建了不放唤醒锁（带上限，到点系统放）")
    }

    @Test
    fun locationFetchTimingOutThreeTimesInARowRebuildsTracking(): Unit = runBlocking {
        var hang = true
        val watch = StuckWatch("location fetch timed out") { rebuilds += it }
        val source = BoundedLocationSource(
            object : LocationSource {
                override val positions = emptyFlow<Position>()
                override suspend fun fetchOnce(): Position? = if (hang) awaitCancellation() else null
            },
            scope,
            "test",
            limit = 100.milliseconds,
            watch = watch,
        )
        repeat(2) { assertNull(source.fetchOnce()) }
        hang = false
        assertNull(source.fetchOnce(), "按时回来（没拿到点也算）就重新数")
        hang = true
        repeat(2) { assertNull(source.fetchOnce()) }
        assertTrue(rebuilds.isEmpty())
        assertNull(source.fetchNetworkOnce())
        assertEquals(listOf("location fetch timed out $STUCK_LIMIT times in a row"), rebuilds)
    }

    @Test
    fun trackingResumesAfterRestartFromTheSavedState(): Unit = runBlocking {
        // 上一个进程：开着追踪，存进库里
        StateStore(Database(driver).stateQueries, State()).update { it.copy(enabled = true) }
        // 开机 / 更新后新进程：从库里读状态，按它恢复
        service.running = false
        val restored = StateStore.create(driver)
        assertTrue(restored.state.value.enabled, "追踪开关从库里读回来")
        keepAlive(restored, engine(restored))
        waitFor("恢复后马上挂呼吸闹钟") { alarm.at == clock + ReportPolicy.BREATH_EVERY + KeepAlive.ALARM_MARGIN }
        feed(10, still)
        waitFor("恢复后第一份快照判出来就报一条") { uploaded.isNotEmpty() }
        // 系统把服务停了（或进程是被闹钟叫起来的）：闹钟响时拉起来
        fireAlarm()
        waitFor("闹钟响时前台服务不在就拉起来") { service.starts == 1 }
        waitFor("呼吸完放锁") { !wake.held }
    }

    @Test
    fun trackingOffCancelsTheAlarmButRebuildingDoesNot(): Unit = runBlocking {
        val state = store()
        keepAlive(state, engine(state))
        waitFor("挂上") { alarm.at != null }
        // 重建 / 改设置：作用域取消，闹钟留着（新的一套会重挂；进程要死了闹钟正好把它叫回来）
        val other = ComponentCoroutineScope()
        KeepAlive(other, state.state, engine(state), alarm, service, wake, fired, onStuck = {})
        delay(100)
        other.cancel()
        delay(100)
        assertTrue(alarm.at != null, "组件作用域取消不撤闹钟")
        state.update { it.copy(enabled = false) }
        waitFor("主人关追踪：撤闹钟") { alarm.at == null }
    }

    @Test
    fun alarmAfterTrackingWasTurnedOffDoesNothing(): Unit = runBlocking {
        val state = store(enabled = false)
        var breaths = 0
        keepAlive(state, object : Breather {
            override val reports = MutableStateFlow(0L)
            override suspend fun breathe() {
                breaths++
            }
            override suspend fun awaitSettled() = Unit
        })
        delay(100)
        assertNull(alarm.at, "追踪关着不挂")
        alarm.at = clock
        service.running = false
        fireAlarm()
        waitFor("撤掉、放锁") { alarm.at == null && !wake.held }
        assertEquals(0, breaths)
        assertEquals(0, service.starts)
        assertFalse(service.running)
    }
}
