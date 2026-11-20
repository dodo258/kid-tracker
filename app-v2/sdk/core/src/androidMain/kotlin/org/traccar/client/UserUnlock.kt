package org.traccar.client

import android.content.Context
import kotlin.concurrent.Volatile

/**
 * 开机后还没解锁时建起来的那一套组件（只用系统定位：高德 SDK 的存储在凭据存储，见 PlatformModule），记下它的作用域；
 * 解锁后（[onUserUnlocked]，或者开机广播 BOOT_COMPLETED 先到）按同样配置重建成完整的一套。解锁着建的是 null。
 */
@Volatile
internal var lockedComponents: ComponentCoroutineScope? = null

/** 解锁那一刻：把凭据存储里的东西并过来，接着恢复跟踪；没解锁时建的那一套换成完整的 */
internal suspend fun onUserUnlocked(context: Context) {
    DeviceStorage.migrate(context)
    // 没解锁时设备保护存储里还没有配置的（升级后还没解锁过就重启了）：配置刚并过来，在这里恢复
    val tracker = sharedTracker()
    Log.log("User unlocked, tracking ${tracker?.state?.value?.enabled == true}")
    rebuildIfLocked()
}

/** 现在这一套是没解锁时建的就重建（重建不停前台服务） */
internal fun rebuildIfLocked() {
    lockedComponents?.let { rebuildSharedTracker(it, "user unlocked, switching to full location") }
}
