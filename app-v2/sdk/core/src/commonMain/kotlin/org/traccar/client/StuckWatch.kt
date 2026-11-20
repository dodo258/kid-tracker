package org.traccar.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/** 连续这么多次卡住（取点超时、呼吸闹钟等不到引擎处理完）就重建跟踪组件 */
internal const val STUCK_LIMIT = 3

/**
 * 数连续卡住的次数：第 [limit] 次 [stuck] 调 [onStuck]（平台重建跟踪）并重新计数；中间有一次正常（[ok]）就清零。
 * 取点可能并发（引擎的网络定位、App 的「发送位置」），计数用原子更新。
 *
 * 原版没有：原版的定位源自己设时限，假设它们一定会回来。不加会怎样：P20 2026-11-18 定位源卡住后关开追踪也救不回来
 * （docs/reports/2026-11-18-p20-zone-fix-hang.md），只能等主人发现、手动重启 App。
 */
internal class StuckWatch(
    private val what: String,
    private val limit: Int = STUCK_LIMIT,
    private val onStuck: (reason: String) -> Unit,
) {
    private val count = MutableStateFlow(0)

    fun stuck() {
        val before = count.getAndUpdate { if (it + 1 >= limit) 0 else it + 1 }
        if (before + 1 < limit) return
        val reason = "$what $limit times in a row"
        Log.log("Tracking stuck: $reason, rebuilding tracker")
        onStuck(reason)
    }

    fun ok() = count.update { 0 }
}
