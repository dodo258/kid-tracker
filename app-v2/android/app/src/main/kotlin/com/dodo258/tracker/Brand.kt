package com.dodo258.tracker

/** 按 Build.MANUFACTURER 归品牌：纯函数，原生单元测试直接测。清单和多任务隐藏共用。 */
object Brand {
    /** 返回 xiaomi、huawei、honor、oppo、vivo、samsung、other（Dart 侧 BackgroundStatus.brand 用同一套名字）。 */
    fun of(manufacturer: String): String {
        val m = manufacturer.lowercase()
        return when {
            m == "xiaomi" || m == "redmi" || m == "poco" -> "xiaomi"
            m == "huawei" -> "huawei"
            m == "honor" -> "honor"
            m == "oppo" || m == "oneplus" || m == "realme" -> "oppo"
            m == "vivo" || m == "iqoo" -> "vivo"
            m == "samsung" -> "samsung"
            else -> "other"
        }
    }

    /**
     * 本 App 的任务要不要从多任务里隐藏：只有三星。
     * 三星实测划掉多任务卡片不影响前台服务和进程（docs/specs/2026-11-19-v2-audit-fixes.md 三星实机一节），
     * 隐藏不降低可靠性；别的品牌划掉可能连进程一起清，要留在多任务里让家长加锁。
     */
    fun excludeFromRecents(brand: String): Boolean = brand == "samsung"
}
