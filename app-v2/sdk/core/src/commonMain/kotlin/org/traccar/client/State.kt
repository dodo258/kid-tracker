package org.traccar.client

import kotlinx.serialization.Serializable
import org.traccar.client.policy.MainFix
import org.traccar.client.policy.WifiMemory

/**
 * [paused] 是「连续定位（卫星）关着」：判断层说没在走路、坐车时为真，定位源停掉连续定位，心跳闹钟开始走。
 * [wifi] 是判断 Wi-Fi 用的记忆（policy/WifiBook：上次卫星定位时连的 Wi-Fi、跟着人走过的 Wi-Fi），重启接着用。
 * [motion] 是最近的运动判断，重启后接着用（只接静止，见 TrackerEngine）。
 * [main] 是上一个报给服务器的主坐标（policy/NetFallback：屋里没卫星时网络估算要离它够远才顶上），重启接着比。
 */
@Serializable
data class State(
    val enabled: Boolean = false,
    val paused: Boolean = false,
    val wifi: WifiMemory = WifiMemory(),
    /** 判断层最近的运动判断（[org.traccar.client.motion.Motion] 的名字）：进程被杀、重启后静止接着静止，不先报一条「在附近活动」 */
    val motion: String? = null,
    val main: MainFix? = null,
)
