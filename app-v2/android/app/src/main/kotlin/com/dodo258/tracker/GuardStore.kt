package com.dodo258.tracker

import android.content.Context

/**
 * 防卸载（设备管理员）的本地状态：原生 SharedPreferences「guard」，键名和根目录旧 App 一样，覆盖安装后接着用。
 *
 * [wantAdmin]：家长开过、还没用 App 密码关掉。开着却发现系统里已经没了，就是被停用了：清单标 ✗、给家长报一次（[adminReported] 防重复）。
 */
class GuardStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("guard", Context.MODE_PRIVATE)

    var wantAdmin: Boolean
        get() = prefs.getBoolean(WANT_ADMIN, false)
        set(value) {
            prefs.edit().putBoolean(WANT_ADMIN, value).commit()
        }

    var adminReported: Boolean
        get() = prefs.getBoolean(ADMIN_REPORTED, false)
        set(value) {
            prefs.edit().putBoolean(ADMIN_REPORTED, value).commit()
        }

    private companion object {
        const val WANT_ADMIN = "want_admin"
        const val ADMIN_REPORTED = "admin_reported"
    }
}
