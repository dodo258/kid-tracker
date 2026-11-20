package org.traccar.client

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/** 定位源自己的单次取点时限（[LOCATION_FETCH_TIMEOUT]）之外再给的余量，过了就不等它 */
internal val FETCH_GUARD_MARGIN: Duration = 10.seconds

/** 定位源单次取点等到自己的时限都没有回调（不是「回调了、没定到」）：看门狗按卡住数（审计高 6） */
internal class LocationNoAnswer(message: String) : Exception(message)

/**
 * 定位源等单次取点的回调，最多 [timeout]：回调了（有点、或者 null 表示没定到）就返回；到点没回调抛 [LocationNoAnswer]，
 * 让 [BoundedLocationSource] 分得清「正常没点」和「定位源不理人」。
 */
internal suspend fun <T> awaitAnswer(name: String, timeout: Duration, callback: suspend () -> T): T {
    val answer = withTimeoutOrNull(timeout) { Answer(callback()) } ?: throw LocationNoAnswer("$name: no answer in $timeout")
    return answer.value
}

private class Answer<T>(val value: T)

/**
 * 单次取点的护栏：在 [scope]（组件作用域）里另起协程向 [delegate] 取点，最多等 [limit]；到点取消那次取点、不等它收尾，
 * 当这次没拿到点。
 *
 * 原版没有这层：它的定位源都自己设了时限，引擎直接调。不加会怎样：定位源只要有一处不理取消（P20 2026-11-18：Fused 单次定位
 * 被取消后永远不回调，见 docs/reports/2026-11-18-p20-zone-fix-hang.md），引擎就拿着锁一直等，之后每个心跳都排在后面，
 * 一整夜不上报。被放弃的取点留在 [scope] 里，追踪停止或改设置时随作用域一起取消。
 *
 * 超时（这里到点、或者定位源自己说没回调 [LocationNoAnswer]）和按时回来（拿没拿到点都算）报给看门狗 [watch]：连续超时
 * [STUCK_LIMIT] 次就重建跟踪，换一套全新的定位源。每个定位源一个 [watch]（高德、系统定位分开数），备用源按时回来不清主源的数。
 */
internal class BoundedLocationSource(
    private val delegate: LocationSource,
    private val scope: CoroutineScope,
    private val name: String,
    private val limit: Duration = LOCATION_FETCH_TIMEOUT + FETCH_GUARD_MARGIN,
    private val watch: StuckWatch? = null,
) : LocationSource {

    override val positions = delegate.positions

    override suspend fun fetchOnce(): Position? = bounded { delegate.fetchOnce() }

    override suspend fun fetchNetworkOnce(): Position? = bounded { delegate.fetchNetworkOnce() }

    private suspend fun bounded(get: suspend () -> Position?): Position? {
        val fetch = scope.async { get() }
        try {
            val done = withTimeoutOrNull(limit) { fetch.await() to true }
            if (done != null) {
                watch?.ok()
                return done.first
            }
            Log.log("$name: no fix in $limit, cancelled")
            watch?.stuck()
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: LocationNoAnswer) {
            Log.log(e.message ?: "$name: no answer")
            watch?.stuck()
            return null
        } catch (e: Exception) {
            Log.log("$name: fetch failed: $e")
            watch?.ok()
            return null
        } finally {
            fetch.cancel()
        }
    }
}
