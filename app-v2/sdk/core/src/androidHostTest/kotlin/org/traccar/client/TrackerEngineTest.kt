package org.traccar.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.traccar.client.alert.Alarm
import org.traccar.client.alert.AlertStore
import org.traccar.client.db.Database
import org.traccar.client.sense.Snapshot

/** 引擎按判断层开关卫星、上报带判断层字段、主坐标只放卫星点、丢掉热点的 Wi-Fi 定位、存 Wi-Fi 记忆；离线队列存得下判断层字段。 */
class TrackerEngineTest {

    private val scope = ComponentCoroutineScope()
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
    private val stateStore = StateStore(Database(driver).stateQueries, State(enabled = true, paused = true))
    private val uploaded = Collections.synchronizedList(mutableListOf<Position>())
    private val snapshots = MutableSharedFlow<Snapshot>(extraBufferCapacity = 256)
    private val fixes = MutableSharedFlow<Position>(extraBufferCapacity = 64)
    private val sleeping = MutableStateFlow<Boolean?>(null)
    private var clock = 1_800_000_000_000L

    @AfterTest
    fun tearDown() {
        scope.cancel()
        driver.close()
    }

    private fun engine(
        networkFix: Position? = null,
        source: LocationSource = object : LocationSource {
            override val positions = fixes
            override suspend fun fetchOnce(): Position? = null
            override suspend fun fetchNetworkOnce(): Position? = networkFix
        },
    ) = TrackerEngine(
        stateStore = stateStore,
        queue = object : PositionQueue {
            override suspend fun enqueue(position: Position) = error("unused")
            override suspend fun peek(): Position? = null
            override suspend fun removeFirst() = error("unused")
        },
        network = object : NetworkMonitor {
            override val isOnline = MutableStateFlow(true)
        },
        locationSource = source,
        sensors = object : SensorSource {
            override val snapshots = this@TrackerEngineTest.snapshots
            override fun setSleeping(sleeping: Boolean) {
                this@TrackerEngineTest.sleeping.value = sleeping
            }
        },
        signalSources = emptyList(),
        processors = emptyList(),
        uploader = object : Uploader {
            override suspend fun upload(position: Position): Boolean {
                uploaded += position
                return true
            }
        },
        buffer = false,
        scope = scope,
        now = { clock },
    )

    private val swing = Snapshot(time = 0, accMean = 2.5, accStd = 6.0, accN = 50, gyroMean = 2.0, stepsTotal = 1000)
    private val still = Snapshot(time = 0, accMean = 0.4, accStd = 0.007, accN = 50, gyroMean = 0.001, stepsTotal = 1000)

    /** 喂 [seconds] 秒的快照（2 秒一份），每份前可以先来一个定位点 */
    private suspend fun feed(seconds: Int, base: Snapshot, fix: ((Long) -> Position)? = null) {
        repeat(seconds / 2) {
            clock += 2_000
            fix?.let { fixes.emit(it(clock)) }
            snapshots.emit(base.copy(time = clock))
            delay(2)
        }
    }

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        withTimeoutOrNull(5.seconds) { while (!condition()) delay(10) }
        assertTrue(condition(), what)
    }

    private fun gpsFix(t: Long) = Position(latitude = 30.27, longitude = 120.15, accuracy = 6.0, time = t, speed = 1.4, provider = Provider.GPS)

    @Test
    fun walkingTurnsGpsOnAndStillTurnsItOff(): Unit = runBlocking {
        engine()
        feed(10, swing)
        waitFor("走路判出来就开卫星") { !stateStore.state.value.paused }
        assertEquals(false, sleeping.value)
        feed(60, swing.copy(stepsTotal = 1080)) { gpsFix(it - 500) }
        waitFor("在动时报卫星点") { uploaded.any { it.provider == Provider.GPS } }
        val walking = uploaded.filter { it.provider == Provider.GPS }
        assertTrue(walking.all { it.state == "moving" && it.motion == "walk" && it.net == "none" })
        assertEquals(80, walking.sumOf { it.steps ?: 0 }, "步数是离上一次上报走的")
        assertTrue(walking.all { (it.bumps ?: 0) in 1..12 })
        feed(140, still)
        waitFor("不动 2 分钟：静止，关卫星，传感器只留硬件唤醒") { stateStore.state.value.paused && sleeping.value == true }
        waitFor("转静止报一条") { uploaded.last().state == "still" }
        assertEquals("still", uploaded.last().motion)
    }

    @Test
    fun wifiLocationIsDroppedOnAHotspot(): Unit = runBlocking {
        engine()
        val hotspot = swing.copy(net = Snapshot.Net.WIFI, wifiBssid = "ae:12", wifiSsid = "小宝的iPhone", wifiMetered = false, wifiGateway = "172.20.10.1")
        feed(10, hotspot)
        waitFor("连着热点走路照样开卫星") { !stateStore.state.value.paused }
        feed(60, hotspot) { Position(latitude = 30.0, longitude = 120.0, accuracy = 80.0, time = it - 500, provider = Provider.WIFI) }
        delay(200)
        assertTrue(uploaded.none { it.netSource == Provider.WIFI || it.provider == Provider.WIFI }, "高德按热点 MAC 报的 Wi-Fi 定位不用")
        assertTrue(uploaded.all { it.net == "hotspot" })
    }

    @Test
    fun wifiAtTheLastSatelliteFixIsSaved(): Unit = runBlocking {
        engine()
        val home = swing.copy(net = Snapshot.Net.WIFI, wifiBssid = "3c:8c", wifiSsid = "HOME-5G", wifiMetered = false, wifiRssi = -50)
        feed(10, home)
        waitFor("卫星没定位过的 Wi-Fi：走动照样开卫星") { !stateStore.state.value.paused }
        feed(24, home) { gpsFix(it - 500) }
        waitFor("卫星定位时连的 Wi-Fi 存进状态库") { stateStore.state.value.wifi.anchor == "3c:8c" }
        waitFor("报上去的点说还连着那个 Wi-Fi") { uploaded.any { it.wifiSame == true } }
    }

    @Test
    fun zoneReportsTheNetworkFixWithItsProvider(): Unit = runBlocking {
        engine(networkFix = Position(latitude = 30.0, longitude = 120.0, accuracy = 40.0, time = clock, provider = Provider.CELL))
        feed(10, swing.copy(accStd = 0.3, accMean = 0.4, gyroMean = 0.08))
        waitFor("屋里：报状态、再报取到的网络定位") { uploaded.size >= 2 }
        assertNull(uploaded[0].latitude)
        // 网络定位不放主坐标（服务器沿用上一个卫星位置），估算放附加字段
        assertNull(uploaded[1].latitude)
        assertNull(uploaded[1].provider)
        assertEquals(Provider.CELL, uploaded[1].netSource)
        assertEquals(30.0, uploaded[1].netLat)
        assertEquals(40.0, uploaded[1].netAcc)
        assertEquals(listOf("zone", "zone"), uploaded.take(2).map { it.state })
        assertFalse(uploaded.any { it.provider == Provider.GPS })
    }

    @Test
    fun confirmedNetworkEstimateBecomesTheMainCoordinateAndIsSaved(): Unit = runBlocking {
        // 屋里定不到卫星、从没有过主坐标（全新装）：隔 60 秒的两次估算一致就当主坐标报，来源 network，上一个主坐标存进状态库（重启接着比）
        engine(networkFix = Position(latitude = 30.0, longitude = 120.0, accuracy = 40.0, time = clock, provider = Provider.WIFI))
        val sit = still.copy(accStd = 0.02, gyroMean = 0.03, net = Snapshot.Net.WIFI, wifiBssid = "3c:8c", wifiSsid = "HOME-5G", wifiMetered = false)
        feed(90, sit)
        waitFor("估算当主坐标报") { uploaded.any { it.provider == "network" } }
        val main = uploaded.first { it.provider == "network" }
        assertEquals(30.0, main.latitude)
        assertEquals(40.0, main.accuracy)
        assertEquals(Provider.WIFI, main.netSource)
        assertTrue(uploaded.takeWhile { it !== main }.all { it.latitude == null })
        waitFor("上一个主坐标存进状态库") { stateStore.state.value.main?.gps == false }
        assertEquals(30.0, stateStore.state.value.main?.latitude)
        waitFor("这个 Wi-Fi 记成临时锚点") { stateStore.state.value.wifi.anchorTemp }
    }

    @Test
    fun queueKeepsJudgementFields(): Unit = runBlocking {
        val queue = DatabaseQueue(driver)
        val position = Position(
            latitude = 30.0, longitude = 120.0, time = 5L, battery = 80,
            provider = Provider.GPS, state = "zone", motion = "fidget", steps = 12, bumps = 4, net = "hotspot",
            netLat = 30.1, netLon = 120.1, netAcc = 80.0, netSource = Provider.CELL, wifiSame = false,
            // 开机后还没解锁时攒下的点：断网存进队列，联网补发时也要带着
            locked = true,
        )
        queue.enqueue(position)
        queue.enqueue(Position(time = 6L))
        assertEquals(position, queue.peek())
        queue.removeFirst()
        assertEquals(Position(time = 6L), queue.peek())
    }

    @Test
    fun queueMigratesFromVersion3(): Unit = runBlocking {
        // 原版（第 3 版）的库：离线队列里还有一个点，升到最新版（第 5 版：R5 加了告警表）后照样读出来
        val old = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            old.execute(
                null,
                "CREATE TABLE Position (id INTEGER PRIMARY KEY AUTOINCREMENT, latitude REAL, longitude REAL, accuracy REAL, " +
                    "time INTEGER NOT NULL, altitude REAL, speed REAL, bearing REAL, battery INTEGER, charging INTEGER)",
                0,
            )
            old.execute(null, "INSERT INTO Position(latitude, longitude, time) VALUES (30.0, 120.0, 7)", 0)
            assertEquals(5L, Database.Schema.version)
            Database.Schema.migrate(old, 3L, Database.Schema.version)
            assertEquals(Position(latitude = 30.0, longitude = 120.0, time = 7), DatabaseQueue(old).peek())
            // 升上来的库能存告警
            val alerts = AlertStore(old)
            alerts.insert(Position(time = 8, alarm = Alarm.SOS), ready = true)
            assertEquals(listOf(Alarm.SOS), alerts.pending().map { it.alarm })
        } finally {
            old.close()
        }
    }

    @Test
    fun rebuildingTheLocationSourceDuringANetworkFetchDoesNotStopReports(): Unit = runBlocking {
        // 看门狗第一级只重建定位源：旧作用域取消，引擎正在等的网络取点跟着被取消。引擎不能跟着静默结束这次取点，
        // 要当「没取到」交回判断层，不然判断层一直以为还在取点，之后屋里一条都不报
        var calls = 0
        val source = RebuildableLocationSource(scope) { built ->
            BoundedLocationSource(
                object : LocationSource {
                    override val positions = fixes
                    override suspend fun fetchOnce(): Position? = null
                    override suspend fun fetchNetworkOnce(): Position? = if (++calls == 1) awaitCancellation() else null
                },
                built,
                "test",
                limit = 5.seconds,
            )
        }
        val engine = engine(source = source)
        engine.breathe()
        waitFor("没有传感器：第一次呼吸按屋里报一条、开始取网络定位") { uploaded.size == 1 && calls == 1 }
        source.rebuild()
        repeat(3) {
            clock += 300_001
            engine.breathe()
            delay(50)
        }
        waitFor("重建后照常每 5 分钟报") { uploaded.size >= 4 }
        assertTrue(uploaded.all { it.state == "zone" })
    }
}
