package org.traccar.client

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.traccar.client.alert.AlertOutbox
import org.traccar.client.alert.AlertStore
import org.traccar.client.db.Database
import org.traccar.client.sense.Snapshot

/**
 * 装机版 11.0.11 审计（docs/audits/2026-11-19-codex-install-311.md）引擎、告警、离线队列几条：
 * 1 求救和告警只带能当主坐标的卫星点、4 队列满时插入和裁剪一个事务、8 服务器持续失败时新点不打断退避。用真 SQLite（JVM 内存库）。
 */
class AuditFix311Test {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clock = 1_800_000_000_000L

    @AfterTest
    fun tearDown() {
        scope.cancel()
        QueueMemory.process.points.clear()
        driver.close()
    }

    private fun gps(ageMillis: Long, accuracy: Double, north: Double = 0.0, speed: Double = 0.0) = Position(
        latitude = 30.0 + north / 111_195.0, longitude = 120.0, accuracy = accuracy, time = clock - ageMillis, speed = speed,
        provider = Provider.GPS,
    )

    private fun uploader(answer: (Position) -> Boolean) = object : Uploader {
        override suspend fun upload(position: Position): Boolean = answer(position)
    }

    private fun outbox(latest: () -> Position?) = AlertOutbox(
        store = AlertStore(driver),
        uploader = uploader { false },
        processor = { it },
        isOnline = MutableStateFlow(false),
        scope = scope,
        latestGps = latest,
        now = { clock },
    )

    private suspend fun stored(outbox: AlertOutbox, id: Long): Position =
        assertNotNull(AlertStore(driver).get(id)).position.also { assertTrue(!outbox.isSent(id)) }

    @Test
    fun sosDoesNotCarryASatellitePointWithAFiveKilometreError(): Unit = runBlocking {
        // 1：引擎手里刚收到一个标称误差 5 公里的卫星点（普通上报已丢掉）。求救不能把它当主坐标，要接着现取；现取的也不合格就只报时间
        val bad = gps(ageMillis = 2_000, accuracy = 5_000.0)
        val outbox = outbox { bad }
        val located = AtomicInteger()
        val first = outbox.sos {
            located.incrementAndGet()
            gps(ageMillis = 0, accuracy = 4_000.0)
        }
        assertEquals(1, located.get(), "缓存不合格，求救照样现取")
        assertNull(stored(outbox, first).latitude, "现取的也不合格：只报时间")

        // 这条算发出去了（不然再按求救是重发这一条），再求救一次
        AlertStore(driver).markSent(first, clock)
        val second = outbox.sos { gps(ageMillis = 0, accuracy = 6.0) }
        assertEquals(30.0, stored(outbox, second).latitude, "现取到合格的卫星点就带上")

        val raised = outbox.raise("lowBattery", "battery 5%", battery = 5)
        assertNull(stored(outbox, raised).latitude, "低电这类告警也不带误差 5 公里的点")
    }

    @Test
    fun theEngineKeepsTheLastGoodSatellitePointForAlerts(): Unit = runBlocking {
        // 1：告警缓存（latestGps）只收能当主坐标的点：屋里误差几公里的点不顶掉刚才那个好点
        val engine = engine(uploader = uploader { true })
        engine.onFix(gps(ageMillis = 0, accuracy = 6.0, north = 10.0))
        val good = engine.latestGps
        assertNotNull(good)
        clock += 2_000
        engine.onFix(gps(ageMillis = 0, accuracy = 5_000.0, north = 3_000.0))
        assertEquals(good, engine.latestGps)
    }

    private fun failDeletes() = driver.execute(
        null,
        "CREATE TRIGGER full_delete BEFORE DELETE ON Position BEGIN SELECT RAISE(ABORT, 'database or disk is full'); END",
        0,
    )

    @Test
    fun aFullQueueThatCannotTrimDoesNotStoreThePointTwice(): Unit = runBlocking {
        // 4：上限缩成 3，只让删除失败，依次放 5 个点；之后服务器一直收，最多模拟 20 次发送：每个点各发一次，库不涨
        val queue = ResilientQueue(DatabaseQueue(driver, keep = 3), QueueMemory())
        failDeletes()
        for (t in 1L..5L) queue.enqueue(Position(time = t))
        val sent = mutableListOf<Long>()
        repeat(20) {
            val p = queue.peek() ?: return@repeat
            sent += p.time
            queue.removeFirst()
        }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), sent)
        val rows = driver.executeQuery(null, "SELECT COUNT(*) FROM Position", { c ->
            c.next()
            QueryResult.Value(c.getLong(0))
        }, 0).value
        assertEquals(3L, rows, "库里只有删不掉的那 3 条，没有重复插进去的")
    }

    private val smooth = Snapshot(time = 0, accMean = 0.4, accStd = 0.1, accN = 50, gyroMean = 0.05)

    private fun engine(uploader: Uploader, queue: PositionQueue = ListQueue()) = TrackerEngine(
        stateStore = StateStore(Database(driver).stateQueries, State(enabled = true, paused = true)),
        queue = queue,
        network = object : NetworkMonitor {
            override val isOnline = MutableStateFlow(true)
        },
        locationSource = object : LocationSource {
            override val positions = emptyFlow<Position>()
            override suspend fun fetchOnce(): Position? = null
            override suspend fun fetchNetworkOnce(): Position? = null
        },
        sensors = object : SensorSource {
            override val snapshots = emptyFlow<Snapshot>()
            override fun setSleeping(sleeping: Boolean) = Unit
        },
        signalSources = emptyList(),
        processors = emptyList(),
        uploader = uploader,
        buffer = true,
        scope = scope,
        now = { clock },
        initialBackoff = 5.minutes,
        maxBackoff = 5.minutes,
    )

    private class ListQueue : PositionQueue {
        val points: MutableList<Position> = Collections.synchronizedList(mutableListOf())
        override suspend fun enqueue(position: Position) {
            points += position
        }
        override suspend fun peek(): Position? = points.firstOrNull()
        override suspend fun removeFirst() {
            points.removeAt(0)
        }
    }

    @Test
    fun movingPointsDoNotCutTheServerBackoffShort(): Unit = runBlocking {
        // 8：联网、服务器一直失败（退避 5 分钟），行车每 10 秒一个点。以前每入库一个点就试发一次同一个队首（60 个间隔试了 61 次）；
        // 现在只在退避到期时试，入库照样马上算「没活」放锁
        val attempts = Collections.synchronizedList(mutableListOf<Long>())
        val queue = ListQueue()
        val engine = engine(uploader = uploader { p -> attempts += p.time; false }, queue = queue)
        var north = 0.0
        suspend fun drive() {
            clock += 10_000
            north += 120.0
            val fix = gps(ageMillis = 500, accuracy = 8.0, north = north, speed = 12.0)
            engine.onSnapshot(smooth.copy(time = clock, fix = Snapshot.Fix(Provider.GPS, fix.time, fix.latitude, fix.longitude, 8.0, 12.0)))
            engine.onFix(fix)
            delay(20)
        }
        drive()
        withTimeoutOrNull(2.seconds) { while (attempts.isEmpty()) delay(10) }
        assertEquals(1, attempts.size, "转在路上那一条马上试发一次")
        repeat(60) { drive() }
        assertTrue(queue.points.size >= 50, "一直在报：${queue.points.size}")
        // 10 分钟里退避（5 分钟）到期两次
        assertTrue(attempts.size in 2..4, "60 个间隔试发了 ${attempts.size} 次")
        assertTrue(attempts.all { it == attempts.first() }, "试的都是队首")
        // 退避中入库照常放锁（呼吸等的是这个）
        drive()
        assertNotNull(withTimeoutOrNull(1.seconds) { engine.awaitSettled() }, "退避中入库后没放锁")
    }
}
