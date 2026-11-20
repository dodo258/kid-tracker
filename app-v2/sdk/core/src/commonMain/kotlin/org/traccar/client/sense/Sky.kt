package org.traccar.client.sense

/**
 * 卫星点头顶的天空，按系统给的精度分三档（docs/specs/2026-11-19-sky-quality.md 第 3 节；P20 屋里开卫星给的点误差 200 m～5.8 km）：
 * - [OPEN] ≤ [OPEN_ACCURACY]：测速、当主坐标、学锚点。
 * - [PARTIAL] 到 [BLOCKED_ACCURACY]：在路上照样当主坐标，但不拿单点速度判坐车、判没在走，不学锚点。
 * - [BLOCKED] 更差：不当主坐标、不测速，只说明头顶被挡（策略那边当作没搜到）。
 * 没有精度的（旧录制）当开阔，和以前一样。
 */
enum class Sky {
    OPEN,
    PARTIAL,
    BLOCKED,
    ;

    companion object {
        const val OPEN_ACCURACY = 50.0
        const val BLOCKED_ACCURACY = 300.0

        fun of(accuracy: Double?): Sky = when {
            accuracy == null || accuracy <= OPEN_ACCURACY -> OPEN
            accuracy <= BLOCKED_ACCURACY -> PARTIAL
            else -> BLOCKED
        }

        /**
         * 卫星点能不能当主坐标：头顶被挡的（[BLOCKED]）不能。普通上报、告警缓存、求救现取、App 里「发送位置」都按这一条
         * （审计 11.0.11 第 1 条：以前只有普通上报挡，求救会带着误差 5 公里的点当主坐标）
         */
        fun mainCoordinate(accuracy: Double?): Boolean = of(accuracy) != BLOCKED
    }
}
