package org.traccar.client.alert

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.traccar.client.Position
import org.traccar.client.Provider

class AlertRulesTest {

    private val now = 1_800_000_000_000L

    private fun fix(ageMillis: Long, provider: String? = Provider.GPS) =
        Position(latitude = 30.0, longitude = 120.0, accuracy = 5.0, time = now - ageMillis, provider = provider)

    @Test
    fun freshSatelliteFixIsUsed() {
        val f = fix(3_000)
        assertSame(f, freshGpsFix(f, now))
        val edge = fix(ALERT_FIX_FRESH)
        assertSame(edge, freshGpsFix(edge, now))
    }

    @Test
    fun oldSatelliteFixIsNotUsed() {
        // 审计 M4：昨天的点不能配上今天的告警
        assertNull(freshGpsFix(fix(ALERT_FIX_FRESH + 1), now))
        assertNull(freshGpsFix(fix(24 * 3_600_000L), now))
    }

    @Test
    fun networkFixesAreNotUsed() {
        assertNull(freshGpsFix(fix(1_000, Provider.WIFI), now))
        assertNull(freshGpsFix(fix(1_000, Provider.CELL), now))
        assertNull(freshGpsFix(fix(1_000, Provider.NETWORK), now))
        assertNull(freshGpsFix(fix(1_000, null), now))
    }

    @Test
    fun fixFromTheFutureIsNotTrusted() {
        // 系统时间被往回改过：定位时间比现在晚很多
        assertNull(freshGpsFix(fix(-(ALERT_FIX_SKEW + 1)), now))
        assertEquals(now + 2_000, freshGpsFix(fix(-2_000), now)?.time)
    }

    @Test
    fun noCoordinatesNoFix() {
        assertNull(freshGpsFix(Position(time = now, provider = Provider.GPS), now))
        assertNull(freshGpsFix(null, now))
    }

    @Test
    fun lowBatteryReportsOncePerDischarge() {
        val policy = LowBatteryPolicy()
        var reported = false
        val reports = listOf(50 to false, 11 to false, 10 to false, 9 to false, 11 to false, 10 to false, 8 to false)
            .count { (level, charging) ->
                val d = policy.next(reported, level, charging)
                reported = d.reported
                d.report
            }
        assertEquals(1, reports)
        // 充上电再拔掉、又掉到 10%：再报一次
        reported = policy.next(reported, 9, charging = true).reported
        assertEquals(true, policy.next(reported, 9, charging = false).report)
    }

    @Test
    fun lowBatteryRearmsAboveFifteen() {
        val policy = LowBatteryPolicy()
        var reported = policy.next(false, 10, false).reported
        reported = policy.next(reported, 14, false).reported
        assertEquals(false, policy.next(reported, 10, false).report)
        reported = policy.next(reported, 15, false).reported
        assertEquals(true, policy.next(reported, 10, false).report)
    }

    @Test
    fun locationProblemsReportedOnceUntilFixed() {
        val off = LocationAlarmPolicy.problems(locationEnabled = false, fine = true, coarse = true, background = true)
        assertEquals(setOf(LocationProblem.LocationOff), off)
        val first = LocationAlarmPolicy.next(emptySet(), off)
        assertEquals(off, first.report)
        assertEquals(emptySet(), LocationAlarmPolicy.next(first.reported, off).report)
        val fixed = LocationAlarmPolicy.next(first.reported, emptySet())
        assertEquals(off, LocationAlarmPolicy.next(fixed.reported, off).report)
    }

    @Test
    fun permissionProblems() {
        assertEquals(
            setOf(LocationProblem.PermissionRevoked),
            LocationAlarmPolicy.problems(locationEnabled = true, fine = false, coarse = false, background = false),
        )
        assertEquals(
            setOf(LocationProblem.PreciseLocationOff, LocationProblem.BackgroundPermissionRevoked),
            LocationAlarmPolicy.problems(locationEnabled = true, fine = false, coarse = true, background = false),
        )
    }
}
