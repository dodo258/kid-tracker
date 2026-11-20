package org.traccar.client

import com.amap.api.location.AMapLocation
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/** 高德报错切系统定位：用真实的高德错误码和 [amapFallbackPolicy]，两个定位源换成假的。 */
class FallbackLocationSourceTest {

    private val scope = ComponentCoroutineScope()
    private val state = MutableStateFlow(State(enabled = true))
    private val online = MutableStateFlow(true)
    private val amapFix = Position(latitude = 30.27, longitude = 120.13, accuracy = 30.0, time = 1L)
    private val systemFix = Position(latitude = 30.28, longitude = 120.14, accuracy = 8.0, time = 2L)

    private class FakeSource(var fix: Position?) : LocationSource {
        lateinit var state: StateFlow<State>
        var onResult: (Int) -> Unit = {}
        var fetches = 0
        override val positions = MutableSharedFlow<Position>(extraBufferCapacity = 8)
        override suspend fun fetchOnce(): Position? {
            fetches++
            return fix
        }
    }

    private val amap = FakeSource(amapFix)
    private val system = FakeSource(systemFix)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun build(amapThrows: Boolean = false) = FallbackLocationSource(
        scope = scope,
        state = state,
        policy = amapFallbackPolicy(),
        isOnline = online,
        primaryName = "AMap",
        fallbackName = "system location",
        createPrimary = { gated, onResult ->
            if (amapThrows) throw IllegalStateException("AMap init failed")
            amap.also { it.state = gated; it.onResult = onResult }
        },
        createFallback = { gated -> system.also { it.state = gated } },
    )

    /** 门控后的状态是异步算出来的，等它跟上 */
    private suspend fun awaitTracking(source: FakeSource, enabled: Boolean) =
        withTimeout(2.seconds) { source.state.first { it.enabled == enabled } }

    @Test
    fun amapErrorsInARowSwitchToSystemLocation(): Unit = runBlocking {
        val source = build()
        awaitTracking(amap, true)
        awaitTracking(system, false)
        repeat(4) { amap.onResult(AMapLocation.ERROR_CODE_FAILURE_LOCATION) }
        assertEquals(amapFix, source.fetchOnce())

        amap.onResult(AMapLocation.ERROR_CODE_FAILURE_LOCATION)
        // 高德停连续定位、系统定位开始；单次取点只问系统定位
        awaitTracking(amap, false)
        awaitTracking(system, true)
        val fetchesBefore = amap.fetches
        assertEquals(systemFix, source.fetchOnce())
        assertEquals(fetchesBefore, amap.fetches)

        // 连续定位的点从系统定位来
        val next = async { withTimeout(2.seconds) { source.positions.first() } }
        yield()
        while (!next.isCompleted) {
            system.positions.emit(systemFix)
            kotlinx.coroutines.delay(20)
        }
        assertEquals(systemFix, next.await())
    }

    @Test
    fun badKeySwitchesAtOnce(): Unit = runBlocking {
        val source = build()
        awaitTracking(amap, true)
        amap.onResult(AMapLocation.ERROR_CODE_FAILURE_AUTH)
        awaitTracking(system, true)
        assertEquals(systemFix, source.fetchOnce())
    }

    @Test
    fun environmentAndOfflineErrorsDoNotSwitch(): Unit = runBlocking {
        build()
        awaitTracking(amap, true)
        online.value = false
        repeat(20) {
            amap.onResult(AMapLocation.ERROR_CODE_FAILURE_CONNECTION)
            amap.onResult(AMapLocation.ERROR_CODE_AIRPLANEMODE_WIFIOFF)
            amap.onResult(AMapLocation.ERROR_CODE_NOCGI_WIFIOFF)
            amap.onResult(AMapLocation.ERROR_CODE_FAILURE_NOWIFIANDAP)
        }
        // 切了的话门控状态是异步跟上的，多等一会儿再看
        kotlinx.coroutines.delay(200)
        assertTrue(amap.state.value.enabled)
        assertFalse(system.state.value.enabled)
    }

    @Test
    fun nextTrackingSessionTriesAmapAgain(): Unit = runBlocking {
        build()
        awaitTracking(amap, true)
        amap.onResult(AMapLocation.ERROR_CODE_FAILURE_AUTH)
        awaitTracking(system, true)
        state.value = State(enabled = false)
        awaitTracking(system, false)
        state.value = State(enabled = true)
        awaitTracking(amap, true)
        awaitTracking(system, false)
    }

    @Test
    fun amapWithoutFixAsksSystemOnce(): Unit = runBlocking {
        val source = build()
        amap.fix = null
        assertEquals(systemFix, source.fetchOnce())
        assertEquals(1, system.fetches)
        system.fix = null
        assertNull(source.fetchOnce())
    }

    @Test
    fun amapThatCannotStartUsesSystemLocation(): Unit = runBlocking {
        val source = build(amapThrows = true)
        awaitTracking(system, true)
        assertEquals(systemFix, source.fetchOnce())
    }

    @Test
    fun errorsWhileTrackingIsOffDoNotSwitch(): Unit = runBlocking {
        state.value = State(enabled = false)
        val source = build()
        repeat(10) { amap.onResult(AMapLocation.ERROR_CODE_FAILURE_AUTH) }
        state.value = State(enabled = true)
        awaitTracking(amap, true)
        assertEquals(amapFix, source.fetchOnce())
    }
}
