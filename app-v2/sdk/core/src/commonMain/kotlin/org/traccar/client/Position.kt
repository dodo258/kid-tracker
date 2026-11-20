package org.traccar.client

import kotlinx.serialization.Serializable

@Serializable
data class Position(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Double? = null,
    val time: Long,
    val altitude: Double? = null,
    val speed: Double? = null,
    val bearing: Double? = null,
    val battery: Int? = null,
    val charging: Boolean? = null,
    val alarm: String? = null,
    /** 告警的原因（只有 tampering 带：adminDisabled、passwordFailures），报给服务器分文案 */
    val reason: String? = null,
    /** 定位来源：[Provider] 里的一个；只报时间的点没有 */
    val provider: String? = null,
    /** 判断层给的（方案第 4 节）：state moving / zone / still，motion walk / vehicle / fidget / still，net wifi / hotspot / cellular / none */
    val state: String? = null,
    val motion: String? = null,
    /** 离上一次上报的步数、晃动秒数 */
    val steps: Int? = null,
    val bumps: Int? = null,
    val net: String? = null,
    /**
     * 网络定位（Wi-Fi、基站）的估算（方案 3a 定稿）：只给家长页画「大概在这」，不当位置——主坐标 [latitude]、[longitude] 只放卫星点，
     * Traccar 的围栏、通知、轨迹都按主坐标算。[netSource] 是 [Provider] 里的 wifi / cell / network
     */
    val netLat: Double? = null,
    val netLon: Double? = null,
    val netAcc: Double? = null,
    val netSource: String? = null,
    /** 连着 Wi-Fi 时：还是不是上次卫星定位时连的那个（是：没离开那里；不是：可能动了） */
    val wifiSame: Boolean? = null,
    /** 开机后孩子还没解锁过（Direct Boot）：只有 true，解锁后的点不带 */
    val locked: Boolean? = null,
)

/** [Position.provider] 的取值，和家长页、模拟器认的一样 */
object Provider {
    const val GPS = "gps"
    const val WIFI = "wifi"
    const val CELL = "cell"

    /** 分不出 Wi-Fi 还是基站的网络定位（系统网络定位、高德的离线和别的结果） */
    const val NETWORK = "network"

    /** 主坐标用的是连着的 Wi-Fi 的卫星锚点（屋里定不到卫星、网络估算和锚点对得上时顶上的，见 policy/NetFallback） */
    const val ANCHOR = "anchor"
}
