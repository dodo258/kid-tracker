// 常去地点（Traccar 地理围栏）的纯函数：CIRCLE 字符串的生成和解析、孩子在不在某个地点里、地址那一行的文字。
// 格式和距离公式照 Traccar 6.17 的 GeofenceCircle、DistanceCalculator：「CIRCLE (纬度 经度, 半径米)」，坐标 WGS-84。
import type { Geofence, Position } from '../api/types'

export type Circle = { latitude: number; longitude: number; radius: number }

/** 添加时可选的半径（米），默认 200 */
export const RADII = [100, 200, 500, 1000] as const
export const DEFAULT_RADIUS = 200

/** 改范围时可选的半径：常用的四档；现在的范围不在里面（完整后台画的 300 米）就按大小插进去，选中它等于不改 */
export function radiusChoices(current: number): number[] {
  const r = Math.round(current)
  const out: number[] = [...RADII]
  return out.includes(r) || r <= 0 ? out : [...out, r].sort((a, b) => a - b)
}

const EARTH_RADIUS = 6378137.0 // DistanceCalculator.EQUATORIAL_EARTH_RADIUS

/** 6 位小数约 0.1 米，够用，也不会写出一长串 */
const round6 = (x: number) => Math.round(x * 1e6) / 1e6

export function circleArea(c: Circle): string {
  return `CIRCLE (${round6(c.latitude)} ${round6(c.longitude)}, ${Math.round(c.radius)})`
}

/** 解析 Traccar 的 CIRCLE；多边形、折线或格式不对返回 undefined（这些在完整后台画的地点只列名字，不画） */
export function parseCircle(area: string): Circle | undefined {
  const m = /^\s*CIRCLE\s*\(([^)]*)\)\s*$/.exec(area)
  if (!m) return undefined
  const parts = m[1].split(',')
  if (parts.length !== 2) return undefined
  const center = parts[0].trim().split(/\s+/)
  if (center.length !== 2) return undefined
  const [latitude, longitude, radius] = [Number(center[0]), Number(center[1]), Number(parts[1].trim())]
  if (![latitude, longitude, radius].every(Number.isFinite) || radius <= 0) return undefined
  if (Math.abs(latitude) > 90 || Math.abs(longitude) > 180) return undefined
  return { latitude, longitude, radius }
}

/** 两点距离（米），和 Traccar DistanceCalculator.distance 同一个公式 */
export function distanceMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const rad = Math.PI / 180
  const sinDlat = Math.sin(((lat2 - lat1) * rad) / 2)
  const sinDlon = Math.sin(((lon2 - lon1) * rad) / 2)
  const a = sinDlat * sinDlat + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * sinDlon * sinDlon
  return EARTH_RADIUS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

/**
 * 孩子现在在哪个常去地点里：服务器标在位置上的（geofenceIds），或者落在圆里的。
 * 自己再算一遍圆，是因为刚添加的地点要等下一个点上来服务器才会标；公式和 Traccar 一样，结果一致。
 * 同时在几个地点里（学校在家附近 500 米的圈里）取最小的那个。
 */
export function placeOf(position: Position, places: Geofence[]): Geofence | undefined {
  // 0,0 占位不是位置，不算在不在圈里
  if (position.unlocated) return undefined
  let best: { place: Geofence; radius: number } | undefined
  for (const place of places) {
    const c = parseCircle(place.area)
    const inside =
      position.geofenceIds.includes(place.id) ||
      (c !== undefined && distanceMeters(c.latitude, c.longitude, position.latitude, position.longitude) <= c.radius)
    if (!inside) continue
    const radius = c?.radius ?? Infinity
    if (!best || radius < best.radius) best = { place, radius }
  }
  return best?.place
}

/** 地址那一行：「在 西溪路 518 号附近」；没有地址返回 null，整行不显示 */
export function nearText(address: string | undefined): string | null {
  const a = address?.trim()
  return a ? `在 ${a}附近` : null
}
