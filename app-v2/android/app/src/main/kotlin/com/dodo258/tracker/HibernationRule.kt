package com.dodo258.tracker

import android.os.Build

/** 清单「暂停闲置应用的活动」一项的判断：纯函数，原生单元测试直接测。 */
object HibernationRule {
    /**
     * [whitelisted] 是 isAutoRevokeWhitelisted（「管理闲置应用」关着）；[adminActive] 是本 App 的设备管理员已启用。
     * Android 12 起系统把已启用的设备管理员豁免出闲置暂停（AOSP HibernationPolicy.isPackageHibernationExemptBySystem），
     * 这时开关是灰的、关不了也不用关；Android 11 没查到同样的豁免，只认开关。
     */
    fun ok(whitelisted: Boolean, adminActive: Boolean, sdkInt: Int): Boolean =
        whitelisted || (adminActive && sdkInt >= Build.VERSION_CODES.S)
}
