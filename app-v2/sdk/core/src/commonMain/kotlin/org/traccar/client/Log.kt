package org.traccar.client

import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object Log {

    /**
     * 日志库在 sharedTracker() 里才打开。打开前写的（进程刚起来时搬存储、开机广播）先按原来的时间攒着（最多 [EARLY_MAX] 条），
     * 打开后补写进去；原版直接丢掉。
     */
    @Volatile
    var store: LogStore? = null
        set(value) {
            field = value
            if (value != null) scope.launch { mutex.withLock { drain(value) } }
        }

    @Volatile
    var retention: Int = 5000

    private const val EARLY_MAX = 100
    private val early = ArrayList<Pair<Long, String>>()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 出了异常（降级、重建、丢弃、写不进存储）时平台记「最近一次异常」的地方（Android：自检清单那份记录，见 HealthRecorder）。
     * 参数是时间、种类、说明
     */
    @Volatile
    var faultSink: ((Long, Fault, String) -> Unit)? = null

    fun log(message: String) {
        val time = Clock.System.now().toEpochMilliseconds()
        scope.launch {
            mutex.withLock {
                val target = store
                if (target == null) {
                    if (early.size < EARLY_MAX) early += time to message
                    return@withLock
                }
                // 写日志失败（存储满了、库坏了）不能把别的拖垮：原版不接，异常落到线程默认处理，Android 上进程直接崩
                try {
                    if (dropped > 0) {
                        target.insert("Log: $dropped entries not written (storage error)", time)
                        dropped = 0
                    }
                    target.insert(message, time)
                    target.trim(retention)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    dropped++
                }
            }
        }
    }

    /** 记一条异常：日志里以「Fault」开头（导出的日志里搜得到），同时交给平台记成「最近一次异常」 */
    fun fault(kind: Fault, message: String) {
        log("Fault ${kind.name.lowercase()}: $message")
        val time = Clock.System.now().toEpochMilliseconds()
        try {
            faultSink?.invoke(time, kind, message)
        } catch (e: Exception) {
            log("Fault not recorded: $e")
        }
    }

    /** 写不进库丢掉的日志条数（存储满时），恢复后第一条补一句；只在 [mutex] 里改 */
    private var dropped = 0

    private suspend fun drain(target: LogStore) {
        if (early.isEmpty()) return
        try {
            early.forEach { (time, message) -> target.insert(message, time) }
            target.trim(retention)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dropped += early.size
        }
        early.clear()
    }
}

/** 日志里写异常：Koin 等外层包装只说「建不出来」，真正的原因在最里层 */
internal fun Throwable.rootCause(): String {
    val root = generateSequence(this) { it.cause }.last()
    val where = root.stackTraceToString().lineSequence().drop(1).take(4).joinToString(" ") { it.trim() }
    return if (root === this) "$this $where" else "$this, caused by $root $where"
}

/**
 * 「最近一次异常」的种类（自检页按它出中文说明）：
 * [RESCUE] 看门狗出手（重建定位源、重建跟踪、报告警），[DEGRADED] 降级（高德切系统定位），[STORAGE] 写不进存储，
 * [DROPPED] 满了丢掉最旧的（离线队列、告警），[CRASH] 组件里有没接住的异常
 */
enum class Fault { RESCUE, DEGRADED, STORAGE, DROPPED, CRASH }
