package org.traccar.client.policy

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import org.traccar.client.Provider
import org.traccar.client.motion.Motion
import org.traccar.client.sense.Snapshot

/** 连着的 Wi-Fi 算哪种（方案 docs/specs/2026-11-18-reporter-sensor-first.md 第 3a 节定稿）。 */
enum class WifiKind {
    /** 没连 Wi-Fi */
    NONE,

    /** 还连着上次卫星定位时连的那个 Wi-Fi：人多半还在那里（像在动时开卫星量一量，见 [AnchorProbe]） */
    ANCHOR,

    /** 一眼认得出的手机热点：计费、iPhone 热点网段、名字像手机；当没连 Wi-Fi */
    HOTSPOT,

    /** 别的 Wi-Fi（换了 Wi-Fi、读不到 BSSID）：只说明可能动了，交给运动判断和卫星确认 */
    OTHER,
}

/**
 * 判断 Wi-Fi 用的记忆，存在状态库里，重启接着用：锚 = 上次好的卫星定位时连着的 Wi-Fi（[anchor]）+ 那个卫星点的位置和精度
 * + 连着它时见过的基站（[anchorCells]，「跟踪区-小区」）；跟着人走过的 Wi-Fi（[moving]）只当提示：像在动时更早去量。
 */
@Serializable
data class WifiMemory(
    val anchor: String? = null,
    val anchorLat: Double? = null,
    val anchorLon: Double? = null,
    val anchorAcc: Double? = null,
    val moving: List<String> = emptyList(),
    val anchorCells: List<String> = emptyList(),
    /** 锚点是确认过的网络估算顶上的临时锚点（[WifiBook.tempAnchor]），第一个开阔卫星点替换 */
    val anchorTemp: Boolean = false,
)

/** 判断 Wi-Fi 用的一个卫星点 */
data class GpsPoint(val time: Long, val latitude: Double, val longitude: Double, val accuracy: Double)

/**
 * Wi-Fi 只辅助判断状态，不当位置（方案 3a 定稿；移动 Wi-Fi 见 docs/specs/2026-11-19-moving-wifi.md 第 4 节）：
 * - 锚：好的卫星点（精度 ≤ [GOOD_ACCURACY] 米）时连着的 Wi-Fi，连同那个点的位置。还连着它（[WifiKind.ANCHOR]）时屋里走动不开卫星；
 *   像在动就开卫星量离锚点多远（[AnchorProbe]），超出 [MOVE_METERS]（加上两边的精度）就是 Wi-Fi 跟着人走，锚清掉。
 *   不问它是哪种 Wi-Fi：随身 Wi-Fi 带回家长期连着，照样在家当锚。
 *   这个 Wi-Fi 还没有锚、屋里卫星点一直没有时，确认过的网络估算先当临时锚点（[tempAnchor]），第一个好的卫星点替换。
 * - 一眼认得出的热点（安卓计费、iPhone 172.20.10.x 网段、名字像「xx 的 iPhone」「xx 的手机」、AndroidAP、随身 Wi-Fi）不当锚：
 *   天生跟着人走，一上车就跟，不用等量。
 * - 跟着人走过的 Wi-Fi（最多 [MAX_MOVING] 个）和本地管理（随机）MAC 的 Wi-Fi 只是提示（[hinted]）：像在动时更早去量。
 * 纯逻辑，调用方每份快照喂一次（[observe]），记忆变了自己存（[memory]）。
 */
class WifiBook(memory: WifiMemory = WifiMemory()) {

    private var anchor: String? = memory.anchor
    private var anchorAt: GpsPoint? = run {
        val lat = memory.anchorLat ?: return@run null
        val lon = memory.anchorLon ?: return@run null
        GpsPoint(0, lat, lon, memory.anchorAcc ?: GOOD_ACCURACY)
    }
    private val moving = memory.moving.toMutableList()
    private val cells = memory.anchorCells.toMutableList()

    /** 锚点是网络估算顶上的（[tempAnchor]）：第一个好的卫星点就替换，不管离多近；量出离开了也不把这个 Wi-Fi 记成跟着人走 */
    var temporary: Boolean = memory.anchorTemp && anchorAt != null
        private set

    val memory: WifiMemory
        get() = WifiMemory(anchor, anchorAt?.latitude, anchorAt?.longitude, anchorAt?.accuracy, moving.toList(), cells.toList(), temporary)

    /** 锚 Wi-Fi 被记下时的卫星点。老版本存的记忆只有 BSSID、没有位置：量不了，先不当锚，下一个好的卫星点补上位置 */
    val anchorPoint: GpsPoint? get() = anchorAt

    fun kind(s: Snapshot): WifiKind = when {
        s.net != Snapshot.Net.WIFI -> WifiKind.NONE
        looksLikeHotspot(s) -> WifiKind.HOTSPOT
        s.wifiBssid.isNotEmpty() && s.wifiBssid == anchor && anchorAt != null -> WifiKind.ANCHOR
        else -> WifiKind.OTHER
    }

    /** 还连着上次卫星定位时那个 Wi-Fi 时是它的 BSSID（给运动判断），否则 null */
    fun anchorBssid(s: Snapshot): String? = s.wifiBssid.takeIf { kind(s) == WifiKind.ANCHOR }

    /** 跟着人走过，或者是本地管理（随机）MAC（新系统的手机热点用随机 BSSID；部分 Mesh 副 BSSID 也是，所以只当提示） */
    fun hinted(bssid: String): Boolean = bssid in moving || locallyAdministered(bssid)

    /** 这一份快照连着的 Wi-Fi 跟着人走过（量出来过）：高德按它的 MAC 查出来的是它原来所在的地方，Wi-Fi 定位不用（[usableFix]） */
    fun followed(s: Snapshot): Boolean = s.net == Snapshot.Net.WIFI && s.wifiBssid.isNotEmpty() && s.wifiBssid in moving

    /** 这一份快照连着锚 Wi-Fi、读得到基站，而这个基站连着锚时没见过（跟踪区变了也是没见过）；还没记下基站时不算 */
    fun unfamiliarCell(s: Snapshot): Boolean {
        val cell = s.cellKey() ?: return false
        return kind(s) == WifiKind.ANCHOR && cells.isNotEmpty() && cell !in cells
    }

    /** [fix] 离锚点（不给 [from] 就是现在的锚点）超出了 Wi-Fi 的覆盖范围：[MOVE_METERS] 加上两个点的精度 */
    fun beyond(fix: GpsPoint, from: GpsPoint? = anchorAt): Boolean = fromAnchor(fix, from)?.let { it > MOVE_METERS } ?: false

    /**
     * [fix] 明显就在锚这里：[HERE_METERS] 加上两个点的精度以内。和 [beyond] 之间那一段说不清（院子里、楼下，或者带着随身 Wi-Fi
     * 刚走出去一百多米），接着量，量不清就过一会儿再量，不当作「还在这里」冷却 10 分钟
     */
    fun near(fix: GpsPoint, from: GpsPoint? = anchorAt): Boolean = fromAnchor(fix, from)?.let { it <= HERE_METERS } ?: false

    /** 离锚点 [at] 多远，减掉两个点的精度 */
    private fun fromAnchor(fix: GpsPoint, at: GpsPoint?): Double? {
        at ?: return null
        return distanceMeters(at.latitude, at.longitude, fix.latitude, fix.longitude) - at.accuracy - fix.accuracy
    }

    /**
     * 每份快照喂一次；[gps] 是这一刻刚拿到的卫星点，[motion] 是没被 Wi-Fi 改判的运动判断，[onTheRoad] 是已经判成在路上（卫星开着跟）。
     * 好的卫星点时连着的 Wi-Fi 记成锚（带位置、基站）；还连着锚、在走路坐车、卫星点已经超出锚的覆盖范围，就是 Wi-Fi 跟着人走：
     * 记成提示、锚清掉（下一个卫星点在哪就锚在哪）。还没判成在路上时、像在动时的点（探测量的、别的 App 定的）不挪锚点：
     * 不然人带着 Wi-Fi 一边走、锚点一边跟着挪，永远量不出离开。记忆变了（要存）返回 true。
     */
    fun observe(s: Snapshot, motion: Motion, gps: GpsPoint?, onTheRoad: Boolean = false): Boolean {
        val bssid = s.wifiBssid.takeIf { s.net == Snapshot.Net.WIFI && it.isNotEmpty() && !looksLikeHotspot(s) }
        val fix = gps?.takeIf { it.accuracy <= GOOD_ACCURACY } ?: return false
        return when {
            bssid == null -> anchor != null && release(followed = false)
            bssid == anchor && motion.moving && beyond(fix) -> release(followed = true)
            bssid == anchor && anchorAt != null && motion.moving && !onTheRoad -> false
            else -> setAnchor(bssid, fix, s)
        }
    }

    /**
     * 锚清掉。[followed]：量出来 Wi-Fi 跟着人走了，记成提示（最多 [MAX_MOVING] 个，旧的先丢）。记忆变了返回 true。
     */
    fun release(followed: Boolean): Boolean {
        val bssid = anchor ?: return false
        // 临时锚点本身就可能差几百米：量出「离开了」不能说明 Wi-Fi 跟着人走，记成提示的话家里的 Wi-Fi 估算以后都不用了
        if (followed && !temporary && bssid !in moving) {
            moving += bssid
            if (moving.size > MAX_MOVING) moving.removeAt(0)
        }
        anchor = null
        anchorAt = null
        temporary = false
        cells.clear()
        return true
    }

    /**
     * 连着的 Wi-Fi 还没有锚（不是热点、没跟着人走过）、屋里卫星点又一直没有：用确认过的网络估算 [at] 当临时锚点，屋里走动就不开卫星了；
     * 之后第一个好的卫星点替换（[observe]）。记忆变了返回 true
     */
    fun tempAnchor(s: Snapshot, at: GpsPoint): Boolean {
        if (kind(s) != WifiKind.OTHER || s.wifiBssid.isEmpty() || followed(s)) return false
        anchor = s.wifiBssid
        anchorAt = at
        temporary = true
        cells.clear()
        s.cellKey()?.let { cells += it }
        return true
    }

    /** 量过了、人还在锚这里（或者量不出来）：这一刻的基站记成锚这里见过的。记忆变了返回 true */
    fun learnCell(s: Snapshot): Boolean {
        val cell = s.cellKey() ?: return false
        if (kind(s) != WifiKind.ANCHOR || cell in cells) return false
        cells += cell
        if (cells.size > MAX_CELLS) cells.removeAt(0)
        return true
    }

    /** 同一个锚上，卫星点在原来锚点 [SETTLE_METERS] 内就不挪锚点（一直开着卫星时不用每秒存一次）；临时锚点总是换成卫星点 */
    private fun setAnchor(bssid: String, fix: GpsPoint, s: Snapshot): Boolean {
        val at = anchorAt
        val cellChanged = if (bssid == anchor) learnCell(s) else false
        if (bssid == anchor && at != null && !temporary &&
            distanceMeters(at.latitude, at.longitude, fix.latitude, fix.longitude) <= SETTLE_METERS
        ) {
            return cellChanged
        }
        if (bssid != anchor) {
            cells.clear()
            s.cellKey()?.let { cells += it }
        }
        anchor = bssid
        anchorAt = fix
        temporary = false
        return true
    }

    companion object {
        const val MOVE_METERS = 200.0
        const val HERE_METERS = 100.0
        const val GOOD_ACCURACY = 50.0
        const val SETTLE_METERS = 50.0
        const val MAX_MOVING = 64
        const val MAX_CELLS = 8

        /** iPhone 个人热点给连上来的设备分 172.20.10.0/28 的地址，网关 172.20.10.1 */
        private const val IPHONE_HOTSPOT_PREFIX = "172.20.10."

        /** 手机热点、随身 Wi-Fi 的常见名字；不收「HUAWEI-」「Xiaomi_」这类路由器默认名 */
        private val HOTSPOT_NAMES = Regex("""iphone|ipad|的手机|手机热点|androidap|galaxy|mifi|随身|ufi""", RegexOption.IGNORE_CASE)

        /** 不用学就能认出的热点（规则 ①②③） */
        fun looksLikeHotspot(s: Snapshot): Boolean =
            s.net == Snapshot.Net.WIFI &&
                (s.wifiMetered == true || s.wifiGateway.startsWith(IPHONE_HOTSPOT_PREFIX) || HOTSPOT_NAMES.containsMatchIn(s.wifiSsid))

        /** MAC 第一个字节的第二低位是 1：本地管理（随机）地址 */
        fun locallyAdministered(bssid: String): Boolean =
            bssid.take(2).toIntOrNull(16)?.let { it and 0x02 != 0 } ?: false
    }
}

/** 基站记成「跟踪区-小区」；读不到是 null */
internal fun Snapshot.cellKey(): String? {
    val id = cellId ?: return null
    return "${cellTac ?: ""}-$id"
}

/**
 * 网络定位的结果能不能用（放进附加字段 netLat / netLon；屋里一直没卫星时确认过的才当主坐标，见 [NetFallback]）：连着热点、跟着人走过的 Wi-Fi（[followed]）时高德按它的 MAC
 * 查位置库，它常在家里开，带出门会报「在家」，Wi-Fi 定位的结果不用；基站照用。
 */
fun usableFix(provider: String?, kind: WifiKind, followed: Boolean = false): Boolean =
    provider != Provider.WIFI || (kind != WifiKind.HOTSPOT && !followed)

/** 上报的 net 字段（和家长页「详细信息」约定的一样）：wifi、hotspot、cellular、none */
fun netLabel(net: Snapshot.Net?, kind: WifiKind): String = when {
    net == null -> "none"
    net == Snapshot.Net.WIFI -> if (kind == WifiKind.HOTSPOT) "hotspot" else "wifi"
    net == Snapshot.Net.NONE -> "none"
    else -> "cellular"
}

/** 两点间的地面距离（米，球面近似） */
internal fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = lat1 * PI / 180
    val p2 = lat2 * PI / 180
    val dp = (lat2 - lat1) * PI / 180
    val dl = (lon2 - lon1) * PI / 180
    val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(h))
}

private const val EARTH_RADIUS_METERS = 6_371_000.0
