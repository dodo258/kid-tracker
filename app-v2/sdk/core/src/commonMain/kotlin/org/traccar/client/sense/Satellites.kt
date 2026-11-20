package org.traccar.client.sense

/**
 * 一次卫星状态（方案 docs/specs/2026-11-19-sky-quality.md 第 8 节第 1 条，只采数据）：参与定位的卫星数、最强 4 颗的平均载噪比。
 * 系统的卫星状态回调给全部两样；只从定位点附带的卫星数拿到时 [cn0Top4] 是 null。
 */
data class SatelliteStatus(val used: Int, val cn0Top4: Double?) {
    companion object {
        /**
         * [cn0] 是每颗在跟的卫星的载噪比（dB-Hz，没信号是 0），[used] 是它参与没参与这次定位，两个下标对齐。
         * 平均取信号最强的 4 颗（不到 4 颗有信号就平均有的那几颗，一颗都没有是 null）。
         */
        fun of(cn0: DoubleArray, used: BooleanArray): SatelliteStatus {
            val top = cn0.filter { it > 0 && it.isFinite() }.sortedDescending().take(TOP)
            return SatelliteStatus(
                used = used.count { it },
                cn0Top4 = if (top.isEmpty()) null else top.average(),
            )
        }

        private const val TOP = 4
    }
}

/**
 * 最近收到的卫星状态，给每一窗快照挑一份。加速度批量上报，一批来时一次切出几窗以前的快照，所以按时间戳挑，不拿「现在」的。
 * 卫星开着时系统约 1 秒给一次，最多留 [CAPACITY] 份（够覆盖一批 10 秒）；卫星停了（[clear]）就不再给旧的。
 * 只在采样线程上用。
 */
class SatelliteLog {
    private val entries = ArrayDeque<Pair<Long, SatelliteStatus>>()

    /** [nanos] 是收到时的开机时长（纳秒，和传感器事件的时间戳同一个钟） */
    fun add(nanos: Long, status: SatelliteStatus) {
        entries.addLast(nanos to status)
        while (entries.size > CAPACITY) entries.removeFirst()
    }

    fun clear() = entries.clear()

    /** 结束于 [endNanos] 的那一窗用的状态：[MAX_AGE_NANOS] 内收到的最新一份，带载噪比的优先；没有就是 null */
    fun at(endNanos: Long): SatelliteStatus? {
        val recent = entries.filter { (t, _) -> t <= endNanos && endNanos - t <= MAX_AGE_NANOS }
        return (recent.lastOrNull { it.second.cn0Top4 != null } ?: recent.lastOrNull())?.second
    }

    companion object {
        const val CAPACITY = 32
        /** 一窗 2 秒，加 1 秒余量：回调约 1 秒一次，偶尔晚到 */
        const val MAX_AGE_NANOS = 3_000_000_000L
    }
}
