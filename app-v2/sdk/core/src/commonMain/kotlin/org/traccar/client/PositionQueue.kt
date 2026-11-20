package org.traccar.client

interface PositionQueue {
    suspend fun enqueue(position: Position)
    suspend fun peek(): Position?
    suspend fun removeFirst()
}

/** 库里的一行：[id] 是自增行号，确认发出时按它删（审计中 1） */
data class QueuedRow(val id: Long, val position: Position)

/** 按行号存取的离线队列（[DatabaseQueue]），[ResilientQueue] 用它记「哪些已经发出去了」 */
interface RowQueue {
    suspend fun enqueue(position: Position)

    /** 行号比 [after] 大的最旧一条 */
    suspend fun peekAfter(after: Long): QueuedRow?

    /** 删掉行号不超过 [id] 的（都发出去了） */
    suspend fun removeThrough(id: Long)
}
