package com.dodo258.tracker

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HibernationRuleTest {
    @Test
    fun whitelistedIsOk() {
        assertTrue(HibernationRule.ok(whitelisted = true, adminActive = false, sdkInt = Build.VERSION_CODES.R))
        assertTrue(HibernationRule.ok(whitelisted = true, adminActive = false, sdkInt = 36))
    }

    // 三星 A57（Android 16）：设备管理员开着时「管理闲置应用」开关灰着、关不了，系统已豁免
    @Test
    fun activeAdminIsOkFromAndroid12() {
        assertTrue(HibernationRule.ok(whitelisted = false, adminActive = true, sdkInt = Build.VERSION_CODES.S))
        assertTrue(HibernationRule.ok(whitelisted = false, adminActive = true, sdkInt = 36))
    }

    @Test
    fun neitherIsBad() {
        assertFalse(HibernationRule.ok(whitelisted = false, adminActive = false, sdkInt = 36))
        // Android 11 没查到设备管理员豁免，只认开关
        assertFalse(HibernationRule.ok(whitelisted = false, adminActive = true, sdkInt = Build.VERSION_CODES.R))
    }
}
