package org.traccar.client

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.traccar.client.alert.Alarm
import org.traccar.client.alert.AlertOutbox
import org.traccar.client.alert.AlertStore
import org.traccar.client.db.Database
import org.traccar.client.sense.Snapshot

/** 审计第二批：高 12 新求救不排在整批旧告警后面；低 1 手动发送位置的日志不写坐标 */
class AuditFixAlertTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        Log.store = null
        driver.close()
    }

    @Test
    fun newSosGoesOutBeforeTheRestOfABatchOfOldAlerts(): Unit = runBlocking {
        val sent = Collections.synchronizedList(mutableListOf<String>())
        val online = MutableStateFlow(false)
        val outbox = AlertOutbox(
            store = AlertStore(driver),
            uploader = object : Uploader {
                override suspend fun upload(position: Position): Boolean {
                    delay(100)
                    sent += position.alarm.orEmpty()
                    return true
                }
            },
            processor = { it },
            isOnline = online,
            scope = scope,
            offlineRecheck = 1.seconds,
        )
        // 断网时攒了 10 条普通告警
        repeat(10) { outbox.raise(Alarm.LOW_BATTERY, "test $it") }
        val batch = async { outbox.flush() }
        delay(150)
        // 补传到第二条时孩子按了求救：App 里只等这一条
        val sos = outbox.sos { null }
        withTimeout(1.seconds) { outbox.flush(until = sos) }
        assertTrue(outbox.isSent(sos), "求救在 1 秒内发出去")
        assertTrue(sent.indexOf(Alarm.SOS) in 1..3, "求救排在正在发的那条后面：$sent")
        batch.await()
        assertEquals(11, sent.size, "每条只发一次")
    }

    @Test
    fun manualSendDoesNotLogCoordinates(): Unit = runBlocking {
        val fix = Position(latitude = 30.2737, longitude = 120.1373, accuracy = 20.0, time = 1_800_000_000_000L, provider = Provider.GPS)
        val platform = module {
            single<SqlDriver> { driver }
            single { HttpClient() }
            single<NetworkMonitor> { object : NetworkMonitor { override val isOnline = MutableStateFlow(false) } }
            single<PositionProcessor> { PositionProcessor { it } }
            single<LocationSource> {
                object : LocationSource {
                    override val positions = emptyFlow<Position>()
                    override suspend fun fetchOnce(): Position = fix
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
        val koin = koinApplication { modules(coreModule(), platform) }.koin
        koin.declare(StateStore.create(driver))
        koin.declare(Config("http://127.0.0.1:1", "1"))
        val store = LogStore(driver)
        Log.store = store
        try {
            koin.get<Tracker>().requestPosition()
            withTimeout(5.seconds) {
                while (store.all().none { it.message.startsWith("Position fetched") }) delay(20)
            }
            val logged = store.all().map { it.message }
            assertTrue(logged.none { "30.27" in it || "120.13" in it }, "日志里有坐标：$logged")
        } finally {
            koin.get<ComponentCoroutineScope>().cancel()
        }
    }
}
