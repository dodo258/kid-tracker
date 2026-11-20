package org.traccar.client

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.traccar.client.db.Database

/** 审计第二批的离线队列三条（docs/specs/2026-11-19-v2-audit-fixes.md）：高 5、中 1、中 2。存储满用 SQLite 触发器模拟（真库报错） */
class AuditFixQueueTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }

    @AfterTest
    fun tearDown() {
        QueueMemory.process.points.clear()
        driver.close()
    }

    private fun p(t: Long) = Position(time = t)

    private fun failDeletes(on: Boolean) = driver.execute(
        null,
        if (on) "CREATE TRIGGER full_delete BEFORE DELETE ON Position BEGIN SELECT RAISE(ABORT, 'database or disk is full'); END" else "DROP TRIGGER full_delete",
        0,
    )

    private fun failInserts(on: Boolean) = driver.execute(
        null,
        if (on) "CREATE TRIGGER full_insert BEFORE INSERT ON Position BEGIN SELECT RAISE(ABORT, 'database or disk is full'); END" else "DROP TRIGGER full_insert",
        0,
    )

    private suspend fun PositionQueue.drain(): List<Long> {
        val out = mutableListOf<Long>()
        while (true) {
            out += (peek() ?: break).time
            removeFirst()
        }
        return out
    }

    @Test
    fun aSentPointStuckInFullStorageDoesNotBlockTheRest(): Unit = runBlocking {
        // 高 5：无卡 P20 连回 Wi-Fi 补传，第一条发出去了、存储满删不掉：后面的照发，每条只发一次
        val queue = ResilientQueue(DatabaseQueue(driver))
        for (t in 1L..4L) queue.enqueue(p(t))
        failDeletes(true)
        assertEquals(listOf(1L, 2L, 3L, 4L), queue.drain(), "删不掉也接着发后面的，不重发")
        failDeletes(false)
        assertNull(queue.peek())
        assertNull(DatabaseQueue(driver).peek(), "能删了就把发过的补删掉")
    }

    @Test
    fun confirmingAPointTrimmedWhileSendingKeepsTheNextOne(): Unit = runBlocking {
        // 中 1：队列到上限，发送方拿着队首在发，这时新点进来把队首裁掉；确认时不能误删下一条没发的
        val db = DatabaseQueue(driver, keep = 3)
        for (t in 1L..3L) db.enqueue(p(t))
        assertEquals(p(1), db.peek())
        db.enqueue(p(4))
        db.removeFirst()
        assertEquals(listOf(2L, 3L, 4L), db.drain())

        // 套着 ResilientQueue（App 里用的）也一样
        val queue = ResilientQueue(DatabaseQueue(driver, keep = 3))
        for (t in 11L..13L) queue.enqueue(p(t))
        assertEquals(p(11), queue.peek())
        queue.enqueue(p(14))
        queue.removeFirst()
        assertEquals(listOf(12L, 13L, 14L), queue.drain())
    }

    @Test
    fun memoryBufferSurvivesRebuildingTheComponents(): Unit = runBlocking {
        // 中 2：存储满时点在内存里；改设置、解锁、看门狗重建整套组件（新的 Koin、新的队列），内存里的点要交给新的一套
        val platform = module { single<SqlDriver> { driver } }
        val first = koinApplication { modules(coreModule(), platform) }.koin.get<PositionQueue>()
        failInserts(true)
        first.enqueue(p(1))
        first.enqueue(p(2))
        val rebuilt = koinApplication { modules(coreModule(), platform) }.koin.get<PositionQueue>()
        failInserts(false)
        assertEquals(listOf(1L, 2L), rebuilt.drain())
    }
}
