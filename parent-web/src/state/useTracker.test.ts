import { describe, expect, it } from 'vitest'
import type { Position } from '../api/types'
import { initialLatest, latestReducer } from './latest'
import { needsPull, serverPointers } from './useTracker'

const MIN = 60_000
const T = (m: number) => new Date(Date.UTC(2026, 10, 16, 8, 0) + m * MIN)
const at = (id: number, fixMin: number, serverMin: number): Position => ({
  id,
  deviceId: 1,
  fixTime: T(fixMin),
  deviceTime: T(fixMin),
  serverTime: T(serverMin),
  latitude: 30,
  longitude: 120,
  speedKmh: 0,
  accuracy: 10,
  geofenceIds: [],
})

describe('Traccar 指针停在超前点上时一直补拉（审计 #2，审查退回第 1 轮）', () => {
  it('补拉到校时后的正常点、页面的最新已经换掉，指针还在超前点上：继续补拉', () => {
    // 推来一个快 2 小时的点：Traccar 的指针定位时间 = 第 120 分钟
    const ahead = at(1, 120, 0)
    let pointers = serverPointers(new Map(), [ahead])
    let latest = latestReducer(initialLatest, { type: 'socket', message: { devices: [], positions: [ahead], events: [] } })
    expect(needsPull(pointers.get(1), T(0))).toBe(true)
    // 第一次补拉把正常点合进来：页面的最新是 id 2，但补拉的轨迹不动指针
    latest = latestReducer(latest, { type: 'route', deviceId: 1, positions: [at(2, 1, 1)] })
    expect(latest.positions.get(1)?.id).toBe(2)
    expect(needsPull(pointers.get(1), T(1))).toBe(true)
    // 之后孩子每分钟正常上报，Traccar 都不推：页面只能靠补拉；20 分钟后照样在拉，不会误报失联
    expect(needsPull(pointers.get(1), T(20))).toBe(true)
    latest = latestReducer(latest, { type: 'route', deviceId: 1, positions: [at(3, 20, 20)] })
    expect(latest.positions.get(1)?.id).toBe(3)
    // /api/positions 给的还是指针那条（超前点），指针不往回走
    pointers = serverPointers(pointers, [ahead, at(3, 20, 20)])
    expect(pointers.get(1)).toBe(T(120).getTime())
  })

  it('现在追上指针（快不到 5 分钟）以后不再补拉；指针换成正常点也不拉', () => {
    const pointers = serverPointers(new Map(), [at(1, 120, 0)])
    expect(needsPull(pointers.get(1), T(115))).toBe(false)
    expect(needsPull(serverPointers(new Map(), [at(5, 30, 30)]).get(1), T(30))).toBe(false)
    expect(needsPull(undefined, T(0))).toBe(false)
  })

  it('没有更新的指针：返回原来那份（不触发重渲染）', () => {
    const p = serverPointers(new Map(), [at(1, 10, 10)])
    expect(serverPointers(p, [at(0, 5, 11)])).toBe(p)
  })
})
