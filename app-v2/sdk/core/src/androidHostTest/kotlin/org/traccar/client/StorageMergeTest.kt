package org.traccar.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.traccar.client.alert.Alarm
import org.traccar.client.alert.AlertStore
import org.traccar.client.db.Database

/**
 * 没解锁也要恢复上报（Direct Boot）：凭据存储里的老库（附加成 ce）并进设备保护存储里的库，不丢不重。
 * 安卓侧（DeviceStorage.mergeDatabase）用的就是这几条语句。
 */
class StorageMergeTest {

    private val dir: File = Files.createTempDirectory("storage-merge").toFile()
    private val credentialFile = File(dir, "ce.db")
    private val credential = open(credentialFile)
    private val deviceFile = File(dir, "de.db")
    private val device = open(deviceFile)

    private fun open(file: File) = JdbcSqliteDriver("jdbc:sqlite:${file.path}").also { Database.Schema.create(it) }

    @AfterTest
    fun tearDown() {
        credential.close()
        device.close()
        dir.deleteRecursively()
    }

    /** 和安卓侧一样：单独开一个连接，附加老库，一个事务里跑完 */
    private fun merge() {
        DriverManager.getConnection("jdbc:sqlite:${deviceFile.path}").use { connection ->
            connection.createStatement().use { it.execute("ATTACH DATABASE '${credentialFile.path}' AS ${StorageMerge.ALIAS}") }
            connection.autoCommit = false
            connection.createStatement().use { statement -> StorageMerge.statements.forEach(statement::execute) }
            connection.commit()
            connection.autoCommit = true
            connection.createStatement().use { it.execute("DETACH DATABASE ${StorageMerge.ALIAS}") }
        }
    }

    private suspend fun drain(queue: DatabaseQueue): List<Position> = buildList {
        while (true) {
            add(queue.peek() ?: break)
            queue.removeFirst()
        }
    }

    @Test
    fun mergesOfflineQueueAlertsAndLogsWithoutDuplicates(): Unit = runBlocking {
        // 老库：升级前攒的两个离线点、一条没发出去的求救、一条日志，追踪开着
        DatabaseQueue(credential).apply {
            enqueue(Position(latitude = 30.0, longitude = 120.0, time = 1_000, provider = Provider.GPS, state = "moving"))
            enqueue(Position(time = 2_000, state = "still"))
        }
        AlertStore(credential).insert(Position(time = 1_500, alarm = Alarm.SOS), ready = true)
        LogStore(credential).insert("before reboot", time = 900)
        StateStore.create(credential).update { it.copy(enabled = true) }
        ConfigStore(credential).save(Config("https://example.org/osmand", "123456"))
        // 新库：没解锁时报不出去的一个点
        DatabaseQueue(device).enqueue(Position(time = 3_000, state = "still", locked = true))

        merge()
        // 搬到一半中断、下次再并一遍：不多出一条
        merge()

        assertEquals(
            listOf(3_000L, 1_000L, 2_000L),
            drain(DatabaseQueue(device)).map { it.time },
        )
        assertEquals(listOf(Alarm.SOS), AlertStore(device).pending().map { it.alarm })
        assertEquals(listOf("before reboot"), LogStore(device).all().map { it.message })
        assertEquals(Config("https://example.org/osmand", "123456"), ConfigStore(device).load())
        assertEquals(true, StateStore.create(device).state.value.enabled)
    }

    @Test
    fun keepsNewerConfigAndState(): Unit = runBlocking {
        ConfigStore(credential).save(Config("https://old.example.org", "111"))
        StateStore.create(credential).update { it.copy(enabled = true, paused = false) }
        // 新库里已经有的是后来写的（没解锁时转了静止），用新的
        ConfigStore(device).save(Config("https://new.example.org", "222"))
        StateStore.create(device).update { it.copy(enabled = true, paused = true) }

        merge()

        assertEquals("https://new.example.org", ConfigStore(device).load()?.serverUrl)
        assertEquals(true, StateStore.create(device).state.value.paused)
    }

    @Test
    fun keepsSentFlagOfMergedAlerts(): Unit = runBlocking {
        val store = AlertStore(credential)
        val sent = store.insert(Position(time = 1_000, alarm = Alarm.LOW_BATTERY), ready = true)
        store.markSent(sent, 2_000)
        store.insert(Position(time = 3_000, alarm = Alarm.POWER_OFF), ready = true)

        merge()

        // 发过的不再发，没发的接着发
        assertEquals(listOf(Alarm.POWER_OFF), AlertStore(device).pending().map { it.alarm })
    }
}
