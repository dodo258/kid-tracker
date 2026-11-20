package com.dodo258.tracker

import android.os.PowerManager

/** 清单「省电模式」一项的判断：纯函数，原生单元测试直接测。 */
object PowerSaveRule {
    // 省电模式下锁屏后停掉或限流定位的几种处理（PowerManager.getLocationPowerSaveMode，Android 9 起）
    private val LOCATION_LIMITED = setOf(
        PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF,
        PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF,
        PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF,
    )

    /**
     * [powerSave] 是 isPowerSaveMode，读不到（null）当作没开、不误报；
     * [locationMode] 是 getLocationPowerSaveMode，Android 9 以前传 null。
     */
    fun ok(powerSave: Boolean?, locationMode: Int?): Boolean = powerSave != true && locationMode !in LOCATION_LIMITED
}
