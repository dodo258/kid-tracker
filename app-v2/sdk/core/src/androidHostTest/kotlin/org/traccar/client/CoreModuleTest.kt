package org.traccar.client

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.traccar.client.alert.Alarm
import org.traccar.client.alert.AlertOutbox
import org.traccar.client.alert.AlertStore
import org.traccar.client.db.Database
import org.traccar.client.sense.Snapshot

/**
 * 依赖图建得起来：平台部分换成假的，核心部分（引擎、Tracker）用真的 coreModule 解析。
 * 2026-11-18 R4 在模拟器上发现：引擎参数改成 CoroutineScope 后 Koin 找不到定义，开机恢复、开始追踪全失败，单测都是手搭的引擎没发现。
 */
class CoreModuleTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
    private lateinit var koin: Koin

    @AfterTest
    fun tearDown() {
        koin.get<ComponentCoroutineScope>().coroutineContext[kotlinx.coroutines.Job]?.cancel()
        driver.close()
    }

    private fun build(): Koin = runBlocking {
        val fakePlatform = module {
            single<SqlDriver> { driver }
            single { HttpClient() }
            single<NetworkMonitor> { object : NetworkMonitor { override val isOnline = MutableStateFlow(true) } }
            single<PositionProcessor> { PositionProcessor { it } }
            single<LocationSource> {
                object : LocationSource {
                    override val positions = emptyFlow<Position>()
                    override suspend fun fetchOnce(): Position? = null
                }
            }
            single<SensorSource> {
                object : SensorSource {
                    override val snapshots = emptyFlow<Snapshot>()
                    override fun setSleeping(sleeping: Boolean) = Unit
                }
            }
            single<List<SignalSource>> { emptyList() }
        }
        koinApplication { modules(coreModule(), fakePlatform) }.koin.also {
            koin = it
            it.declare(StateStore.create(driver))
            // 服务器地址连不上：上传都失败
            it.declare(Config("http://127.0.0.1:1", "1"))
        }
    }

    @Test
    fun trackerAndEngineResolveFromTheModules() {
        val koin = build()
        assertNotNull(koin.get<TrackerEngine>())
        assertNotNull(koin.get<AlertOutbox>())
        assertNotNull(koin.get<Tracker>())
    }

    @Test
    fun stopRecordsTrackingOffAndUnsentSosIsKept(): Unit = runBlocking {
        val tracker = build().get<Tracker>()
        tracker.start()
        tracker.stop()
        tracker.stop() // 已经关着：不再报
        val first = tracker.sendSos(locate = true)
        assertFalse(first.sent, "服务器连不上：没发出去")
        assertEquals(first.id, tracker.sendSos(locate = true).id)
        assertEquals(listOf(Alarm.SOS, Alarm.TRACKING_OFF).sorted(), AlertStore(driver).pending().map { it.alarm }.sorted())
    }

    @Test
    fun retryAfterBackgroundDeliveryDoesNotSendAnotherSos(): Unit = runBlocking {
        val tracker = build().get<Tracker>()
        val store = AlertStore(driver)
        val first = tracker.sendSos(locate = false)
        // 后台联网补发出去了（界面还停在「没发出去」）
        store.markSent(first.id, 1L)
        assertTrue(tracker.isAlertSent(first.id))
        val again = tracker.sendSos(locate = false, retry = first.id)
        assertEquals(SosSend(first.id, sent = true), again, "再发带着编号：已经发出去就直接说发出去了")
        assertEquals(0L, store.pendingCount())
        // 不带编号（新打开的求救）才是新的一条
        assertTrue(tracker.sendSos(locate = false).id != first.id)
    }
}
