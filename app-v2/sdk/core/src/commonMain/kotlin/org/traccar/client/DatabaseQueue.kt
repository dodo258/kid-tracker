package org.traccar.client

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.traccar.client.db.Database

/**
 * 离线队列（原版）。比原版多一个上限 [keep]：没网的日子一长（关机放抽屉、出国没开漫游），队列不能把存储吃满、拖垮整个 App；
 * 超了丢最旧的并记一条异常。默认 5 万条：在动时 10 秒一条也够存 5 天多，一条约 300 字节，最多十几 MB。
 */
class DatabaseQueue(driver: SqlDriver, private val keep: Long = MAX_QUEUE) : PositionQueue, RowQueue {

    private val queries = Database(driver).positionQueries
    private val mutex = Mutex()

    override suspend fun enqueue(position: Position) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // 插入、看上限、裁掉最旧的在一个事务里：裁不掉（存储满时删除也会失败）就整笔回滚、抛给调用方转内存缓冲。
                // 以前分两次提交，点已经进了库还被当成没写进去再放内存，每次取队首又插一遍，同一点反复补传（审计 11.0.11 第 4 条）
                val over = queries.transactionWithResult {
                    queries.enqueue(
                        latitude = position.latitude,
                        longitude = position.longitude,
                        accuracy = position.accuracy,
                        time = position.time,
                        altitude = position.altitude,
                        speed = position.speed,
                        bearing = position.bearing,
                        battery = position.battery?.toLong(),
                        charging = position.charging?.let { if (it) 1L else 0L },
                        extras = Extras.of(position)?.let { json.encodeToString(it) },
                    )
                    (queries.span().executeAsOne() - keep).also { if (it > 0) queries.trim(keep) }
                }
                if (over > 0) Log.fault(Fault.DROPPED, "offline queue over $keep, dropped $over oldest")
            }
        }
    }

    /** 上次 [peek] 给出去的那一行：确认时按行号删，发送期间队列满了裁掉旧的也不会误删下一条（审计中 1，旧版 DatabaseQueue 的做法） */
    private var peekedId: Long? = null

    override suspend fun peek(): Position? = peekAfter(0)?.also { peekedId = it.id }?.position

    override suspend fun removeFirst() {
        val id = peekedId ?: return
        peekedId = null
        removeThrough(id)
    }

    override suspend fun peekAfter(after: Long): QueuedRow? = withContext(Dispatchers.IO) {
        mutex.withLock {
            queries.peekAfter(after).executeAsOneOrNull()?.let { row ->
                val extras = row.extras?.let { json.decodeFromString<Extras>(it) }
                QueuedRow(
                    row.id,
                    Position(
                        latitude = row.latitude,
                        longitude = row.longitude,
                        accuracy = row.accuracy,
                        time = row.time,
                        altitude = row.altitude,
                        speed = row.speed,
                        bearing = row.bearing,
                        battery = row.battery?.toInt(),
                        charging = row.charging?.let { it != 0L },
                        provider = extras?.provider,
                        state = extras?.state,
                        motion = extras?.motion,
                        steps = extras?.steps,
                        bumps = extras?.bumps,
                        net = extras?.net,
                        netLat = extras?.netLat,
                        netLon = extras?.netLon,
                        netAcc = extras?.netAcc,
                        netSource = extras?.netSource,
                        wifiSame = extras?.wifiSame,
                        locked = extras?.locked,
                    ),
                )
            }
        }
    }

    override suspend fun removeThrough(id: Long) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                queries.removeThrough(id)
            }
        }
    }

    /** 判断层的上报字段，存在 extras 一列里（3.sqm） */
    @Serializable
    private data class Extras(
        val provider: String? = null,
        val state: String? = null,
        val motion: String? = null,
        val steps: Int? = null,
        val bumps: Int? = null,
        val net: String? = null,
        val netLat: Double? = null,
        val netLon: Double? = null,
        val netAcc: Double? = null,
        val netSource: String? = null,
        val wifiSame: Boolean? = null,
        val locked: Boolean? = null,
    ) {
        companion object {
            fun of(p: Position): Extras? = Extras(
                p.provider, p.state, p.motion, p.steps, p.bumps, p.net, p.netLat, p.netLon, p.netAcc, p.netSource, p.wifiSame, p.locked,
            ).takeIf { it != Extras() }
        }
    }

    internal companion object {
        const val MAX_QUEUE = 50_000L
        private val json = Json { ignoreUnknownKeys = true }
    }
}
