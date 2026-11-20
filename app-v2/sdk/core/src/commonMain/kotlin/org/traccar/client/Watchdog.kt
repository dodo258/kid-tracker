package org.traccar.client

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest

/** 看门狗这一次出什么手 */
internal enum class Rescue {
    /** 第一级：只重建定位源（取消卡住的取点、销毁高德客户端，重新从高德开始） */
    LOCATION,

    /** 第二级：按同样配置重建整套跟踪（定位源、引擎、保活、告警发件箱），等于关开一次追踪 */
    TRACKER,

    /** 第三级：报一条告警（alarm=fault）让家长知道，再重建整套 */
    ALERT,
}

/**
 * 看门狗的升级规则（方案「可靠性要求」第 4 条，纯逻辑）：卡住了（取点连续超时、呼吸连续没接住、很久没产出上报）先用最轻的办法，
 * 上一次出手后 [calmMillis] 内又卡住就升一级：重建定位源 → 重建整套跟踪 → 报告警并重建。告警一轮只报一次，之后一直重建整套；
 * 安稳 [calmMillis] 后从第一级重新来。时间用开机以来的单调时间（改系统时间不影响）。
 */
internal class Ladder(private val calmMillis: Long = CALM_MILLIS) {
    private var level = 0
    private var lastAt: Long? = null
    private var alerted = false

    fun next(now: Long): Rescue {
        val last = lastAt
        if (last == null || now - last >= calmMillis) {
            level = 0
            alerted = false
        }
        lastAt = now
        level = (level + 1).coerceAtMost(3)
        return when {
            level == 1 -> Rescue.LOCATION
            level == 2 || alerted -> Rescue.TRACKER
            else -> Rescue.ALERT.also { alerted = true }
        }
    }

    companion object {
        /** 第一级之后取点还会连着超时三次（屋里 5 分钟一次要 15 分钟），呼吸没接住三次要 3 分钟：30 分钟内再卡住算同一件事 */
        const val CALM_MILLIS = 30 * 60_000L
    }
}

/**
 * 能单独重建的定位源（看门狗第一级）：[create] 在一个子作用域里建出定位源，[rebuild] 取消旧的子作用域（卡住的取点、
 * 连续定位、高德客户端跟着停掉、销毁）再建一个新的；引擎收的点跟着换过去，不用重建引擎。
 */
internal class RebuildableLocationSource(
    private val parent: ComponentCoroutineScope,
    private val create: (ComponentCoroutineScope) -> LocationSource,
) : LocationSource {

    private class Built(val scope: ComponentCoroutineScope, val source: LocationSource)

    private val current = MutableStateFlow(build())

    @OptIn(ExperimentalCoroutinesApi::class)
    override val positions: Flow<Position> = current.flatMapLatest { it.source.positions }

    override suspend fun fetchOnce(): Position? = current.value.source.fetchOnce()

    override suspend fun fetchNetworkOnce(): Position? = current.value.source.fetchNetworkOnce()

    fun rebuild() {
        val old = current.value
        current.value = build()
        old.scope.cancel()
    }

    private fun build(): Built {
        val scope = ComponentCoroutineScope(parent)
        return Built(scope, create(scope))
    }
}
