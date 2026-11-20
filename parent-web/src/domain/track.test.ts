import { describe, expect, it } from 'vitest'
import type { Position } from '../api/types'
import { addPositions, EMPTY_HISTORY, steadyOf, trailOf, TRAIL_MS } from './track'

const MIN = 60_000
const now = new Date(2026, 10, 17, 20, 0)
const at = (minutesAgo: number) => new Date(now.getTime() - minutesAgo * MIN)

let nextId = 1
/** 一个点：定位 fixAgo 分钟前，上报 reportAgo 分钟前（呼吸点上报晚、定位时间沿用上一个） */
function point(fixAgo: number, reportAgo = fixAgo, north = 0, over: Partial<Position> = {}): Position {
  return {
    id: nextId++,
    deviceId: 1,
    fixTime: at(fixAgo),
    deviceTime: at(reportAgo),
    serverTime: at(reportAgo),
    latitude: 30 + north / 111_320,
    longitude: 120,
    speedKmh: 0,
    accuracy: 8,
    geofenceIds: [],
    provider: 'gps',
    ...over,
  }
}

describe('最近 30 分钟的点', () => {
  it('按定位时间排好：离线补传的旧点插到该在的位置', () => {
    const a = point(10, 10, 0)
    const c = point(2, 2, 200)
    const b = point(6, 0.5, 100) // 离线时记下、刚补传上来
    const h = addPositions(addPositions(EMPTY_HISTORY, [a, c]), [b])
    expect(h.fixes.map((p) => p.id)).toEqual([a.id, b.id, c.id])
    // 上报按服务器收到的先后：补传的那个最后到
    expect(h.reports.map((p) => p.id)).toEqual([a.id, c.id, b.id])
  })
  it('同一个点来两次（拉的轨迹和推送）只算一次', () => {
    const a = point(3)
    const h = addPositions(addPositions(EMPTY_HISTORY, [a]), [a])
    expect(h.fixes).toHaveLength(1)
    expect(h.reports).toHaveLength(1)
  })
  it('呼吸、状态点沿用上一个坐标和定位时间：算上报，不算新位置', () => {
    const fix = point(20, 20)
    const beat = { ...point(20, 15), latitude: fix.latitude, state: 'still' as const }
    const moving = { ...point(20, 0.2), latitude: fix.latitude, state: 'moving' as const }
    const h = addPositions(EMPTY_HISTORY, [fix, beat, moving])
    expect(h.fixes.map((p) => p.id)).toEqual([fix.id])
    expect(h.reports.map((p) => p.id)).toEqual([fix.id, beat.id, moving.id])
  })
  it('只留最近 30 分钟：最新的点总在', () => {
    const old = point(45)
    const recent = point(5)
    const h = addPositions(EMPTY_HISTORY, [old, recent])
    expect(h.fixes.map((p) => p.id)).toEqual([recent.id])
    const lone = addPositions(EMPTY_HISTORY, [point(120)])
    expect(lone.fixes).toHaveLength(1)
  })
  it('没有新东西时原样返回（React 不重算）', () => {
    const a = point(3)
    const h = addPositions(EMPTY_HISTORY, [a])
    expect(addPositions(h, [])).toBe(h)
    expect(addPositions(h, [a])).toBe(h)
  })
})

describe('轨迹线', () => {
  it('和圆点走同一串：屋里漂的 Wi-Fi 点不画进去', () => {
    const home = point(25, 25, 0, { provider: 'wifi', accuracy: 55 })
    const drift = point(24, 24, 15, { provider: 'wifi', accuracy: 52 })
    const walk1 = point(10, 10, 120)
    const walk2 = point(9.8, 9.8, 140)
    const h = addPositions(EMPTY_HISTORY, [home, drift, walk1, walk2])
    const steady = steadyOf(h)
    // 漂的那个点换成了圆点所在的点（home 的坐标），轨迹线上看不出来
    expect(steady.map((p) => [p.id, p.latitude, p.held])).toEqual([
      [home.id, home.latitude, undefined],
      [drift.id, home.latitude, true],
      [walk1.id, walk1.latitude, undefined],
      [walk2.id, walk2.latitude, undefined],
    ])
    expect(trailOf(steady, now).map((p) => p.id)).toEqual([home.id, drift.id, walk1.id, walk2.id])
  })
  it('圆点停着的点过了 30 分钟也留着：之后在它圈里漂的 Wi-Fi 点照样不挪', () => {
    // P20 11-17：07:53 最后一个点（精度 100 米），一直静止到 12:14 才来一个 53 米外的 Wi-Fi 点
    const home = point(270, 270, 0, { provider: 'wifi', accuracy: 100 })
    let h = addPositions(EMPTY_HISTORY, [home])
    const drift = point(1, 1, 53, { provider: 'wifi', accuracy: 100 })
    h = addPositions(h, [drift])
    expect(h.fixes.map((p) => p.id)).toEqual([drift.id])
    expect(steadyOf(h).map((p) => [p.id, p.latitude])).toEqual([
      [home.id, home.latitude],
      [drift.id, home.latitude],
    ])
    // 真走出去了（圈外）才挪
    const away = point(0.5, 0.5, 400, { provider: 'wifi', accuracy: 100 })
    h = addPositions(h, [away])
    expect(steadyOf(h).filter((p) => !p.held).map((p) => p.id)).toEqual([home.id, away.id])
  })
  it('只画最近 30 分钟（按现在算）', () => {
    const h = addPositions(EMPTY_HISTORY, [point(29, 29, 0), point(31, 31, 100)])
    expect(trailOf(steadyOf(h), now)).toHaveLength(1)
    expect(TRAIL_MS).toBe(30 * MIN)
  })
})
