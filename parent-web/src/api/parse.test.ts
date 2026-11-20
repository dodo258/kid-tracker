import { describe, expect, it } from 'vitest'
import { parseEvent, parseGeofences, parsePosition } from './parse'

const raw = { id: 1, deviceId: 2, fixTime: '2026-11-16T08:39:00.000+00:00', latitude: 30.27, longitude: 120.14 }

describe('地址和常去地点字段', () => {
  it('位置带地址和所在围栏', () => {
    const p = parsePosition({ ...raw, address: ' 教工路 18 号 ', geofenceIds: [11, 12] })
    expect(p?.address).toBe('教工路 18 号')
    expect(p?.geofenceIds).toEqual([11, 12])
  })
  it('Traccar 没有地址、不在围栏里时是 null', () => {
    const p = parsePosition({ ...raw, address: null, geofenceIds: null })
    expect(p?.address).toBeUndefined()
    expect(p?.geofenceIds).toEqual([])
  })
  it('到达事件带围栏号', () => {
    const e = parseEvent({ id: 5, type: 'geofenceEnter', eventTime: '2026-11-16T00:12:00.000+00:00', deviceId: 1, positionId: 9, geofenceId: 12 })
    expect(e?.geofenceId).toBe(12)
  })
  it('围栏列表丢掉缺字段的', () => {
    expect(parseGeofences([{ id: 1, name: '家', area: 'CIRCLE (30 120, 100)' }, { id: 2, name: '学校' }])).toEqual([
      { id: 1, name: '家', area: 'CIRCLE (30 120, 100)' },
    ])
  })
})

describe('运动摘要', () => {
  it('attributes 里的 steps、bumps（Traccar 把数字串存成小数）', () => {
    const p = parsePosition({ ...raw, attributes: { state: 'moving', steps: 0.0, bumps: 12.0 } })
    expect([p?.steps, p?.bumps]).toEqual([0, 12])
  })
  it('还没带运动摘要的 App：没有', () => {
    const p = parsePosition({ ...raw, attributes: { state: 'zone' } })
    expect([p?.steps, p?.bumps]).toEqual([undefined, undefined])
  })
})

describe('详细信息用到的字段', () => {
  it('net、wifiType、powerSave、outdated 原样带上', () => {
    const p = parsePosition({ ...raw, outdated: true, attributes: { net: 'hotspot', wifiType: 'hotspot', powerSave: true } })
    expect([p?.net, p?.wifiType, p?.powerSave, p?.outdated]).toEqual(['hotspot', 'hotspot', true, true])
  })
  it('locked（重启后还没解锁）只认 true', () => {
    expect(parsePosition({ ...raw, attributes: { locked: true } })?.locked).toBe(true)
    expect(parsePosition({ ...raw, attributes: { locked: 'true' } })?.locked).toBeUndefined()
    expect(parsePosition(raw)?.locked).toBeUndefined()
  })
  it('没有或类型不对时不带', () => {
    const p = parsePosition({ ...raw, outdated: false, attributes: { net: 3, powerSave: 'yes' } })
    expect([p?.net, p?.wifiType, p?.powerSave, p?.outdated]).toEqual([undefined, undefined, undefined, undefined])
  })
})

// App 心跳取不到新位置时不带坐标，Traccar 6.17 沿用上一个坐标和 fixTime，deviceTime 是心跳时间，outdated 为 true
describe('没坐标的心跳', () => {
  it('deviceTime 是心跳时间，fixTime 还是上一个真点', () => {
    const p = parsePosition({ ...raw, outdated: true, deviceTime: '2026-11-16T08:44:00.000+00:00' })
    expect(p?.fixTime.toISOString()).toBe('2026-11-16T08:39:00.000Z')
    expect(p?.deviceTime.toISOString()).toBe('2026-11-16T08:44:00.000Z')
  })
  it('serverTime 是服务器收到的时间，旧数据没有时为空', () => {
    expect(parsePosition({ ...raw, serverTime: '2026-11-16T08:44:02.000+00:00' })?.serverTime?.toISOString()).toBe('2026-11-16T08:44:02.000Z')
    expect(parsePosition(raw)?.serverTime).toBeUndefined()
  })
  it('普通的点没有 deviceTime 时按 fixTime', () => {
    expect(parsePosition(raw)?.deviceTime.toISOString()).toBe('2026-11-16T08:39:00.000Z')
  })
  it('服务器上之前没有位置时 Traccar 填 0,0、1980：留着这条（联系时间、电量、估算有用），标成没有位置', () => {
    const p = parsePosition({
      ...raw,
      latitude: 0,
      longitude: 0,
      outdated: true,
      fixTime: '1980-01-06T00:00:00.000+00:00',
      deviceTime: '2026-11-19T08:44:00.000+00:00',
      attributes: { batteryLevel: 80, netLat: 30.3, netLon: 120.1, netAcc: 90 },
    })
    expect(p?.unlocated).toBe(true)
    expect([p?.batteryLevel, p?.netLat, p?.netAcc]).toEqual([80, 30.3, 90])
  })
  it('REST 拉回来的没有 outdated（不进数据库）：只看 0,0 或 1980 也认得出', () => {
    expect(parsePosition({ ...raw, latitude: 0, longitude: 0 })?.unlocated).toBe(true)
    expect(parsePosition({ ...raw, fixTime: '1980-01-06T00:00:00.000+00:00' })?.unlocated).toBe(true)
  })
  it('真位置不标：只有一个是 0（赤道、本初子午线上）也不算', () => {
    expect(parsePosition(raw)?.unlocated).toBeUndefined()
    expect(parsePosition({ ...raw, latitude: 0 })?.unlocated).toBeUndefined()
  })
})

// 新版 App 每个点带 state（Traccar OsmAnd 协议把不认识的参数存进 attributes）
describe('三种状态和方向', () => {
  it('attributes.state 和 course', () => {
    const p = parsePosition({ ...raw, course: 45, attributes: { state: 'zone' } })
    expect(p?.state).toBe('zone')
    expect(p?.course).toBe(45)
  })
  it('不认识的 state、旧点没有 state：undefined，照旧按速度判断', () => {
    expect(parsePosition({ ...raw, attributes: { state: 'parked' } })?.state).toBeUndefined()
    expect(parsePosition(raw)?.state).toBeUndefined()
  })
})

describe('网络估算（上报端 v2：主坐标只放卫星点）', () => {
  it('attributes 里的 netLat、netLon、netAcc', () => {
    const p = parsePosition({ ...raw, attributes: { netLat: 30.31, netLon: 120.16, netAcc: 520.5, netSource: 'cell' } })
    expect([p?.netLat, p?.netLon, p?.netAcc]).toEqual([30.31, 120.16, 520.5])
  })
  it('没有或不是数字时不带', () => {
    const p = parsePosition({ ...raw, attributes: { netLat: '30.31' } })
    expect([p?.netLat, p?.netLon, p?.netAcc]).toEqual([undefined, undefined, undefined])
  })
})
