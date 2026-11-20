package com.dodo258.tracker

import android.os.PowerManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerSaveRuleTest {
    @Test
    fun offOrUnreadableIsOk() {
        assertTrue(PowerSaveRule.ok(powerSave = false, locationMode = PowerManager.LOCATION_MODE_NO_CHANGE))
        // 国产系统的省电模式不一定同步到 isPowerSaveMode：读不到就当没开
        assertTrue(PowerSaveRule.ok(powerSave = null, locationMode = null))
        assertTrue(PowerSaveRule.ok(powerSave = false, locationMode = null))
    }

    @Test
    fun onIsBad() {
        assertFalse(PowerSaveRule.ok(powerSave = true, locationMode = null))
        assertFalse(PowerSaveRule.ok(powerSave = true, locationMode = PowerManager.LOCATION_MODE_NO_CHANGE))
    }

    @Test
    fun locationLimitedWhenScreenOffIsBad() {
        for (mode in listOf(
            PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF,
            PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF,
            PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF,
        )) {
            assertFalse("mode $mode", PowerSaveRule.ok(powerSave = null, locationMode = mode))
        }
        assertTrue(PowerSaveRule.ok(powerSave = null, locationMode = PowerManager.LOCATION_MODE_FOREGROUND_ONLY))
    }
}
