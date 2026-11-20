package org.traccar.client.alert

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.traccar.client.Position
import org.traccar.client.Provider
import org.traccar.client.Uploader
import org.traccar.client.db.Database

/**
 * 告警发件箱：先存再发、2xx 才算、断网联网后补发、进程重来接着发、不重复、位置只带新鲜卫星点。
 * 用真的 SQLite（JVM 内存库）和假的服务器。
 */
class AlertOutboxTest {

    /** 假服务器：[accept] 为假时像断网 / 5xx 一样不收；[received] 是服务器真收到的 */
    private class FakeServer : Uploader {
        @Volatile var accept = false
        @Volatile var hang = false
        var slow = 0L
        val received = java.util.Collections.synchronizedList(mutableListOf<Position>())
        val attempts = java.util.concurrent.atomic.AtomicInteger()

        override suspend fun upload(position: Position): Boolean {
            attempts.incrementAndGet()
            if (hang) CompletableDeferred<Unit>().await()
            if (slow > 0) delay(slow)
            if (!accept) return false
            received += position
            return true
        }
    }

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
    private val scopes = mutableListOf<CoroutineScope>()
    private val online = MutableStateFlow(false)
    private val server = FakeServer()
    private var clock = 1_800_000_000_000L
    private var latestGps: Position? = null
    private val retries = java.util.concurrent.atomic.AtomicInteger()

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        driver.close()
    }

    private fun outbox(uploader: Uploader = server, isOnline: MutableStateFlow<Boolean> = online): AlertOutbox {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return AlertOutbox(
            store = AlertStore(driver),
            uploader = uploader,
            processor = { it.copy(battery = it.battery ?: 42) },
            isOnline = isOnline,
            scope = scope,
            latestGps = { latestGps },
            describe = { it.copy(state = "still") },
            retry = { retries.incrementAndGet() },
            now = { clock },
            uploadTimeout = 300.milliseconds,
            initialBackoff = 50.milliseconds,
            maxBackoff = 200.milliseconds,
        )
    }

    private suspend fun until(what: String, check: suspend () -> Boolean) {
        withTimeout(5.seconds) { while (!check()) delay(10) }
            .also { assertTrue(check(), what) }
    }

    private fun gps(ageMillis: Long) =
        Position(latitude = 30.25, longitude = 120.16, accuracy = 4.0, time = clock - ageMillis, provider = Provider.GPS)

    @Test
    fun sosWhileOfflineIsSentOnceNetworkReturns(): Unit = runBlocking {
        val outbox = outbox()
        val id = outbox.sos { null }
        assertFalse(outbox.flush(), "断网时发不出去")
        assertFalse(outbox.isSent(id))
        assertTrue(server.received.isEmpty())
        assertTrue(retries.get() > 0, "没发出去要挂联网补发任务")

        server.accept = true
        online.value = true
        until("联网后自己补发") { outbox.isSent(id) }
        assertEquals(listOf("sos"), server.received.map { it.alarm })

        // 再发几遍也不重复
        assertTrue(outbox.flush())
        delay(200)
        assertEquals(1, server.received.size)
    }

    @Test
    fun unsentAlertsSurviveProcessRestart(): Unit = runBlocking {
        val before = outbox()
        before.raise(Alarm.POWER_OFF, "shutdown")
        before.raise(Alarm.LOW_BATTERY, "battery 9%", battery = 9, charging = false)
        assertFalse(before.flush())
        scopes.forEach { it.cancel() } // 进程没了

        server.accept = true
        online.value = true
        val after = outbox() // 开机后新建的组件：同一个库
        until("重启后接着发") { server.received.size == 2 }
        assertEquals(listOf("powerOff", "lowBattery"), server.received.map { it.alarm })
        assertEquals(9, server.received[1].battery)
        assertTrue(after.flush())
        assertEquals(2, server.received.size)
    }

    @Test
    fun pressingSosAgainResendsTheSameAlert(): Unit = runBlocking {
        val outbox = outbox()
        val first = outbox.sos { null }
        outbox.flush()
        clock += 20_000
        val again = outbox.sos { null }
        assertEquals(first, again, "没发出去时再按是重发那一条")

        server.accept = true
        assertTrue(outbox.flush())
        assertEquals(1, server.received.size)
        // 发出去之后再按就是新的一次求救
        clock += 60_000
        val next = outbox.sos { null }
        assertTrue(next != first)
        assertTrue(outbox.flush())
        assertEquals(2, server.received.size)
    }

    @Test
    fun concurrentSendersDoNotDuplicate(): Unit = runBlocking {
        // 组件重建时新旧两套发件箱、联网补发任务同时来发同一个库
        val a = outbox()
        val b = outbox()
        a.raise(Alarm.TRACKING_OFF, "off")
        a.sos { null }
        server.accept = true
        server.slow = 30
        (1..3).flatMap { listOf(async(Dispatchers.Default) { a.flush() }, async(Dispatchers.Default) { b.flush() }) }.awaitAll()
        assertEquals(2, server.received.size)
        assertEquals(setOf("sos", "trackingOff"), server.received.map { it.alarm }.toSet())
    }

    @Test
    fun onlyServerSuccessCountsAsSent(): Unit = runBlocking {
        val outbox = outbox()
        val id = outbox.raise(Alarm.GENERAL, "locationOff")
        // 非 2xx（HttpUploader 返回 false）
        server.accept = false
        assertFalse(outbox.flush())
        assertFalse(outbox.isSent(id))
        // 服务器不回：超时，不算
        server.hang = true
        assertFalse(outbox.flush())
        assertFalse(outbox.isSent(id))
        server.hang = false
        server.accept = true
        assertTrue(outbox.flush())
        assertTrue(outbox.isSent(id))
    }

    @Test
    fun tamperingCarriesReasonAndSurvivesOffline(): Unit = runBlocking {
        // 设备管理员被停用时多半没网：先存，联网补发，原因一起报给服务器（通知模板按它分文案）
        outbox().raise(Alarm.TAMPERING, "adminDisabled", reported = "adminDisabled")
        val later = outbox()
        server.accept = true
        assertTrue(later.flush())
        val sent = server.received.single()
        assertEquals("tampering", sent.alarm)
        assertEquals("adminDisabled", sent.reason)
        // 别的告警不带 reason
        later.raise(Alarm.GENERAL, "locationOff")
        assertTrue(later.flush())
        assertNull(server.received.last().reason)
    }

    @Test
    fun sosGoesFirst(): Unit = runBlocking {
        val outbox = outbox()
        outbox.raise(Alarm.LOW_BATTERY, "battery 10%", battery = 10)
        outbox.raise(Alarm.GENERAL, "locationOff")
        outbox.sos { null }
        server.accept = true
        assertTrue(outbox.flush())
        assertEquals(listOf("sos", "lowBattery", "general"), server.received.map { it.alarm })
    }

    @Test
    fun alertCarriesOnlyFreshSatelliteFix(): Unit = runBlocking {
        val outbox = outbox()
        server.accept = true

        latestGps = gps(ageMillis = 3_600_000) // 一小时前的点
        outbox.raise(Alarm.POWER_OFF, "shutdown")
        latestGps = gps(ageMillis = 5_000)
        outbox.raise(Alarm.LOW_BATTERY, "battery 9%", battery = 9)
        assertTrue(outbox.flush())

        val (old, fresh) = server.received
        assertNull(old.latitude, "旧点不能配上现在的时间")
        assertNull(old.longitude)
        assertEquals(clock, old.time)
        assertEquals(30.25, fresh.latitude)
        assertEquals(clock - 5_000, fresh.time, "带位置时用真实定位时间")
        assertEquals(Provider.GPS, fresh.provider)
        assertEquals("still", fresh.state)
    }

    @Test
    fun sosLocatesAndKeepsOnlySatelliteFix(): Unit = runBlocking {
        val outbox = outbox()
        server.accept = true

        outbox.sos { Position(latitude = 30.0, longitude = 120.0, time = clock, provider = Provider.WIFI) }
        outbox.flush()
        clock += 120_000
        outbox.sos { gps(ageMillis = 2_000) }
        outbox.flush()
        clock += 120_000
        outbox.sos { error("location service died") }
        outbox.flush()

        val (wifi, satellite, failed) = server.received
        assertNull(wifi.latitude, "Wi-Fi 定位不当告警位置")
        assertNull(wifi.netLat)
        assertEquals(30.25, satellite.latitude)
        assertNull(failed.latitude)
        assertEquals(listOf(42, 42, 42), server.received.map { it.battery })
    }

    @Test
    fun sosUsesEngineFixWithoutWaiting(): Unit = runBlocking {
        val outbox = outbox()
        latestGps = gps(ageMillis = 1_000)
        var asked = false
        outbox.sos { asked = true; null }
        assertFalse(asked, "引擎手里有新鲜卫星点就不再现取")
        server.accept = true
        outbox.flush()
        assertEquals(30.25, server.received.single().latitude)
    }

    @Test
    fun sosStillLocatingIsHeldBackButNotForever(): Unit = runBlocking {
        val store = AlertStore(driver)
        val outbox = outbox()
        server.accept = true
        // 正在取位置的求救先不发
        store.insert(Position(time = clock, alarm = Alarm.SOS), ready = false)
        assertFalse(outbox.flush())
        assertTrue(server.received.isEmpty())
        // 取位置时进程被杀，过了时限只报时间发出去
        clock += AlertOutbox.LOCATE_LIMIT
        assertTrue(outbox.flush())
        assertEquals(1, server.received.size)
        assertNull(server.received.single().latitude)
    }

    @Test
    fun fullOutboxDropsOldestButNeverSos(): Unit = runBlocking {
        val outbox = outbox()
        outbox.sos { null }
        repeat(AlertOutbox.KEEP_PENDING.toInt() + 5) { outbox.raise(Alarm.LOW_BATTERY, "battery") }
        val pending = AlertStore(driver).pending()
        assertEquals(AlertOutbox.KEEP_PENDING.toInt(), pending.size)
        assertNotNull(pending.find { it.alarm == Alarm.SOS })
    }
}
