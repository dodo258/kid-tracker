package org.traccar.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 主定位源（高德）为主，[ProviderFallbackPolicy] 判定它不行了就在这一段追踪里改用兜底源（系统定位）；关掉追踪算一段结束，
 * 下次开追踪再先试主定位源。主定位源连建都建不起来（高德初始化抛异常）时这次一直用兜底源。
 *
 * 两个源都只建一次，各看一份自己的 [state]：没在用的那个看到的是「追踪关着」，停掉自己的连续定位；切换只是翻 [usingFallback]。
 *
 * 原版没有这层：原版只有一个定位源（有 Google 服务用 Fused，否则系统定位）。不加会怎样：高德 Key 配错、SDK 坏了、连续报错时
 * 一直拿不到点；直接只用系统定位，在没有 Google 服务的手机上室内只能靠 GPS，屋里基本没点。
 */
internal class FallbackLocationSource(
    scope: CoroutineScope,
    private val state: StateFlow<State>,
    private val policy: ProviderFallbackPolicy,
    private val isOnline: StateFlow<Boolean>,
    private val primaryName: String,
    private val fallbackName: String,
    createPrimary: (state: StateFlow<State>, onResult: (errorCode: Int) -> Unit) -> LocationSource,
    createFallback: (state: StateFlow<State>) -> LocationSource,
) : LocationSource {

    private val usingFallback = MutableStateFlow(false)

    private val primary: LocationSource? = try {
        createPrimary(scope.gatedState(state, usingFallback) { !it }, ::onPrimaryResult).also {
            Log.log("Using $primaryName, fallback $fallbackName")
        }
    } catch (e: Exception) {
        Log.fault(Fault.DEGRADED, "$primaryName init failed, using $fallbackName: $e")
        usingFallback.value = true
        null
    }
    private val fallback = createFallback(scope.gatedState(state, usingFallback) { it })

    override val positions: Flow<Position> = merge(primary?.positions ?: emptyFlow(), fallback.positions)

    init {
        if (primary != null) {
            scope.launch {
                state.map { it.enabled }.distinctUntilChanged().collect { enabled ->
                    if (!enabled) endSession()
                }
            }
        }
    }

    // 单次取点（App 里「发送位置」、心跳）：主定位源没拿到点就问兜底源一次
    override suspend fun fetchOnce(): Position? {
        if (!usingFallback.value) primary?.fetchOnce()?.let { return it }
        return fallback.fetchOnce()
    }

    override suspend fun fetchNetworkOnce(): Position? {
        if (!usingFallback.value) primary?.fetchNetworkOnce()?.let { return it }
        return fallback.fetchNetworkOnce()
    }

    // 高德在主线程回调，结束一段追踪在协程里：共用一把锁
    private fun onPrimaryResult(errorCode: Int): Unit = synchronized(policy) {
        // 追踪关着时的取点是一次性的，不影响下一段追踪
        if (!state.value.enabled) return
        val reason = policy.onResult(errorCode, isOnline.value) ?: return
        val why = if (reason.fatal) {
            "fatal $primaryName error ${reason.errorCode}"
        } else {
            "${reason.consecutiveErrors} $primaryName errors in a row, last ${reason.errorCode}"
        }
        Log.fault(Fault.DEGRADED, "location fallback: $why, using $fallbackName until tracking restarts")
        usingFallback.value = true
    }

    private fun endSession(): Unit = synchronized(policy) {
        if (usingFallback.value) Log.log("Location fallback: session ended, next start tries $primaryName again")
        policy.onSessionEnd()
        usingFallback.value = false
    }
}

/**
 * [state] 按 [flag] 开关后的样子：[active] 为假时追踪读成关着，组件停掉自己在跑的东西，[flag] 翻回来再开。
 */
internal fun CoroutineScope.gatedState(
    state: StateFlow<State>,
    flag: StateFlow<Boolean>,
    active: (Boolean) -> Boolean,
): StateFlow<State> {
    fun gate(real: State, value: Boolean) = if (active(value)) real else real.copy(enabled = false)
    return combine(state, flag, ::gate).stateIn(this, SharingStarted.Eagerly, gate(state.value, flag.value))
}
