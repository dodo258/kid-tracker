import { describe, expect, it } from 'vitest'
import type { Position } from '../api/types'
import { ESTIMATE_FRESH_MS, laggingOf, laggingText, recentEstimate } from './lagging'
import type { Presence } from './presence'
import { situationOf, type Situation } from './status'

const MIN = 60_000
const NOW = new Date('2026-11-19T08:42:00Z')
const ago = (m: number) => new Date(NOW.getTime() - m * MIN)
const M_PER_DEG = 111_320
const DOT = { latitude: 30.27, longitude: 120.14 }
/** 圆点正北 meters 米 */
const north = (meters: number) => ({ netLat: DOT.latitude + meters / M_PER_DEG, netLon: DOT.longitude })

/** 上报端 v2 在附近活动时只报时间（Traccar 沿用上一个卫星点的坐标），网络估算放 netLat/netLon */
function report(patch: Partial<Position> = {}): Position {
  return {
    id: 9,
    deviceId: 1,
    fixTime: ago(40),
    deviceTime: ago(0.5),
    serverTime: ago(0.5),
    latitude: DOT.latitude,
    longitude: DOT.longitude,
    speedKmh: 0,
    accuracy: 8,
    provider: 'gps',
    geofenceIds: [],
    outdated: true,
    state: 'zone',
    ...patch,
  }
}

const FRESH: Presence = { freshness: 'fresh', live: null }
const situation = (p: Position, extra: Partial<Parameters<typeof situationOf>[0]> = {}): Situation =>
  situationOf({ position: p, presence: FRESH, history: { reports: [], fixes: [] }, offlineSince: null, updating: false, now: NOW, ...extra })
const lagging = (p: Position, extra: Partial<Parameters<typeof situationOf>[0]> = {}) => laggingOf(situation(p, extra), p, DOT)

describe('位置可能没跟上：在附近活动、网络估算离圆点超过 1 公里 + 估算误差', () => {
  it('车载 Wi-Fi 被当成家里的：估算在 3 公里外，提示并带上估算的位置和误差', () => {
    const p = report({ ...north(3000), netAcc: 500 })
    const l = lagging(p)
    expect(l).not.toBeNull()
    expect(l?.latitude).toBe(p.netLat)
    expect(l?.longitude).toBe(p.netLon)
    expect(l?.accuracy).toBe(500)
    expect(l?.distance).toBeCloseTo(3000, -1)
  })
  it('界线是 1 公里 + 估算误差：误差 500 米时 1.4 公里不提示、1.6 公里提示', () => {
    expect(lagging(report({ ...north(1400), netAcc: 500 }))).toBeNull()
    expect(lagging(report({ ...north(1600), netAcc: 500 }))).not.toBeNull()
  })
  it('没报误差按 0：1.1 公里就提示', () => {
    expect(lagging(report(north(1100)))).not.toBeNull()
  })
  it('估算近（屋里 Wi-Fi 漂几百米）：一切照旧', () => {
    expect(lagging(report({ ...north(400), netAcc: 60 }))).toBeNull()
  })
  it('最近一条没带估算（旧版 App、卫星点）：一切照旧', () => {
    expect(lagging(report())).toBeNull()
    expect(lagging(report({ netLat: 31 }))).toBeNull()
  })
  it('静止也提示（屋里定不到卫星，圆点停在早上的卫星点，估算在 2.8 公里外）', () => {
    expect(lagging(report({ ...north(2800), netAcc: 86, state: 'still' }))).not.toBeNull()
  })
  it('只在「在附近活动」「静止」时提示：在路上、旧版 App 不提示', () => {
    const far = { ...north(3000), netAcc: 100 }
    expect(laggingOf(situation(report({ ...far, state: 'moving' }), { presence: { freshness: 'fresh', live: 'locating' } }), report(far), DOT)).toBeNull()
    expect(lagging(report({ ...far, state: undefined }))).toBeNull()
  })
  it('先说的情况压过提示：失联、手机刚重启、正在更新、家长这边断网', () => {
    const p = report({ ...north(3000), netAcc: 100 })
    expect(lagging(p, { presence: { freshness: 'lost', live: null } })).toBeNull()
    expect(lagging(report({ ...north(3000), locked: true }))).toBeNull()
    expect(lagging(p, { updating: true })).toBeNull()
    expect(lagging(p, { offlineSince: ago(3) })).toBeNull()
  })
})

describe('提示的字', () => {
  const l = { latitude: 30.3, longitude: 120.14, accuracy: 500, distance: 3240 }
  it('有估算位置的地址：去掉省市区，「大概在 X 附近」', () => {
    expect(laggingText(l, '浙江省杭州市西湖区北山街道文三路 152 号浙江科贸大楼')).toBe('位置可能没跟上，大概在 文三路 152 号浙江科贸大楼附近')
  })
  it('地址还没查到或查不到：写离圆点多远', () => {
    expect(laggingText(l, null)).toBe('位置可能没跟上，大概在 3.2 公里外')
    expect(laggingText({ ...l, distance: 23_600 }, undefined)).toBe('位置可能没跟上，大概在 24 公里外')
  })
})

describe('用哪条的估算：最近 15 分钟内最新一条带 netLat 的', () => {
  /** 联系时间 m 分钟前的一条只报时间的上报 */
  const at = (m: number, id: number, patch: Partial<Position> = {}) => report({ id, deviceTime: ago(m), serverTime: ago(m), ...patch })
  it('最新一条没带估算、8 分钟前那条带了：用 8 分钟前的', () => {
    const withNet = at(8, 1, { ...north(2800), netAcc: 86 })
    expect(recentEstimate([at(12, 0), withNet, at(3, 2)], NOW)).toBe(withNet)
  })
  it('几条都带：用最新的', () => {
    const newer = at(2, 2, { ...north(3000) })
    expect(recentEstimate([at(8, 1, north(2800)), newer], NOW)).toBe(newer)
  })
  it('超过 15 分钟的估算不用', () => {
    expect(ESTIMATE_FRESH_MS).toBe(15 * MIN)
    expect(recentEstimate([at(16, 1, north(2800)), at(3, 2)], NOW)).toBeNull()
    expect(recentEstimate([at(14, 1, north(2800)), at(3, 2)], NOW)).not.toBeNull()
  })
  it('没有上报或都不带：null', () => {
    expect(recentEstimate([], NOW)).toBeNull()
    expect(recentEstimate([at(3, 1), at(1, 2, { netLat: 31 })], NOW)).toBeNull()
  })
})
