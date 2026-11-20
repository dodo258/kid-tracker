package org.traccar.client.sense

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotCsvTest {

    @Test
    fun localTimeConvertsBothWays() {
        // 期望值用 Python datetime 算的
        val cases = listOf(
            Triple("2026-11-17 22:50:50", 480, 1_794_927_050_000L),
            Triple("2028-02-29 23:59:59", 480, 1_835_452_799_000L),
            Triple("2000-01-01 07:59:59", 480, 946_684_799_000L),
            Triple("1969-12-31 23:00:00", 0, -3_600_000L),
        )
        for ((text, offset, millis) in cases) {
            assertEquals(millis, SnapshotCsv.parseTime(text, offset), text)
            assertEquals(text, SnapshotCsv.formatTime(millis, offset), text)
        }
        assertEquals("2026-11-17 22:50:50", SnapshotCsv.formatTime(1_794_927_050_999L, 480), "毫秒不进位")
        assertNull(SnapshotCsv.parseTime("2026-11-17", 480))
        assertNull(SnapshotCsv.parseTime("", 480))
    }

    @Test
    fun numbersHaveFixedDecimals() {
        assertEquals("0.007", SnapshotCsv.num(0.0071, 3))
        assertEquals("-0.306", SnapshotCsv.num(-0.3061, 3))
        assertEquals("0.000", SnapshotCsv.num(-0.0001, 3), "四舍五入成 0 时不带负号")
        assertEquals("286", SnapshotCsv.num(285.7, 0))
        assertEquals("1.00", SnapshotCsv.num(1.0, 2))
        assertEquals("", SnapshotCsv.num(Double.NaN, 2))
        assertEquals("", SnapshotCsv.num(null, 2))
    }

    @Test
    fun headerKeepsTheRecorderColumnsAndAddsNewOnesAtTheEnd() {
        // 2026-11-17 起 App 录制文件的表头；新列只往后加，按位置读的旧工具（tools/simulator）不受影响
        val recorder = "time,screen,charging,battery,airplane,acc_mean,acc_std,acc_max,acc_n,gyro_mean,gyro_max,heading,heading_n," +
            "steps_total,step_events,proximity,light,pressure,net,wifi_bssid,wifi_ssid,wifi_rssi,wifi_metered,scan_n,scan_overlap," +
            "fix_provider,fix_time,fix_lat,fix_lon,fix_acc,fix_speed,fix_bearing"
        assertEquals(
            "$recorder,acc_peaks,wifi_gateway,cell_tac,cell_id,sat_used,cn0_top4,mag_std,cell_dbm,net_link,elapsed,fix_elapsed",
            SnapshotCsv.HEADER,
        )
    }

    @Test
    fun snapshotSurvivesARoundTrip() {
        val snapshot = Snapshot(
            time = 1_794_927_050_000L,
            screen = true, charging = false, battery = 82, airplane = false,
            accMean = 0.415, accStd = 0.069, accMax = 0.671, accN = 51, accPeaks = 3,
            gyroMean = 0.011, gyroMax = 0.063, heading = 286.0, headingN = 100,
            stepsTotal = 816, stepEvents = 1, proximity = 5.0, light = 6.0, pressure = 1013.25,
            net = Snapshot.Net.WIFI, wifiBssid = "aa:bb:cc:dd:ee:ff", wifiSsid = "Home, \"5G\"", wifiRssi = -56, wifiMetered = false,
            wifiGateway = "172.20.10.1", scanN = 10, scanOverlap = 0.5,
            fix = Snapshot.Fix("gps", 1_794_927_048_000L, 30.262836, 120.136198, 8.0, 6.2, 155.0, elapsed = 86_398_000L),
            cellTac = 22_801, cellId = 68_719_476_735L,
            satUsed = 9, cn0Top4 = 31.3, magStd = 0.842, cellDbm = -97,
            netLink = 4, elapsed = 86_400_000L,
        )
        val line = SnapshotCsv.format(snapshot, 480)
        assertTrue(line.startsWith("2026-11-17 22:50:50,1,0,82,0,0.415,0.069,0.671,51,"), line)
        assertTrue(line.endsWith(",22801,68719476735,9,31.3,0.842,-97,4,86400000,86398000"), line)
        val parsed = SnapshotCsv.parse(sequenceOf(SnapshotCsv.HEADER, line), 480).single()
        assertEquals(snapshot, parsed)
        assertTrue(parsed.onFixedWifi)
        assertEquals(6.2, parsed.freshGps(10_000)?.speed)
        assertNull(parsed.freshGps(1_000), "两秒前的点不算一秒内的新点")
    }

    @Test
    fun oldRecordingWithoutPeaksAndEmptyCellsParses() {
        val old = "time,screen,charging,battery,airplane,acc_mean,acc_std,acc_max,acc_n,gyro_mean,gyro_max,heading,heading_n," +
            "steps_total,step_events,proximity,light,pressure,net,wifi_bssid,wifi_ssid,wifi_rssi,wifi_metered,scan_n,scan_overlap," +
            "fix_provider,fix_time,fix_lat,fix_lon,fix_acc,fix_speed,fix_bearing"
        val rows = sequenceOf(
            old,
            "2026-11-18 10:03:09,0,0,95,0,-0.306,0.407,1.066,51,0.154,0.259,45,100,2565,0,0.0,0,,none,,,,,0,,network,2026-11-18 09:33:49,,,36,,",
            "2026-11-18 10:03:11,0,0,95,0,-0.296",
            "2026-11-18 10:0",
        )
        val parsed = SnapshotCsv.parse(rows, 480).toList()
        assertEquals(2, parsed.size, "写了一半的最后一行跳过")
        val first = parsed[0]
        assertNull(first.accPeaks)
        assertEquals(Snapshot.Net.NONE, first.net)
        assertNull(first.wifiMetered)
        assertEquals(2565L, first.stepsTotal)
        assertEquals("network", first.fix?.provider)
        assertNull(first.fix?.latitude)
        assertNull(first.fix?.speed)
        assertNull(first.cellId, "旧录制没有基站列")
        assertNull(first.satUsed, "旧录制没有卫星、磁场、基站信号列")
        assertNull(first.cn0Top4)
        assertNull(first.magStd)
        assertNull(first.cellDbm)
        assertNull(first.netLink, "旧录制没有连接代次、开机时长")
        assertNull(first.elapsed)
        assertNull(first.freshGps(10_000), "网络定位不算卫星")
        assertEquals(0, parsed[1].accN)
        assertNull(parsed[1].accStd)
        assertNull(parsed[1].fix)
    }

    @Test
    fun emptySkyColumnsStayNull() {
        // 卫星没开、手机没卡时这几列写空，读回来还是 null，不变成 0
        val snapshot = Snapshot(time = 1_794_927_050_000L, magStd = 0.0)
        val line = SnapshotCsv.format(snapshot, 480)
        assertTrue(line.endsWith(",,,0.000,,,,"), line)
        val parsed = SnapshotCsv.parse(sequenceOf(SnapshotCsv.HEADER, line), 480).single()
        assertEquals(snapshot, parsed)
    }

    @Test
    fun meteredWifiIsNotFixed() {
        assertTrue(!Snapshot(time = 0, net = Snapshot.Net.WIFI, wifiMetered = true).onFixedWifi)
        assertTrue(!Snapshot(time = 0, net = Snapshot.Net.CELL).onFixedWifi)
        assertTrue(Snapshot(time = 0, net = Snapshot.Net.WIFI, wifiMetered = null).onFixedWifi, "读不到是否计费时当固定")
    }

    @Test
    fun freshnessUsesElapsedWhenBothSidesHaveIt() {
        // 审计 11.0.11 第 6 条：系统时间被改快一天、卫星时间照常，两秒前的点按开机时长看还是新的
        val fix = Snapshot.Fix("gps", 1_000_000L, 30.0, 120.0, 8.0, 12.0, elapsed = 50_000L)
        val s = Snapshot(time = 1_000_000L + 86_400_000L + 2_000L, elapsed = 52_000L, fix = fix)
        assertEquals(12.0, s.freshGps(10_000)?.speed)
        assertEquals(52_000L, s.clock)
        assertNull(s.copy(elapsed = null).freshGps(10_000), "没有开机时长只能按时间比：一天前的点")
    }
}
