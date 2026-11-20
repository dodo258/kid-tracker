package org.traccar.client

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 库写不进去时放点的内存缓冲，最多 [limit] 条（满了丢最旧的）。整个进程一份（[QueueMemory.process]）：改设置、解锁、看门狗重建跟踪组件时
 * 新的一套接着用，不跟着旧组件丢（审计中 2）；进程被杀时还是会丢（库写不进去时没有别处可放）。锁也在这里，新旧两套不会同时改。
 */
internal class QueueMemory(val limit: Int = ResilientQueue.MEMORY_LIMIT) {
    val mutex = Mutex()
    val points = ArrayDeque<Position>()

    companion object {
        val process = QueueMemory()
    }
}

/**
 * 离线队列外面的一层：库写不进去（存储满了、库出错）时点先放内存，库好了按原来的顺序补进去；发出去了但从库里删不掉时不重发、也不卡住。
 *
 * - 顺序：库里的点一定比内存里的旧（内存里有点时新点也接着放内存，不插队写库），所以先发库里的、再发内存里的。
 * - 内存缓冲见 [QueueMemory]，满了丢最旧的并记异常。
 * - 发出去后按行号确认（[RowQueue.removeThrough]）。删不掉（存储满时删除也要写日志文件）就记下「这一行及以前都发过了」，
 *   接着发后面的（库里的、内存里的），每次取下一条前再试着补删（审计高 5：以前删不掉就停，后面的点全卡住）。
 *   这个记号只在这一套组件里；重建或进程重启后没删掉的那几条会再发一遍（宁可重一条，不丢）。
 *
 * 原版没有：原版直接用库，写不进去时异常把整条上报流程弄停（协程挂了，之后一个点都不报）。
 */
internal class ResilientQueue(
    private val db: RowQueue,
    private val memory: QueueMemory = QueueMemory(),
) : PositionQueue {

    private val points get() = memory.points

    /** 上次 peek 给出去的：库里的行号，或内存里的那一条 */
    private var peekedRow: Long? = null
    private var memoryHead: Position? = null

    /** 库里行号不超过它的都发出去了；[removedThrough] 是确实删掉了的 */
    private var sentThrough = 0L
    private var removedThrough = 0L

    /** 内存里有几条（测试和日志用） */
    val inMemory: Int get() = points.size

    override suspend fun enqueue(position: Position) = memory.mutex.withLock {
        if (points.isEmpty() && write(position)) return@withLock
        points.addLast(position)
        if (points.size > memory.limit) {
            points.removeFirst()
            Log.fault(Fault.DROPPED, "storage unavailable and memory buffer over ${memory.limit}, dropped oldest")
        }
        // 不在这里往库里补：发送方可能正拿着内存里的头一条在发，补进库后它删的就不是发出去的那条了。只在 peek 时补
    }

    override suspend fun peek(): Position? = memory.mutex.withLock {
        if (removedThrough < sentThrough) remove(sentThrough)
        flushMemory()
        val stored = try {
            db.peekAfter(sentThrough)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (points.isEmpty()) throw e
            null
        }
        peekedRow = stored?.id
        memoryHead = if (stored == null) points.firstOrNull() else null
        stored?.position ?: memoryHead
    }

    override suspend fun removeFirst() = memory.mutex.withLock {
        val head = memoryHead
        if (head != null) {
            // 发的时候内存满了丢过最旧的，头一条可能已经不是它了
            if (points.firstOrNull() === head) points.removeFirst()
            memoryHead = null
            return@withLock
        }
        val id = peekedRow ?: return@withLock
        peekedRow = null
        sentThrough = maxOf(sentThrough, id)
        if (!remove(id)) Log.fault(Fault.STORAGE, "sent point not removed from offline queue, skipping it and removing later")
    }

    private suspend fun remove(id: Long): Boolean = try {
        db.removeThrough(id)
        removedThrough = maxOf(removedThrough, id)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun write(position: Position): Boolean = try {
        db.enqueue(position)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.fault(Fault.STORAGE, "offline queue write failed, keeping points in memory: $e")
        false
    }

    /** 内存里的按顺序补进库，写不进去就停 */
    private suspend fun flushMemory() {
        while (points.isNotEmpty()) {
            try {
                db.enqueue(points.first())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return
            }
            points.removeFirst()
            if (points.isEmpty()) Log.log("Offline queue writable again, memory buffer moved to storage")
        }
    }

    companion object {
        /** 库写不进去时内存里最多放这么多点：在动时 10 秒一条，够撑 5 个多小时 */
        const val MEMORY_LIMIT = 2_000
    }
}
