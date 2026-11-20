package org.traccar.client.sense

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import androidx.core.content.getSystemService

/**
 * 登记着的基站（跟踪区、小区号），给判断层认「锚 Wi-Fi 这里没见过的基站」（方案 docs/specs/2026-11-19-moving-wifi.md 4.3）；
 * 信号强度只录下来（方案 2026-11-19-sky-quality.md 第 8 节：出门时墙不挡了，信号会跳一下）。
 * 读的是系统缓存的 [TelephonyManager.getAllCellInfo]（不让基带重新扫），最多 [MAX_AGE_MILLIS] 读一次；没卡、没权限、读不到都是 null。
 * 只在采样线程上调。
 */
internal class CellReader(context: Context) {

    private val telephony: TelephonyManager? = context.applicationContext.getSystemService()
    private var readAt = Long.MIN_VALUE / 2
    private var cell = Cell()

    fun current(): Cell {
        val now = SystemClock.elapsedRealtime()
        if (now - readAt < MAX_AGE_MILLIS) return cell
        readAt = now
        cell = try {
            telephony?.allCellInfo?.firstOrNull { it.isRegistered }?.let(::read) ?: Cell()
        } catch (e: SecurityException) {
            Cell()
        }
        return cell
    }

    private fun read(info: CellInfo): Cell = when {
        info is CellInfoLte -> info.cellIdentity.let { Cell(valid(it.tac), valid(it.ci)?.toLong(), valid(info.cellSignalStrength.dbm)) }
        info is CellInfoWcdma -> info.cellIdentity.let { Cell(valid(it.lac), valid(it.cid)?.toLong(), valid(info.cellSignalStrength.dbm)) }
        info is CellInfoGsm -> info.cellIdentity.let { Cell(valid(it.lac), valid(it.cid)?.toLong(), valid(info.cellSignalStrength.dbm)) }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr ->
            (info.cellIdentity as CellIdentityNr).let {
                Cell(valid(it.tac), it.nci.takeIf { n -> n != Long.MAX_VALUE }, valid((info.cellSignalStrength as CellSignalStrengthNr).dbm))
            }
        else -> Cell()
    }

    /** 系统读不到时给 Int.MAX_VALUE（CellInfo.UNAVAILABLE） */
    private fun valid(v: Int): Int? = v.takeIf { it != Int.MAX_VALUE }

    private companion object {
        const val MAX_AGE_MILLIS = 10_000L
    }
}

/** 登记着的基站：跟踪区、小区号、信号强度（dBm）；读不到的是 null */
internal data class Cell(val tac: Int? = null, val id: Long? = null, val dbm: Int? = null)
