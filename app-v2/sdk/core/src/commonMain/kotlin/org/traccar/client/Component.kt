package org.traccar.client

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.traccar.client.sense.Snapshot

internal val LOCATION_FETCH_TIMEOUT: Duration = 30.seconds

interface LocationSource {
    val positions: Flow<Position>
    suspend fun fetchOnce(): Position?

    /** 只用网络定位（Wi-Fi、基站）取一次，不开卫星：屋里、静止时判断层要的点。定位源分不出来就和 [fetchOnce] 一样 */
    suspend fun fetchNetworkOnce(): Position? = fetchOnce()
}

/**
 * 传感器快照（sense/）：约 2 秒一份，批量来时一次几份。[sleeping] 为真时（静止、飞行模式）只留硬件唤醒（显著运动），
 * 叫醒了自己接着出快照；追踪开着才采。
 */
interface SensorSource {
    val snapshots: Flow<Snapshot>
    fun setSleeping(sleeping: Boolean)
}

internal enum class LocationMode { Off, Active, Stationary }

internal fun State.locationMode(): LocationMode = when {
    !enabled -> LocationMode.Off
    paused -> LocationMode.Stationary
    else -> LocationMode.Active
}

interface SignalSource {
    val signals: Flow<Signal>
}

fun interface PositionProcessor {
    suspend fun process(position: Position): Position?
}

/**
 * 跟踪组件的作用域（改设置、看门狗重建时整个取消）。[parent] 不为空时是它的子作用域（看门狗只重建定位源时用）。
 *
 * 比原版多一个异常处理：作用域里哪个协程抛了没接住的异常（比如存储满了写库失败），原版没有处理器，异常交给线程的默认处理，
 * Android 上整个进程崩掉，重启后再写还崩；现在只记一条异常（自检页看得到）、交给看门狗升级处理（[rescueSharedTracker]），进程不崩。
 */
class ComponentCoroutineScope(parent: ComponentCoroutineScope? = null) : CoroutineScope {
    private val root: ComponentCoroutineScope = parent?.root ?: this

    override val coroutineContext: CoroutineContext = SupervisorJob(parent?.coroutineContext?.get(Job)) + Dispatchers.Default +
        CoroutineExceptionHandler { _, e ->
            Log.fault(Fault.CRASH, "component failed: ${e.rootCause()}")
            // 不知道停的是哪一个循环：直接重建整套，不先只重建定位源
            rescueSharedTracker(root, "component failed: $e", wholeTracker = true)
        }
}

internal fun <T> CoroutineScope.observeState(
    state: StateFlow<State>,
    select: (State) -> T,
    inactive: T,
    block: suspend (T) -> Unit,
): Job = launch {
    try {
        state.map(select).distinctUntilChanged().collectLatest(block)
    } finally {
        block(inactive)
    }
}

/**
 * 登记一个系统回调（网络回调这类），[scope]（组件作用域）结束时撤掉：改设置、解锁、看门狗重建组件时不留下旧的回调
 * （审计中 5：每重建一次漏一个网络回调，多了会用完系统给每个 App 的配额，新的一套注册失败）。作用域已经结束就马上撤。
 */
internal inline fun CoroutineScope.registerWhileActive(register: () -> Unit, crossinline unregister: () -> Unit) {
    register()
    coroutineContext[Job]?.invokeOnCompletion { unregister() }
}
