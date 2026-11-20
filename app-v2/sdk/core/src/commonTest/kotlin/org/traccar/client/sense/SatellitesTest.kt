package org.traccar.client.sense

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SatellitesTest {

    @Test
    fun statusCountsUsedAndAveragesTheStrongestFour() {
        val s = SatelliteStatus.of(
            cn0 = doubleArrayOf(18.0, 35.0, 0.0, 31.0, 40.0, 22.0, 28.0),
            used = booleanArrayOf(false, true, false, true, true, false, true),
        )
        assertEquals(4, s.used)
        assertEquals((40.0 + 35.0 + 31.0 + 28.0) / 4, s.cn0Top4!!, 1e-9)
    }

    @Test
    fun fewerThanFourSignalsAverageWhatThereIs() {
        val s = SatelliteStatus.of(doubleArrayOf(0.0, 24.0, 20.0), BooleanArray(3))
        assertEquals(0, s.used)
        assertEquals(22.0, s.cn0Top4!!, 1e-9)
        val none = SatelliteStatus.of(doubleArrayOf(0.0, 0.0), BooleanArray(2))
        assertNull(none.cn0Top4, "在跟的卫星一颗信号都没有")
        assertNull(SatelliteStatus.of(DoubleArray(0), BooleanArray(0)).cn0Top4)
    }

    @Test
    fun windowTakesTheLatestRecentStatusPreferringOnesWithSignal() {
        val log = SatelliteLog()
        val s = 1_000_000_000L
        log.add(10 * s, SatelliteStatus(6, 30.0))
        log.add(11 * s, SatelliteStatus(7, 31.0))
        log.add(12 * s, SatelliteStatus(7, null)) // 定位点附带的卫星数，晚一点到
        assertEquals(SatelliteStatus(7, 31.0), log.at(12 * s), "带载噪比的优先")
        assertEquals(SatelliteStatus(6, 30.0), log.at(10 * s + s / 2), "批量切的旧窗口用它那时的，不用后来的")
        assertNull(log.at(9 * s), "这一窗之前没收到过")
        assertEquals(SatelliteStatus(7, null), log.at(14 * s + s / 2), "带载噪比的超过 3 秒了，只剩卫星数")
        assertNull(log.at(16 * s), "3 秒内都没有：卫星停了或系统没给")
        log.clear()
        assertNull(log.at(12 * s), "卫星停了就清掉")
    }

    @Test
    fun logKeepsAtMostCapacityEntries() {
        val log = SatelliteLog()
        val s = 1_000_000_000L
        repeat(SatelliteLog.CAPACITY + 10) { log.add(it * s / 10, SatelliteStatus(it, 20.0)) }
        // 最早的 10 份已经挤掉了：时间点 0.5 秒那一窗找不到 0 到 0.5 秒的
        assertNull(log.at(s / 2))
        assertEquals(SatelliteStatus(SatelliteLog.CAPACITY + 9, 20.0), log.at((SatelliteLog.CAPACITY + 9) * s / 10))
    }
}
