package org.traccar.client

import android.content.Context
import android.os.BatteryManager
import androidx.core.content.getSystemService

/** 上报那一刻手机自己的状态：电量、充没充电，开机后还没解锁时标 locked（家长页写「手机刚重启」） */
class AndroidBatteryProcessor(context: Context) : PositionProcessor {

    private val appContext = context.applicationContext
    private val batteryManager: BatteryManager? = appContext.getSystemService()

    override suspend fun process(position: Position): Position = position.copy(
        battery = position.battery ?: batteryLevel(),
        charging = position.charging ?: batteryManager?.isCharging,
        locked = position.locked ?: true.takeIf { !DeviceStorage.isUnlocked(appContext) },
    )

    private fun batteryLevel(): Int? {
        val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return null
        return if (level in 0..100) level else null
    }
}
