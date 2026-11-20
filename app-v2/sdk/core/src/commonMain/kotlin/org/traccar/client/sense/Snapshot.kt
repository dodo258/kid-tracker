package org.traccar.client.sense

/**
 * 每 2 秒一份的传感器快照（方案 docs/specs/2026-11-18-reporter-sensor-first.md 第 3 节 sense/）。
 * 字段和录制文件（[SnapshotCsv]）的列一一对应；运动判断（motion/）只读这里，不碰 Android。
 * 读不到的值是 null（CSV 里是空），计数类是 0。
 *
 * 加速度是去掉重力后的大小（m/s²，静止时接近 0，P20 有约 ±0.4 的零点偏差），陀螺是角速度大小（rad/s）。
 */
data class Snapshot(
    /** 这一窗结束的时刻，毫秒（Unix 时间） */
    val time: Long,
    val screen: Boolean? = null,
    val charging: Boolean? = null,
    val battery: Int? = null,
    val airplane: Boolean? = null,
    val accMean: Double? = null,
    val accStd: Double? = null,
    val accMax: Double? = null,
    val accN: Int = 0,
    /** 这一窗里合走路节奏的加速度峰（[GaitRhythm]）；旧录制没有这一列，是 null */
    val accPeaks: Int? = null,
    val gyroMean: Double? = null,
    val gyroMax: Double? = null,
    /** 朝向（度，0–360） */
    val heading: Double? = null,
    val headingN: Int = 0,
    /** 计步器开机以来的累计步数；华为会攒着一批一批报 */
    val stepsTotal: Long? = null,
    /** 这一窗里步数检测器报的次数 */
    val stepEvents: Int = 0,
    val proximity: Double? = null,
    val light: Double? = null,
    val pressure: Double? = null,
    val net: Net = Net.NONE,
    val wifiBssid: String = "",
    val wifiSsid: String = "",
    val wifiRssi: Int? = null,
    /** 连着的 Wi-Fi 是不是计费网络（热点、随身 Wi-Fi 多半是）；没连 Wi-Fi 时是 null */
    val wifiMetered: Boolean? = null,
    /** 连着的 Wi-Fi 的默认网关（IPv4），iPhone 热点是 172.20.10.1；读不到是空 */
    val wifiGateway: String = "",
    val scanN: Int? = null,
    val scanOverlap: Double? = null,
    /** 最近一次定位（被动收到的，可能是很久以前的），没有就是 null */
    val fix: Fix? = null,
    /** 登记着的基站的跟踪区（LTE / 5G 的 TAC，2G / 3G 的 LAC）；没卡、读不到是 null */
    val cellTac: Int? = null,
    /** 登记着的基站的小区号（LTE 的 CI、5G 的 NCI、2G / 3G 的 CID）；没卡、读不到是 null */
    val cellId: Long? = null,
    // 下面四个只采数据、判断层还不读（方案 docs/specs/2026-11-19-sky-quality.md 第 8 节第 1 条），旧录制没有这几列是 null
    /** 这一窗里参与定位的卫星数（[SatelliteLog]）；卫星没开（自己和别的 App 都没开）、系统没给是 null */
    val satUsed: Int? = null,
    /** 这一窗里信号最强 4 颗卫星的平均载噪比（dB-Hz）；只拿到卫星数（定位点附带的）时是 null */
    val cn0Top4: Double? = null,
    /** 这一窗里磁场强度（μT）的标准差：楼里钢筋、电器让它乱，户外平稳 */
    val magStd: Double? = null,
    /** 登记着的基站的信号强度（dBm，LTE 是 RSRP）；没卡、读不到是 null */
    val cellDbm: Int? = null,
    /**
     * 现在这条网络连接是采样器见到的第几条（默认网络每连上一次加一）：同一个 Wi-Fi 在两份快照之间断开又连上，BSSID 一样、这个数变了
     * （审计 11.0.11 第 3 条）。旧录制没有这一列是 null
     */
    val netLink: Int? = null,
    /** 这一窗结束时的开机时长（毫秒，改系统时间不影响）：判断快照间隔、卫星点新不新鲜用它（审计 11.0.11 第 6 条）。旧录制没有是 null */
    val elapsed: Long? = null,
) {
    /** 连着不计费的 Wi-Fi（多半是家里、学校的路由器；判断屋里走动用的是「上次卫星定位时连的 Wi-Fi」，见 policy/WifiBook） */
    val onFixedWifi: Boolean get() = net == Net.WIFI && wifiMetered != true

    /**
     * [fix] 是这一窗前 [maxAgeMillis] 内新拿到的卫星定位。比这一窗晚 [BATCH_LATE_MILLIS] 以内的也算：息屏时快照一批 5 份（约 10 秒）
     * 一起交，判断层手里最新的卫星点常常比一批里前几窗都晚，车一直在走、速度一直有效，不能当成「还没发生」丢掉
     */
    fun freshGps(maxAgeMillis: Long): Fix? =
        fix?.takeIf { it.provider == "gps" && ageOf(it) in -BATCH_LATE_MILLIS..maxAgeMillis }

    /**
     * [f] 比这一窗早多少毫秒：两边都有开机时长就用它（系统时间被改快一天，卫星时间照常，拿系统时间减会以为点是一天前的）；
     * 旧录制、模拟器没有开机时长，用时间
     */
    fun ageOf(f: Fix): Long {
        val e = elapsed
        val fe = f.elapsed
        return if (e != null && fe != null) e - fe else time - f.time
    }

    /** 判断快照间隔用的时刻：有开机时长用它（审计 11.0.11 第 6 条），没有用时间 */
    val clock: Long get() = elapsed ?: time

    companion object {
        /** 一批快照覆盖的时长（SnapshotSampler 息屏时 5 份一批） */
        const val BATCH_LATE_MILLIS = 10_000L
    }

    data class Fix(
        val provider: String,
        val time: Long,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val accuracy: Double? = null,
        /** m/s */
        val speed: Double? = null,
        val bearing: Double? = null,
        /** 定位时的开机时长（毫秒，见 [ageOf]）；旧录制、拿不到时是 null */
        val elapsed: Long? = null,
    )

    enum class Net(val csv: String) {
        WIFI("wifi"),
        CELL("cell"),
        OTHER("other"),
        NONE("none"),
        ;

        companion object {
            fun fromCsv(value: String): Net = entries.firstOrNull { it.csv == value } ?: NONE
        }
    }
}
