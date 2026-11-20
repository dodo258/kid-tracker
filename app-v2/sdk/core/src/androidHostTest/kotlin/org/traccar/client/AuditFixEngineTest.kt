package org.traccar.client

import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.traccar.client.sense.Snapshot

/** 审计第二批的引擎、定位源、网络几条：高 6、高 11、中 5 */
class AuditFixEngineTest {

    private val scope = ComponentCoroutineScope()

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun source(fetch: suspend () -> Position?) = object : LocationSource {
        override val positions: Flow<Position> = emptyFlow()
        override suspend fun fetchOnce(): Position? = fetch()
    }

    @Test
    fun aSourceThatNeverCallsBackWithinItsOwnTimeoutCountsAsStuck(): Unit = runBlocking {
        // 高 6：真实定位源自己 30 秒没回调就返回，外层 40 秒护栏收到的是按时完成；以前当正常、清零，三次也不重建
        val rebuilds = mutableListOf<String>()
        val amap = BoundedLocationSource(
            source { awaitAnswer("AMap single fix", 50.milliseconds) { awaitCancellation() } },
            scope,
            "AMap",
            limit = 2.seconds,
            watch = StuckWatch("AMap fetch timed out") { rebuilds += it },
        )
        // 备用的系统定位每次按时回来、没定到（屋里）：各数各的，不清高德的数
        val system = BoundedLocationSource(
            source { awaitAnswer("System location single fix", 2.seconds) { null } },
            scope,
            "System location",
            limit = 2.seconds,
            watch = StuckWatch("System location fetch timed out") { rebuilds += it },
        )
        repeat(STUCK_LIMIT) {
            assertEquals(null, amap.fetchOnce())
            assertEquals(null, system.fetchOnce())
        }
        assertEquals(listOf("AMap fetch timed out $STUCK_LIMIT times in a row"), rebuilds)
    }

    @Test
    fun aSourceThatAnswersWithoutAFixIsNotStuck(): Unit = runBlocking {
        val rebuilds = mutableListOf<String>()
        val watch = StuckWatch("fetch") { rebuilds += it }
        val bounded = BoundedLocationSource(source { awaitAnswer("x", 1.seconds) { null } }, scope, "x", limit = 2.seconds, watch = watch)
        repeat(STUCK_LIMIT * 2) { bounded.fetchOnce() }
        assertTrue(rebuilds.isEmpty(), "回调了、没定到是正常的")
    }

    @Test
    fun unvalidatedWifiThatReachesTheServerDrainsTheQueue(): Unit = runBlocking {
        // 高 11：Wi-Fi 到得了服务器，系统却没标「已验证」（isOnline 一直是 false）。试发成功后接着发，不再每条等 5 分钟
        val queued = Collections.synchronizedList(mutableListOf<Position>())
        val uploaded = Collections.synchronizedList(mutableListOf<Position>())
        // 离线时攒下的 20 条
        for (t in 1L..20L) queued += Position(time = t)
        TrackerEngine(
            stateStore = StateStore(
                org.traccar.client.db.Database(
                    app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY)
                        .also { org.traccar.client.db.Database.Schema.create(it) },
                ).stateQueries,
                State(enabled = true, paused = true),
            ),
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
                override val isOnline = MutableStateFlow(false)
            },
            locationSource = source { null },
            sensors = object : SensorSource {
                override val snapshots = MutableSharedFlow<Snapshot>()
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
            buffer = true,
            scope = scope,
            offlineRecheck = 300.milliseconds,
        )
        // 第一条等一次 recheck（300 毫秒），之后每条都要再等的话 20 条要 6 秒
        val done = withTimeoutOrNull(2.seconds) {
            while (uploaded.size < 20) delay(10)
        }
        assertTrue(done != null, "2 秒内只发了 ${uploaded.size} 条")
    }

    @Test
    fun aCallbackRegisteredOnAComponentIsRemovedWithIt() {
        // 中 5：每重建一次组件登记一个网络回调，旧组件结束时要注销，重建几次也只剩一个
        val active = mutableSetOf<Int>()
        val scopes = (1..5).map { n ->
            ComponentCoroutineScope().also { s ->
                s.registerWhileActive(register = { active += n }, unregister = { active -= n })
            }
        }
        scopes.dropLast(1).forEach { it.cancel() }
        assertEquals(setOf(5), active)
        scopes.last().cancel()
        assertTrue(active.isEmpty())
        // 作用域已经结束了才登记：马上撤
        val gone = ComponentCoroutineScope().also { it.cancel() }
        gone.registerWhileActive(register = { active += 9 }, unregister = { active -= 9 })
        assertTrue(active.isEmpty())
    }
}
