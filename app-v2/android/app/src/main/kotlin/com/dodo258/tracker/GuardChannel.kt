package com.dodo258.tracker

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

/** 防卸载的 Dart 接口：读状态、开启（弹系统的激活设备管理员）、关闭（Dart 侧先验 App 密码）。 */
class GuardChannel(private val activity: Activity) {
    private val store = GuardStore(activity)
    private var pendingAdmin: MethodChannel.Result? = null

    fun register(messenger: BinaryMessenger) {
        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getState" -> result.success(
                    mapOf("admin" to Guard.adminActive(activity), "wantAdmin" to store.wantAdmin),
                )
                "enableAdmin" -> enableAdmin(result)
                "disableAdmin" -> {
                    store.wantAdmin = false
                    activity.getSystemService(DevicePolicyManager::class.java)
                        .removeActiveAdmin(GuardAdminReceiver.component(activity))
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    /** 弹系统的「激活设备管理员」；返回是否激活了。 */
    private fun enableAdmin(result: MethodChannel.Result) {
        if (Guard.adminActive(activity)) {
            result.success(true)
            return
        }
        pendingAdmin?.success(false)
        pendingAdmin = result
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, GuardAdminReceiver.component(activity))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, activity.getString(R.string.guard_admin_explanation))
        activity.startActivityForResult(intent, REQUEST_ADMIN)
    }

    fun onActivityResult(requestCode: Int): Boolean {
        if (requestCode != REQUEST_ADMIN) return false
        pendingAdmin?.success(Guard.adminActive(activity))
        pendingAdmin = null
        return true
    }

    private companion object {
        const val CHANNEL = "com.dodo258.tracker/guard"
        const val REQUEST_ADMIN = 7302
    }
}
