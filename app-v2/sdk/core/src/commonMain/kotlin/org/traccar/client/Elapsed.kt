package org.traccar.client

import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * 开机以来的毫秒数，不受改系统时间影响：看「过了多久」都用它（保活的多久没上报、看门狗的升级间隔、引擎发现系统时间被改）。
 * Android 启动时换成 `SystemClock.elapsedRealtime()`（手机睡着也在走）；默认的单调时钟睡着时可能不走，只给测试和 iOS 用。
 */
internal object Elapsed {
    private val start = TimeSource.Monotonic.markNow()

    @Volatile
    var source: () -> Long = { start.elapsedNow().inWholeMilliseconds }

    fun now(): Long = source()
}
