package org.traccar.client

import app.cash.sqldelight.db.SqlDriver
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.traccar.client.db.Database
import org.traccar.client.db.StateQueries

class StateStore internal constructor(
    private val queries: StateQueries,
    initialState: State,
) {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * 改状态并落盘。写不进去（存储满）时内存里的状态照样改、记一条异常，不往外抛：调用方多在引擎的锁里，
     * 抛出去会把收快照的循环弄停。每次都整份写，存储好了下一次改就补上
     */
    suspend fun update(transform: (State) -> State) {
        val next = _state.updateAndGet(transform)
        try {
            withContext(Dispatchers.IO) {
                queries.saveState(json.encodeToString(next))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.fault(Fault.STORAGE, "state not saved, kept in memory: $e")
        }
    }

    companion object {
        suspend fun create(driver: SqlDriver): StateStore = withContext(Dispatchers.IO) {
            val queries = Database(driver).stateQueries
            val initial = queries.selectState().executeAsOneOrNull()
                ?.let { json.decodeFromString<State>(it) } ?: State()
            StateStore(queries, initial)
        }

        // R2 及以前存过的字段（原版距离过滤的 lastAcceptedLocation）读的时候跳过
        private val json = Json { ignoreUnknownKeys = true }
    }
}
