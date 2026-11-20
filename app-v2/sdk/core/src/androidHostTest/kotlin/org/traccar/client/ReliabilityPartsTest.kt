package org.traccar.client

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.traccar.client.db.Database

/** 可靠性补的几样零件：离线队列有上限、存储满时的内存缓冲、只重建定位源、组件异常不崩进程、日志写不进去不崩进程 */
class ReliabilityPartsTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
    private val faults = Collections.synchronizedList(mutableListOf<Pair<Fault, String>>())

    init {
        Log.faultSink = { _, kind, message -> faults += kind to message }
    }

    @AfterTest
    fun tearDown() {
        Log.faultSink = null
        driver.close()
    }

    private fun p(t: Long) = Position(time = t)

    @Test
    fun offlineQueueDropsTheOldestOverItsLimit(): Unit = runBlocking {
        val queue = DatabaseQueue(driver, keep = 5)
        for (t in 1L..8L) queue.enqueue(p(t))
        assertEquals(p(4), queue.peek(), "超过 5 条丢最旧的 3 条")
        assertTrue(faults.any { it.first == Fault.DROPPED && "dropped 1 oldest" in it.second }, "丢了要记异常")
        val left = mutableListOf<Long>()
        while (true) {
            val head = queue.peek() ?: break
            left += head.time
            queue.removeFirst()
        }
        assertEquals(listOf(4L, 5L, 6L, 7L, 8L), left)
    }

    /** 能注入故障的库 */
    private class Storage(private val db: RowQueue) : RowQueue {
        var writeFails = false
        var removeFails = false
        override suspend fun enqueue(position: Position) {
            if (writeFails) error("SQLITE_FULL")
            db.enqueue(position)
        }
        override suspend fun peekAfter(after: Long) = db.peekAfter(after)
        override suspend fun removeThrough(id: Long) {
            if (removeFails) error("SQLITE_FULL")
            db.removeThrough(id)
        }
    }

    private suspend fun ResilientQueue.drain(): List<Long> {
        val out = mutableListOf<Long>()
        while (true) {
            out += (peek() ?: break).time
            removeFirst()
        }
        return out
    }

    @Test
    fun pointsWaitInMemoryWhileStorageIsFullAndKeepTheirOrder(): Unit = runBlocking {
        val storage = Storage(DatabaseQueue(driver))
        val queue = ResilientQueue(storage)
        queue.enqueue(p(1))
        queue.enqueue(p(2))
        storage.writeFails = true
        queue.enqueue(p(3))
        queue.enqueue(p(4))
        assertEquals(2, queue.inMemory, "写不进库的先放内存")
        assertTrue(faults.any { it.first == Fault.STORAGE })
        assertEquals(p(1), queue.peek(), "库里旧的先发")
        storage.writeFails = false
        queue.enqueue(p(5))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), queue.drain(), "库好了按原来的顺序补进去，一个不少")
        assertEquals(0, queue.inMemory)
    }

    @Test
    fun aSentPointThatCannotBeRemovedIsNotSentAgain(): Unit = runBlocking {
        val storage = Storage(DatabaseQueue(driver))
        val queue = ResilientQueue(storage)
        queue.enqueue(p(1))
        queue.enqueue(p(2))
        assertEquals(p(1), queue.peek())
        // 发出去了，可存储满了删不掉：跳过 1 接着发 2（审计高 5：以前卡在 1 上，后面的全发不出去）
        storage.removeFails = true
        queue.removeFirst()
        assertEquals(p(2), queue.peek(), "1 不再交出去，也不卡住后面的")
        queue.removeFirst()
        assertEquals(null, queue.peek())
        storage.removeFails = false
        assertEquals(emptyList(), queue.drain(), "能删了就把发过的 1、2 补删，不重发")
        assertEquals(null, DatabaseQueue(driver).peek(), "库里也删干净了")
    }

    @Test
    fun memoryBufferHasALimit(): Unit = runBlocking {
        val storage = Storage(DatabaseQueue(driver)).apply { writeFails = true }
        val queue = ResilientQueue(storage, QueueMemory(limit = 3))
        for (t in 1L..5L) queue.enqueue(p(t))
        assertEquals(3, queue.inMemory)
        assertTrue(faults.count { it.first == Fault.DROPPED } == 2)
        storage.writeFails = false
        assertEquals(listOf(3L, 4L, 5L), queue.drain())
    }

    @Test
    fun rebuildingTheLocationSourceCancelsTheStuckFetchAndUsesANewOne(): Unit = runBlocking {
        val parent = ComponentCoroutineScope()
        try {
            val built = mutableListOf<ComponentCoroutineScope>()
            val emitters = mutableListOf<MutableSharedFlow<Position>>()
            val source = RebuildableLocationSource(parent) { scope ->
                built += scope
                val flow = MutableSharedFlow<Position>(extraBufferCapacity = 4).also { emitters += it }
                val n = built.size
                object : LocationSource {
                    override val positions = flow
                    // 第一个定位源卡死：不理不回；第二个正常
                    override suspend fun fetchOnce(): Position? = if (n == 1) awaitCancellation() else p(42)
                }
            }
            val stuck = parent.async { source.fetchOnce() }
            delay(50)
            val received = async { source.positions.first { it.time == 7L } }
            delay(50)
            source.rebuild()
            assertTrue(built[0].coroutineContext[Job]!!.isCancelled, "旧的定位源整个作用域取消（卡住的取点、高德客户端跟着停掉）")
            assertTrue(parent.coroutineContext[Job]!!.isActive, "引擎这一套不动")
            assertEquals(p(42), source.fetchOnce(), "之后取点用新的")
            // 引擎那边换订阅是异步的（新的定位源开连续定位也要一会儿）
            delay(50)
            emitters[1].emit(p(7))
            assertEquals(p(7), withTimeoutOrNull(2.seconds) { received.await() }, "连续定位的点从新的来")
            stuck.cancel()
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun anUncaughtFailureInsideTheComponentsDoesNotCrashTheProcess(): Unit = runBlocking {
        val uncaught = CompletableDeferred<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.complete(e) }
        val scope = ComponentCoroutineScope()
        try {
            scope.launch { error("SQLITE_FULL in a component loop") }
            val sibling = scope.launch { delay(200) }
            delay(300)
            assertTrue(sibling.isCompleted && !sibling.isCancelled, "别的协程照常跑")
            assertTrue(!uncaught.isCompleted, "没交给线程默认处理（Android 上那就是进程崩溃）")
            assertTrue(faults.any { it.first == Fault.CRASH }, "记一条异常")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            scope.cancel()
        }
    }

    @Test
    fun aBrokenLogStoreDoesNotCrashTheProcess(): Unit = runBlocking {
        val uncaught = CompletableDeferred<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.complete(e) }
        val broken = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val before = Log.store
        try {
            // 没建表的库：每次写都抛异常，和存储满时一样
            Log.store = LogStore(broken)
            Log.log("storage is full")
            delay(300)
            assertNull(withTimeoutOrNull(100) { uncaught.await() }, "写日志失败不交给线程默认处理")
        } finally {
            Log.store = before
            Thread.setDefaultUncaughtExceptionHandler(previous)
            broken.close()
        }
    }

    @Test
    fun stateThatCannotBeSavedIsKeptInMemoryWithoutThrowing(): Unit = runBlocking {
        // 没建表的库：写状态抛异常，和存储满时一样。撤掉接异常就抛到引擎的锁里，收快照的循环停掉
        val broken = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val store = StateStore(Database(broken).stateQueries, State())
            store.update { it.copy(enabled = true, motion = "STILL") }
            assertEquals(State(enabled = true, motion = "STILL"), store.state.value, "内存里的状态照样改")
            assertTrue(faults.any { it.first == Fault.STORAGE }, "记一条异常")
        } finally {
            broken.close()
        }
    }
}
