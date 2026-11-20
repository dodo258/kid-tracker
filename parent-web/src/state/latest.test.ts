import { describe, expect, it } from 'vitest'
import type { Position } from '../api/types'
import { hasUnknownDevices, initialLatest, latestReducer, type LatestAction, type LatestState } from './latest'

function pos(id: number, deviceId: number, minute: number): Position {
  return {
    id,
    deviceId,
    fixTime: new Date(Date.UTC(2026, 10, 16, 8, minute)),
    deviceTime: new Date(Date.UTC(2026, 10, 16, 8, minute)),
    latitude: 30.27,
    longitude: 120.14,
    speedKmh: 0,
    accuracy: 10,
    batteryLevel: 70,
    geofenceIds: [],
  }
}

function socket(positions: Position[]): LatestAction {
  return { type: 'socket', message: { devices: [], positions, events: [] } }
}

describe('设备和最新位置', () => {
  it('首拉失败 → WebSocket 推来位置 → 页面恢复正常', () => {
    let s: LatestState = latestReducer(initialLatest, { type: 'failed', error: '连不上服务器' })
    expect(s.loaded).toBe(false)
    expect(s.error).toBe('连不上服务器')

    s = latestReducer(s, socket([pos(7, 1, 40)]))
    expect(s.loaded).toBe(true)
    expect(s.error).toBeNull()
    expect(s.positions.get(1)?.id).toBe(7)
    // 设备列表还空着：要补拉设备
    expect(hasUnknownDevices(s)).toBe(true)

    s = latestReducer(s, { type: 'fetched', devices: [{ id: 1, name: '小宝' }], positions: [] })
    expect(hasUnknownDevices(s)).toBe(false)
    expect(s.devices.map((d) => d.name)).toEqual(['小宝'])
    expect(s.positions.get(1)?.id).toBe(7)
  })

  it('拉取成功清掉之前的错误', () => {
    let s = latestReducer(initialLatest, { type: 'failed', error: '服务器没有响应' })
    s = latestReducer(s, { type: 'fetched', devices: [{ id: 1, name: '小宝' }], positions: [pos(1, 1, 0)] })
    expect(s).toMatchObject({ loaded: true, error: null })
  })

  it('WebSocket 的空心跳不改变状态', () => {
    const s = latestReducer(initialLatest, { type: 'failed', error: 'x' })
    expect(latestReducer(s, socket([]))).toBe(s)
  })

  it('旧点不覆盖新点（离线补传）', () => {
    let s = latestReducer(initialLatest, socket([pos(9, 1, 40)]))
    s = latestReducer(s, socket([pos(5, 1, 10)]))
    expect(s.positions.get(1)?.id).toBe(9)
  })

  it('推来的设备更新名字、追加新设备', () => {
    let s = latestReducer(initialLatest, { type: 'fetched', devices: [{ id: 1, name: '小宝' }], positions: [] })
    s = latestReducer(s, {
      type: 'socket',
      message: { devices: [{ id: 1, name: '小宝贝' }, { id: 2, name: '大宝' }], positions: [], events: [] },
    })
    expect(s.devices.map((d) => d.name)).toEqual(['小宝贝', '大宝'])
  })
})

describe('最近 30 分钟的点', () => {
  it('拉的、推的、补拉的轨迹都进 history；呼吸点算上报不算新位置', () => {
    let s = latestReducer(initialLatest, { type: 'fetched', devices: [{ id: 1, name: '小宝' }], positions: [pos(1, 1, 30)] })
    s = latestReducer(s, socket([pos(2, 1, 35)]))
    const beat = { ...pos(3, 1, 35), deviceTime: new Date(Date.UTC(2026, 10, 16, 8, 38)) }
    s = latestReducer(s, socket([beat]))
    expect(s.history.get(1)?.fixes.map((p) => p.id)).toEqual([1, 2])
    expect(s.history.get(1)?.reports.map((p) => p.id)).toEqual([1, 2, 3])
    // 最新一条是呼吸点：第一行按它，位置还是 2 号点的
    expect(s.positions.get(1)?.id).toBe(3)
    s = latestReducer(s, { type: 'route', deviceId: 1, positions: [pos(4, 1, 20), pos(1, 1, 30), pos(9, 2, 25)] })
    expect(s.history.get(1)?.fixes.map((p) => p.id)).toEqual([4, 1, 2])
    // 补拉的轨迹不改最新位置，也不收别的设备的点
    expect(s.positions.get(1)?.id).toBe(3)
    expect(s.history.get(2)).toBeUndefined()
  })
  it('补拉的轨迹全是已有的点：状态不变', () => {
    const s = latestReducer(initialLatest, socket([pos(1, 1, 30)]))
    expect(latestReducer(s, { type: 'route', deviceId: 1, positions: [pos(1, 1, 30)] })).toBe(s)
  })
})

describe('手机时间快的超前点（审计 #2）', () => {
  const T = (m: number) => new Date(Date.UTC(2026, 10, 16, 8, m))
  const at = (id: number, fixMin: number, serverMin: number): Position => ({
    ...pos(id, 1, 0),
    fixTime: T(fixMin),
    deviceTime: T(fixMin),
    serverTime: T(serverMin),
  })

  it('先来一个快 2 小时的点，校时后补拉到的正常点成为最新', () => {
    let s = latestReducer(initialLatest, socket([at(1, 120, 0)]))
    s = latestReducer(s, { type: 'route', deviceId: 1, positions: [at(2, 1, 1), at(3, 2, 2)] })
    expect(s.positions.get(1)?.id).toBe(3)
    // 30 分钟窗口没被超前点挤掉
    expect(s.history.get(1)?.fixes.map((p) => p.id)).toEqual([1, 2, 3])
  })

  it('推来的正常点也能换掉超前点；补拉的旧点不换掉更新的', () => {
    let s = latestReducer(initialLatest, socket([at(1, 120, 0)]))
    s = latestReducer(s, socket([at(2, 1, 1)]))
    expect(s.positions.get(1)?.id).toBe(2)
    s = latestReducer(s, { type: 'route', deviceId: 1, positions: [at(1, 120, 0)] })
    expect(s.positions.get(1)?.id).toBe(2)
  })
})
