// 位置可能没跟上（方案 docs/specs/2026-11-19-moving-wifi.md 第 5 节）：手机把车载 Wi-Fi、随身 Wi-Fi 当成了家里的固定 Wi-Fi，
// 一直报「在附近活动」、不开卫星，圆点就停在最后一个卫星点上；屋里定不到卫星时也一样（静止，圆点停在早上的卫星点）。
// 这时最近带过的网络估算（netLat/netLon）已经在很远的地方。
// 家长不该被停住的圆点骗过去：卡片第三行写「位置可能没跟上，大概在 X 附近」，地图画淡色估算圈。
// 圆点本身不按估算挪（主坐标只信卫星）。手机从没定到过位置（没有圆点）时，有估算就只画估算圈。纯函数。
import type { LatLon, Position } from '../api/types'
import { distanceMeters } from './places'
import { contactTime, shortAddress } from './presence'
import type { Situation } from './status'
import { MINUTE } from './time'

/** 估算离圆点超过这么多米再加上估算误差，才算「没跟上」 */
export const LAGGING_MARGIN_M = 1000
/** 网络估算只用这么久以内上报带来的：不是每一条都带，最新一条没带不等于估算没了 */
export const ESTIMATE_FRESH_MS = 15 * MINUTE

type Estimate = Pick<Position, 'netLat' | 'netLon' | 'netAcc'>

/**
 * 最近 15 分钟内（按服务器收到的时间）最新一条带网络估算的上报；没有返回 null。
 * reports 是最近 30 分钟的上报，按联系时间排好（track.ts）
 */
export function recentEstimate(reports: readonly Position[], now: Date): Position | null {
  for (let i = reports.length - 1; i >= 0; i--) {
    const p = reports[i]
    if (now.getTime() - contactTime(p).getTime() > ESTIMATE_FRESH_MS) return null
    if (p.netLat !== undefined && p.netLon !== undefined) return p
  }
  return null
}

/** 网络估算：在哪、误差（米，没报就是 0）、离圆点多远（米） */
export type Lagging = LatLon & { accuracy: number; distance: number }

/**
 * 状态是「在附近活动」或「静止」、有网络估算（recentEstimate）、估算离圆点超过 1 公里 + 估算误差：返回估算，否则 null（一切照旧）。
 * situation 是状态句判断出来的情况（失联、手机刚重启、正在更新、在路上这些先说的情况不提示）；dot 是圆点停着的那个点
 */
export function laggingOf(situation: Situation, latest: Estimate, dot: LatLon): Lagging | null {
  if (situation.kind !== 'zone' && situation.kind !== 'still') return null
  if (latest.netLat === undefined || latest.netLon === undefined) return null
  const accuracy = latest.netAcc ?? 0
  const distance = distanceMeters(dot.latitude, dot.longitude, latest.netLat, latest.netLon)
  if (distance <= LAGGING_MARGIN_M + accuracy) return null
  return { latitude: latest.netLat, longitude: latest.netLon, accuracy, distance }
}

/** 「位置可能没跟上，大概在 文三路 152 号附近」；还没查到估算位置的地址时写「大概在 3.2 公里外」 */
export function laggingText(lagging: Lagging, address: string | null | undefined): string {
  const near = shortAddress(address ?? undefined)
  if (near) return `位置可能没跟上，大概在 ${near}附近`
  const km = lagging.distance / 1000
  return `位置可能没跟上，大概在 ${km < 10 ? km.toFixed(1) : Math.round(km)} 公里外`
}
