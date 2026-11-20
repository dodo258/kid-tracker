// 方向：两点之间的方位角、箭头往目标方向转（走近的那边）、地图转了以后箭头在屏幕上转多少。
import type { LatLon } from '../api/types'

const RAD = Math.PI / 180

/** 把角度规整到 (-180, 180] */
export function wrap180(deg: number): number {
  const d = ((((deg + 180) % 360) + 360) % 360) - 180
  return d === -180 ? 180 : d
}

/** 从 a 到 b 的方位角（度，正北 0 顺时针，0–360） */
export function bearing(a: LatLon, b: LatLon): number {
  const φ1 = a.latitude * RAD
  const φ2 = b.latitude * RAD
  const Δλ = (b.longitude - a.longitude) * RAD
  const y = Math.sin(Δλ) * Math.cos(φ2)
  const x = Math.cos(φ1) * Math.sin(φ2) - Math.sin(φ1) * Math.cos(φ2) * Math.cos(Δλ)
  return ((Math.atan2(y, x) / RAD) % 360 + 360) % 360
}

/**
 * 箭头从 current 往 target 转一帧：走近的那边（359° → 1° 转 2°，不转一整圈），时间常数 tauMs（约 3 个 tau 转到位）。
 * 返回新的角度（0–360）
 */
export function approachHeading(current: number, target: number, dtMs: number, tauMs: number): number {
  const delta = wrap180(target - current)
  const k = dtMs <= 0 ? 0 : 1 - Math.exp(-dtMs / tauMs)
  const next = Math.abs(delta) < 0.1 ? target : current + delta * k
  return ((next % 360) + 360) % 360
}

/** 地图转了 mapBearing 度（正北朝屏幕右偏）以后，地上朝 heading 的箭头在屏幕上转多少度 */
export function screenAngle(heading: number, mapBearing: number): number {
  return wrap180(heading - mapBearing)
}
