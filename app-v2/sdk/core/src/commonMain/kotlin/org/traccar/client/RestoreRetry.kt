package org.traccar.client

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * 开机、闹钟叫起进程时恢复跟踪失败（读不出配置、状态：存储暂时出错）后再试的间隔：[first] 起翻倍，最多 [max]；恢复成功清零。
 * 「确实没配置」「追踪关着」不算失败，不重试。
 *
 * 不加会怎样（审计高 3）：重启清掉了原来的闹钟，锁屏开机那次恢复读库失败只记一条日志就完了，前台服务、呼吸闹钟都还没建，
 * 孩子不解锁就再没有入口把追踪拉起来。重试用呼吸闹钟（Direct Boot 下能响），响了照常走恢复。
 */
internal class RestoreRetry(private val first: Long = 60_000L, private val max: Long = 900_000L) {
    // 开机广播、呼吸闹钟可能同时来：原子更新
    private val attempts = MutableStateFlow(0)

    /** 又失败了一次：返回过多久再试 */
    fun failed(): Long {
        val before = attempts.getAndUpdate { it + 1 }
        return (first shl before.coerceAtMost(MAX_SHIFT)).coerceAtMost(max)
    }

    fun succeeded() = attempts.update { 0 }

    private companion object {
        const val MAX_SHIFT = 20
    }
}
