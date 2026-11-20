package org.traccar.client.alert

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.traccar.client.Position
import org.traccar.client.db.Database

/** 存在本机的一条告警。[position] 是要上报的整条（含 alarm）；[sentAt] 为 null 是还没收到服务器 2xx */
data class Alert(
    val id: Long,
    val alarm: String,
    val time: Long,
    val position: Position,
    val ready: Boolean,
    val sentAt: Long?,
)

/** 告警的本机存储（库里的 Alert 表，和离线队列同一个库），进程被杀、重启、覆盖安装后接着用 */
class AlertStore(driver: SqlDriver) {

    private val queries = Database(driver).alertQueries

    suspend fun insert(position: Position, ready: Boolean): Long = io {
        queries.transactionWithResult {
            queries.insert(checkNotNull(position.alarm), position.time, json.encodeToString(position), if (ready) 1L else 0L)
            queries.lastId().executeAsOne()
        }
    }

    suspend fun get(id: Long): Alert? = io { queries.selectById(id, ::row).executeAsOneOrNull() }

    suspend fun pending(): List<Alert> = io { queries.selectPending(::row).executeAsList() }

    suspend fun pending(alarm: String): Alert? = io { queries.selectPendingByAlarm(alarm, ::row).executeAsOneOrNull() }

    suspend fun pendingCount(): Long = io { queries.countPending().executeAsOne() }

    /** 求救定到位置了（或等不到了）：换上要报的那条，可以发了 */
    suspend fun locate(id: Long, position: Position) = io { queries.locate(json.encodeToString(position), id) }

    suspend fun markSent(id: Long, at: Long) = io { queries.markSent(at, id) }

    /** 发出去的留 [keepSent] 条；没发出去的超过 [keepPending] 条丢最旧的（求救不丢）。返回丢了几条没发的 */
    suspend fun trim(keepSent: Long, keepPending: Long): Long = io {
        queries.transactionWithResult {
            queries.trimSent(keepSent)
            val before = queries.countPending().executeAsOne()
            queries.trimPending(keepPending)
            before - queries.countPending().executeAsOne()
        }
    }

    private fun row(id: Long, alarm: String, time: Long, position: String, ready: Long, sentAt: Long?) =
        Alert(id, alarm, time, json.decodeFromString(position), ready != 0L, sentAt)

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
