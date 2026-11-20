package org.traccar.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.traccar.client.alert.Alarm
import org.traccar.client.alert.AlertOutbox
import org.traccar.client.alert.AlertStore
import org.traccar.client.alert.LowBatteryPolicy
import org.traccar.client.db.Database
import org.traccar.client.sense.Snapshot

/**
 * 故障注入（方案「可靠性要求」第 6 条）：没网、网时断时续、服务器 5xx、服务器超时、GPS 永远不回、高德报错、传感器不存在、
 * 飞行模式、时间被改、存储满、进程被杀、重启、低电，每种测完都要恢复上报。
 *
 * 引擎、判断层、状态库、离线队列（真 SQLite + [ResilientQueue]）、保活都用真的；服务器、网络、定位源、传感器、闹钟换成能注入故障的假实现。
 * 「不丢不重」的判法：引擎产出的每一个点（处理流程第一步记下）恢复后服务器都按顺序收到、只收到一次（[assertAllDelivered]）。
 */
class FaultInjectionTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
    private val scopes = mutableListOf<ComponentCoroutineScope>()
    private var clock = 1_800_000_000_000L
    /** 开机时长 = 系统时间 + 这个差；改系统时间时只动系统时间 */
    private var skew = -1_799_000_000_000L

    private val online = MutableStateFlow(true)
    private val snapshots = MutableSharedFlow<Snapshot>(extraBufferCapacity = 512)
    private val fixes = MutableSharedFlow<Position>(extraBufferCapacity = 512)
    /**
     * 呼吸闹钟的信号和真机（BreathAlarm）一样走 Channel：保活还没开始收时存着等它来取。用不带缓存的 SharedFlow 时，保活的作用域
     * 是多线程的，闹钟已经挂上、收信号的协程还没订上，这时发的信号就丢了（noSensors 时好时坏，main 上整类 6 次挂 3 次）
     */
    private val alarms = Channel<Unit>(Channel.UNLIMITED)
    private val fired = alarms.receiveAsFlow()
    private val produced = Collections.synchronizedList(mutableListOf<Position>())
    private val received = Collections.synchronizedList(mutableListOf<Position>())
    private val troubles = Collections.synchronizedList(mutableListOf<String>())
    private val faults = Collections.synchronizedList(mutableListOf<Fault>())

    private enum class Server { OK, ERROR_5XX, HANG }

    @Volatile private var server = Server.OK
    @Volatile private var battery = 80
    @Volatile private var networkFix: Position? = null
    @Volatile private var fetchHangs = false

    /** 服务器：没网时发不出去；5xx 时收不到（返回失败）；挂住时永远不回 */
    private val uploader = object : Uploader {
        override suspend fun upload(position: Position): Boolean {
            if (!online.value) return false
            return when (server) {
                Server.OK -> {
                    received += position
                    true
                }
                Server.ERROR_5XX -> false
                Server.HANG -> awaitCancellation()
            }
        }
    }

    private val alarm = object : WakeAlarm {
        @Volatile var at: Long? = null
        override fun scheduleIn(delayMillis: Long) {
            at = clock + delayMillis
        }
        override fun cancel() {
            at = null
        }
    }

    init {
        Log.faultSink = { _, kind, _ -> faults += kind }
    }

    @AfterTest
    fun tearDown() {
        Log.faultSink = null
        scopes.forEach { it.cancel() }
        driver.close()
    }

    /** 存储满的开关：库写不进、删不掉 */
    private class FlakyStorage(private val db: RowQueue) : RowQueue {
        @Volatile var full = false
        override suspend fun enqueue(position: Position) {
            if (full) throw IllegalStateException("SQLITE_FULL: database or disk is full")
            db.enqueue(position)
        }
        override suspend fun peekAfter(after: Long) = db.peekAfter(after)
        override suspend fun removeThrough(id: Long) {
            if (full) throw IllegalStateException("SQLITE_FULL: database or disk is full")
            db.removeThrough(id)
        }
    }

    private inner class Rig(
        val state: StateStore,
        val storage: FlakyStorage,
        val engine: TrackerEngine,
        val scope: ComponentCoroutineScope,
    )

    /** 起一套（进程被杀、重启时再起一套，读同一个库） */
    private suspend fun rig(
        source: LocationSource? = null,
        restore: Boolean = false,
    ): Rig {
        val scope = ComponentCoroutineScope().also { scopes += it }
        val state = if (restore) StateStore.create(driver) else StateStore(Database(driver).stateQueries, State(enabled = true, paused = true))
        if (!restore) state.update { it }
        val storage = FlakyStorage(DatabaseQueue(driver))
        val watch = StuckWatch("location fetch timed out") { troubles += it }
        val location = source ?: BoundedLocationSource(
            object : LocationSource {
                override val positions = fixes
                override suspend fun fetchOnce(): Position? = if (fetchHangs) awaitCancellation() else null
                override suspend fun fetchNetworkOnce(): Position? = if (fetchHangs) awaitCancellation() else networkFix
            },
            scope,
            "test",
            limit = 200.milliseconds,
            watch = watch,
        )
        val engine = TrackerEngine(
            stateStore = state,
            queue = ResilientQueue(storage),
            network = object : NetworkMonitor {
                override val isOnline = online
            },
            locationSource = location,
            sensors = object : SensorSource {
                override val snapshots = this@FaultInjectionTest.snapshots
                override fun setSleeping(sleeping: Boolean) = Unit
            },
            signalSources = emptyList(),
            processors = listOf(
                PositionProcessor { p -> p.copy(battery = battery).also { produced += it } },
            ),
            uploader = uploader,
            buffer = true,
            scope = scope,
            now = { clock },
            initialBackoff = 20.milliseconds,
            maxBackoff = 100.milliseconds,
            uploadLimit = 300.milliseconds,
            offlineRecheck = 500.milliseconds,
            elapsed = { clock + skew },
        )
        KeepAlive(
            scope = scope,
            state = state.state,
            engine = engine,
            alarm = alarm,
            service = object : ForegroundService {
                override val running = true
                override fun start() = Unit
            },
            wake = object : WakeHold {
                override fun hold(limitMillis: Long) = Unit
                override fun release() = Unit
            },
            fired = fired,
            onStuck = { troubles += it },
            ackLimit = 2.seconds,
            holdLimit = 3.seconds,
        )
        return Rig(state, storage, engine, scope)
    }

    private val still = Snapshot(time = 0, accMean = 0.4, accStd = 0.007, accN = 50, gyroMean = 0.001, stepsTotal = 1000)
    private val swing = Snapshot(time = 0, accMean = 2.5, accStd = 6.0, accN = 50, gyroMean = 2.0, stepsTotal = 1000)

    private fun gpsFix(t: Long) = Position(latitude = 30.27, longitude = 120.15, accuracy = 6.0, time = t - 500, speed = 1.4, provider = Provider.GPS)

    /** 喂 [seconds] 秒的快照（2 秒一份），在动时每份前来一个卫星点 */
    private suspend fun feed(seconds: Int, base: Snapshot, gps: Boolean = base === swing, pace: Long = 2) {
        repeat(seconds / 2) {
            clock += 2_000
            if (gps) fixes.emit(gpsFix(clock))
            snapshots.emit(base.copy(time = clock))
            delay(pace)
        }
    }

    private suspend fun fireAlarm() {
        clock = checkNotNull(alarm.at) { "闹钟没挂" }
        alarms.send(Unit)
    }

    private suspend fun waitFor(what: String, limit: kotlin.time.Duration = 10.seconds, condition: () -> Boolean) {
        withTimeoutOrNull(limit) { while (!condition()) delay(10) }
        assertTrue(condition(), what)
    }

    /** 不丢不重：产出的每一个点服务器都按顺序收到一次 */
    private suspend fun assertAllDelivered(what: String) {
        waitFor("$what：恢复后产出的点全部发出去（产出 ${produced.size}，收到 ${received.size}）") { received.size >= produced.size }
        delay(300)
        assertEquals(produced.toList(), received.toList(), "$what：不丢、不重、按顺序")
    }

    @Test
    fun noNetwork(): Unit = runBlocking {
        rig()
        online.value = false
        feed(60, swing)
        waitFor("没网时照样产出点、存进离线队列") { produced.size >= 5 }
        assertTrue(received.isEmpty())
        online.value = true
        feed(20, swing)
        assertAllDelivered("没网")
    }

    @Test
    fun flakyNetwork(): Unit = runBlocking {
        rig()
        repeat(12) { i ->
            online.value = i % 2 == 1
            feed(10, swing)
            delay(40)
        }
        online.value = true
        feed(10, swing)
        assertAllDelivered("网时断时续")
    }

    @Test
    fun server5xx(): Unit = runBlocking {
        rig()
        server = Server.ERROR_5XX
        feed(60, swing)
        delay(300)
        assertTrue(received.isEmpty(), "5xx 不算发出去")
        server = Server.OK
        feed(20, swing)
        assertAllDelivered("服务器 5xx")
    }

    @Test
    fun serverTimeout(): Unit = runBlocking {
        rig()
        server = Server.HANG
        feed(40, swing)
        delay(1_000)
        assertTrue(received.isEmpty())
        server = Server.OK
        feed(20, swing)
        assertAllDelivered("服务器不回（超时）")
    }

    @Test
    fun gpsNeverAnswers(): Unit = runBlocking {
        // 走着走着卫星一个点都不回、网络定位也挂住不回：照样每分钟报一条只带时间的；取点连续超时交给看门狗
        rig()
        fetchHangs = true
        // 慢一点喂：取点挂住要等护栏的 200 毫秒（真时间）
        feed(900, swing, gps = false, pace = 20)
        waitFor("卫星和网络定位都不回也按时报（只带时间）") { produced.count { it.latitude == null && it.state == "moving" } >= 6 }
        waitFor("取点连续超时三次交给看门狗") { troubles.any { it.startsWith("location fetch timed out") } }
        fetchHangs = false
        val before = produced.size
        feed(30, swing)
        waitFor("卫星恢复后报卫星点") { produced.drop(before).any { it.provider == Provider.GPS } }
        assertAllDelivered("GPS 永远不回")
    }

    @Test
    fun amapErrors(): Unit = runBlocking {
        // 高德报 Key 错（致命错误）：这一段追踪改用系统定位，屋里照样报网络定位
        val scope = ComponentCoroutineScope().also { scopes += it }
        val state = MutableStateFlow(State(enabled = true, paused = true))
        val systemFix = Position(latitude = 30.1, longitude = 120.1, accuracy = 30.0, time = clock, provider = Provider.NETWORK)
        val fallback = FallbackLocationSource(
            scope = scope,
            state = state,
            policy = ProviderFallbackPolicy(fatalErrors = setOf(AUTH_ERROR), maxConsecutiveErrors = 5),
            isOnline = online,
            primaryName = "AMap",
            fallbackName = "system location",
            createPrimary = { _, onResult ->
                object : LocationSource {
                    override val positions = MutableSharedFlow<Position>()
                    override suspend fun fetchOnce(): Position? = null.also { onResult(AUTH_ERROR) }
                    override suspend fun fetchNetworkOnce(): Position? = null.also { onResult(AUTH_ERROR) }
                }
            },
            createFallback = {
                object : LocationSource {
                    override val positions = fixes
                    override suspend fun fetchOnce(): Position? = systemFix
                    override suspend fun fetchNetworkOnce(): Position? = systemFix
                }
            },
        )
        rig(source = fallback)
        feed(20, swing.copy(accStd = 0.3, accMean = 0.4, gyroMean = 0.08))
        repeat(2) {
            clock += 301_000
            feed(4, swing.copy(accStd = 0.3, accMean = 0.4, gyroMean = 0.08))
        }
        waitFor("高德报错后用系统定位接着报") { produced.any { it.netSource == Provider.NETWORK } }
        assertTrue(Fault.DEGRADED in faults, "切系统定位记一条异常")
        assertAllDelivered("高德报错")
    }

    @Test
    fun noSensors(): Unit = runBlocking {
        // 一份快照都没有（没有加速度计）：第一次呼吸就按「在附近活动」报，之后照样 5 分钟一次
        networkFix = Position(latitude = 30.2, longitude = 120.2, accuracy = 40.0, time = clock, provider = Provider.CELL)
        rig()
        waitFor("挂上闹钟") { alarm.at != null }
        assertTrue(produced.isEmpty())
        fireAlarm()
        waitFor("没有传感器也报") { produced.any { it.state == "zone" } }
        fireAlarm()
        waitFor("5 分钟后接着报网络定位") { produced.count { it.netSource == Provider.CELL } >= 2 }
        assertAllDelivered("传感器不存在")
    }

    @Test
    fun airplaneMode(): Unit = runBlocking {
        val r = rig()
        feed(140, still)
        waitFor("静止") { produced.lastOrNull()?.state == "still" }
        val before = produced.size
        online.value = false
        feed(20, still.copy(airplane = true))
        repeat(3) { fireAlarm() }
        delay(300)
        assertEquals(before, produced.size, "飞行模式全停，不报")
        assertTrue(r.engine.quiet, "看门狗不当卡住")
        online.value = true
        feed(10, still.copy(airplane = false))
        waitFor("退出飞行模式马上报") { produced.size > before }
        assertAllDelivered("飞行模式")
    }

    @Test
    fun clockSetBack(): Unit = runBlocking {
        rig()
        feed(140, still)
        waitFor("静止") { produced.lastOrNull()?.state == "still" }
        fireAlarm()
        waitFor("呼吸") { produced.count { it.state == "still" } >= 2 }
        // 时间往回拨一天：闹钟按开机时长走、照样 5 分钟响，判断层照样呼吸
        clock -= 86_400_000
        skew += 86_400_000
        alarm.at = alarm.at!! - 86_400_000
        val before = produced.size
        repeat(3) {
            fireAlarm()
            waitFor("往回拨后第 ${it + 1} 次呼吸照样报") { produced.size == before + it + 1 }
        }
        assertAllDelivered("时间被改")
    }

    @Test
    fun storageFull(): Unit = runBlocking {
        val r = rig()
        feed(20, swing)
        waitFor("先正常报几条、都发完") { received.size >= 1 && received.size == produced.size }
        // 存储满了：库写不进、删不掉。点先放内存接着发，不丢；发出去删不掉的不重发
        r.storage.full = true
        feed(60, swing)
        waitFor("存储满时照样发出去") { received.size >= produced.size - 1 && produced.size >= 6 }
        assertTrue(Fault.STORAGE in faults, "写不进存储记一条异常")
        r.storage.full = false
        feed(20, swing)
        assertAllDelivered("存储满")
    }

    @Test
    fun storageFullWhileOffline(): Unit = runBlocking {
        val r = rig()
        online.value = false
        feed(20, swing)
        r.storage.full = true
        feed(40, swing)
        waitFor("没网又写不进库：点在内存里") { produced.size >= 6 }
        r.storage.full = false
        online.value = true
        feed(10, swing)
        assertAllDelivered("存储满又没网")
    }

    @Test
    fun processKilled(): Unit = runBlocking {
        val first = rig()
        online.value = false
        feed(60, swing)
        feed(140, still)
        waitFor("被杀前攒了一批点、转了静止") { produced.lastOrNull()?.state == "still" }
        delay(200)
        val beforeKill = produced.size
        // 进程被杀：这一套组件全没了，库还在
        first.scope.cancel()
        delay(100)
        online.value = true
        val second = rig(restore = true)
        assertTrue(second.state.state.value.enabled, "追踪开关从库里读回来")
        waitFor("被杀前攒下的点重启后发出去") { received.size >= produced.size }
        // 接着静止：闹钟到点就呼吸，不先报一条「在附近活动」
        fireAlarm()
        waitFor("重启后第一次呼吸就报") { produced.size > beforeKill && received.size == produced.size }
        assertEquals("still", produced.last().state, "接着静止")
        assertTrue(produced.drop(beforeKill).none { it.state == "zone" }, "重启后不先报一条在附近活动")
        assertAllDelivered("进程被杀")
    }

    @Test
    fun reboot(): Unit = runBlocking {
        val first = rig()
        feed(20, swing)
        online.value = false
        feed(40, swing)
        first.scope.cancel()
        // 重启：开机广播按库里的状态恢复，挂呼吸闹钟，第一份快照判出来就报
        alarm.at = null
        online.value = true
        clock += 60_000
        rig(restore = true)
        waitFor("开机后马上挂呼吸闹钟") { alarm.at != null }
        feed(10, swing)
        assertAllDelivered("重启")
    }

    @Test
    fun lowBattery(): Unit = runBlocking {
        val r = rig()
        val alerts = AlertOutbox(
            store = AlertStore(driver),
            uploader = uploader,
            processor = { it.copy(battery = battery) },
            isOnline = online,
            scope = r.scope,
            initialBackoff = 20.milliseconds,
        )
        val rule = LowBatteryPolicy()
        var reported = false
        feed(20, swing)
        // 电量掉到 5%：照样上报，低电告警报一次
        battery = 5
        repeat(3) {
            val d = rule.next(reported, battery, charging = false)
            if (d.report) alerts.raise(Alarm.LOW_BATTERY, "battery $battery%", battery = battery)
            reported = d.reported
            feed(20, swing)
        }
        waitFor("低电告警发出去一次") { received.count { it.alarm == Alarm.LOW_BATTERY } == 1 }
        val points = received.filter { it.alarm == null }
        assertTrue(points.takeLast(3).all { it.battery == 5 }, "低电时照样上报，带电量")
        received.removeAll { it.alarm != null }
        assertAllDelivered("低电")
    }

    @Test
    fun noReportForTenMinutesGoesToTheWatchdogButAirplaneModeDoesNot(): Unit = runBlocking {
        val scope = ComponentCoroutineScope().also { scopes += it }
        var time = 0L
        val engine = object : Breather {
            override val reports = MutableStateFlow(0L)
            @Volatile override var quiet = true
            override suspend fun breathe() = Unit
            override suspend fun awaitSettled() = Unit
        }
        val state = MutableStateFlow(State(enabled = true))
        KeepAlive(
            scope, state, engine, alarm,
            object : ForegroundService {
                override val running = true
                override fun start() = Unit
            },
            object : WakeHold {
                override fun hold(limitMillis: Long) = Unit
                override fun release() = Unit
            },
            fired, onStuck = { troubles += it }, checkEvery = 50.milliseconds, elapsed = { time },
        )
        time += 15.minutes.inWholeMilliseconds
        delay(300)
        assertTrue(troubles.isEmpty(), "飞行模式没有上报是应该的")
        engine.quiet = false
        time += 11.minutes.inWholeMilliseconds
        waitFor("十分钟多一条上报都没有：交给看门狗") { troubles.any { it.startsWith("no report for") } }
        delay(300)
        assertEquals(1, troubles.size, "两次之间至少隔一个呼吸周期")
    }

    private companion object {
        const val AUTH_ERROR = 7
    }
}
