package org.traccar.client.motion

/** 运动判断的结果（方案第 3 节 motion/）。 */
enum class Motion {
    /** 完全不动（桌上、充电放着）：连续 2 分钟加速度和陀螺都几乎为零 */
    STILL,

    /** 有动静但没离开：拿着玩、在家里走、坐着；或者连着同一个固定 Wi-Fi */
    FIDGET,

    /** 走路（含跑）：加速度有走路节奏或大幅摆动 */
    WALK,

    /** 坐车：有动静、没走路节奏、持续 1 分钟以上；或卫星定位速度 > 3 m/s */
    VEHICLE,

    /** 飞行模式：全停 */
    OFF,
    ;

    /** 走路或坐车：在路上 */
    val moving: Boolean get() = this == WALK || this == VEHICLE
}
