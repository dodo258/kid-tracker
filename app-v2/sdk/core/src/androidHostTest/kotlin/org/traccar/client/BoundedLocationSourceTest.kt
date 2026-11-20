package org.traccar.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.traccar.client.db.Database
import org.traccar.client.sense.Snapshot

/**
 * 单次取点永远不返回时不能拖住调用方（P20 2026-11-18：Fused 单次定位被取消后永不回调，引擎拿着锁卡了一整夜）。
 * 护栏到点放弃那次取点、当没拿到点；定位源理取消的要真的被取消（停掉定位请求），不理取消的也不挡下一次取点。
 */
class BoundedLocationSourceTest {

    private val scope = ComponentCoroutineScope()
    private val limit = 300.milliseconds
    private val fix = Position(latitude = 30.2737, longitude = 120.1373, accuracy = 20.0, time = 1_800_000_000_000L)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun source(fetch: suspend () -> Position?) = object : LocationSource {
        override val positions: Flow<Position> = emptyFlow()
        override suspend fun fetchOnce(): Position? = fetch()
    }

    /** 不理取消、永远不返回的取点（被取消后不回调的系统接口就是这样） */
    private val hangs = source { withContext(NonCancellable) { CompletableDeferred<Position?>().await() } }

    @Test
    fun fetchThatNeverReturnsGivesNoFixInTime(): Unit = runBlocking {
        val bounded = BoundedLocationSource(hangs, scope, "test", limit)
        val result = withTimeout(5.seconds) { bounded.fetchOnce() }
        assertNull(result)
    }

    @Test
    fun hungFetchDoesNotBlockTheNextOne(): Unit = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val bounded = BoundedLocationSource(
            source {
                if (firstStarted.complete(Unit)) withContext(NonCancellable) { awaitCancellation() } else fix
            },
            scope,
            "test",
            limit,
        )
        val first = async { bounded.fetchOnce() }
        withTimeout(5.seconds) { firstStarted.await() }
        // 第一次还挂着时第二次照常拿到点：不共用锁
        assertEquals(fix, withTimeout(5.seconds) { bounded.fetchOnce() })
        assertNull(withTimeout(5.seconds) { first.await() })
    }

    @Test
    fun timedOutFetchIsCancelled(): Unit = runBlocking {
        val released = CompletableDeferred<Unit>()
        val bounded = BoundedLocationSource(
            source {
                try {
                    awaitCancellation()
                } finally {
                    released.complete(Unit)
                }
            },
            scope,
            "test",
            limit,
        )
        assertNull(withTimeout(5.seconds) { bounded.fetchOnce() })
        // 定位源收到取消，停掉自己的请求（真实定位源在这里注销监听、销毁客户端）
        withTimeout(5.seconds) { released.await() }
    }

    @Test
    fun fixAndFailurePassThrough(): Unit = runBlocking {
        assertEquals(fix, BoundedLocationSource(source { fix }, scope, "test", limit).fetchOnce())
        assertNull(BoundedLocationSource(source { null }, scope, "test", limit).fetchOnce())
        assertNull(BoundedLocationSource(source { throw SecurityException("no permission") }, scope, "test", limit).fetchOnce())
    }

    /** 引擎：屋里走动时取网络定位永远不返回，也要在时限内报一个只带时间的点，并且放锁，后面的快照照常处理。 */
    @Test
    fun engineZoneFetchWithHungSourceReportsWithoutCoordinates(): Unit = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
        try {
            val uploaded = Collections.synchronizedList(mutableListOf<Position>())
            val snapshots = MutableSharedFlow<Snapshot>(extraBufferCapacity = 64)
            TrackerEngine(
                stateStore = StateStore(Database(driver).stateQueries, State(enabled = true, paused = true)),
                queue = object : PositionQueue {
                    override suspend fun enqueue(position: Position) = error("unused")
                    override suspend fun peek(): Position? = null
                    override suspend fun removeFirst() = error("unused")
                },
                network = object : NetworkMonitor {
                    override val isOnline = MutableStateFlow(true)
                },
                locationSource = BoundedLocationSource(hangs, scope, "test", limit),
                sensors = object : SensorSource {
                    override val snapshots = snapshots
                    override fun setSleeping(sleeping: Boolean) = Unit
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
            )
            // 拿着手机晃（有动静、没走路）：判成屋里走动，马上报状态、再取一次网络定位（卡住）→ 到时限报只带时间的点
            val start = System.currentTimeMillis()
            scope.launch {
                for (i in 1..3) snapshots.emit(Snapshot(time = start + i * 2_000L, accMean = 0.4, accStd = 0.3, accN = 50, gyroMean = 0.08))
            }
            withTimeoutOrNull(5.seconds) { while (uploaded.size < 2) delay(20) }
            assertEquals(2, uploaded.size, "state report and zone report within 5 s")
            assertTrue(uploaded.all { it.latitude == null && it.longitude == null && it.state == "zone" })
        } finally {
            driver.close()
        }
    }
}
