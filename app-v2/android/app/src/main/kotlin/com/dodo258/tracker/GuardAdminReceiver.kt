package com.dodo258.tracker

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * 防卸载：设备管理员激活后要先停用才能卸载。不申请任何管理策略，只用它挡卸载。
 * 家长用 App 密码关闭时 GuardChannel 先清掉 wantAdmin 再停用；别的途径被停用就报 alarm=tampering（reason=adminDisabled）。
 * 不做无障碍防护（主人 11-18 定）：被停用、关追踪、停报都靠告警和服务器失联提醒兜底。
 */
class GuardAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        val store = GuardStore(context)
        store.wantAdmin = true
        store.adminReported = false
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.guard_admin_disable_warning)

    override fun onDisabled(context: Context, intent: Intent) {
        val pending = goAsync()
        Guard.reportAdminLost(context) { pending.finish() }
    }

    companion object {
        fun component(context: Context) = ComponentName(context, GuardAdminReceiver::class.java)
    }
}
