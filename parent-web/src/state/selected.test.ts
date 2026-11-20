import { describe, expect, it } from 'vitest'
import type { Position, SocketMessage, TraccarEvent } from '../api/types'
import { eventsOf, initialSelected, selectedReducer, trackOf, type SelectedAction, type SelectedState } from './selected'

const H = 3_600_000
const now = new Date(2026, 10, 18, 23, 50)
const ev = (id: number, type: string, hoursAgo: number, deviceId = 1): TraccarEvent => ({
  id,
  type,
  eventTime: new Date(now.getTime() - hoursAgo * H),
  deviceId,
  positionId: 0,
  geofenceId: 0,
})
const fix = (id: number, time: Date, deviceId = 1): Position => ({
  id,
  deviceId,
  fixTime: time,
  deviceTime: time,
  serverTime: time,
  latitude: 30,
  longitude: 120,
  speedKmh: 0,
  accuracy: 5,
  geofenceIds: [],
})
const msg = (m: Partial<SocketMessage>): SocketMessage => ({ devices: [], positions: [], events: [], ...m })
const run = (...actions: SelectedAction[]): SelectedState => actions.reduce(selectedReducer, initialSelected)

describe('推来的事件（审计 #11）', () => {
  it('只收告警、失联、到达/离开：上线这类事件不算告警', () => {
    const s = run(
      { type: 'events', deviceId: 1, events: [ev(1, 'alarm', 1)], at: now },
      { type: 'socket', selectedId: 1, trackOn: false, message: msg({ events: [ev(2, 'deviceOnline', 0), ev(3, 'geofenceEnter', 0)] }) },
    )
    expect(eventsOf(s, 1, now)?.map((e) => e.type)).toEqual(['geofenceEnter', 'alarm'])
  })

  it('页面一直开着：超过 24 小时的告警滚出去', () => {
    const s = run({ type: 'events', deviceId: 1, events: [ev(1, 'alarm', 23.5), ev(2, 'alarm', 1)], at: now })
    expect(eventsOf(s, 1, now)?.map((e) => e.id)).toEqual([2, 1])
    expect(eventsOf(s, 1, new Date(now.getTime() + H))?.map((e) => e.id)).toEqual([2])
  })
})

describe('今天的轨迹跨午夜（审计 #11）', () => {
  it('过了午夜只留新的一天的点', () => {
    const s = run(
      { type: 'track', deviceId: 1, positions: [fix(1, new Date(2026, 10, 18, 23, 40))] },
      { type: 'socket', selectedId: 1, trackOn: true, message: msg({ positions: [fix(2, new Date(2026, 10, 19, 0, 5))] }) },
    )
    expect(trackOf(s, 1, now)?.map((p) => p.id)).toEqual([1, 2])
    expect(trackOf(s, 1, new Date(2026, 10, 19, 0, 6))?.map((p) => p.id)).toEqual([2])
  })
})

describe('换孩子不串、点已选中的孩子不清空（审计 #5）', () => {
  it('数据记着是哪台设备的：换到 B（含自动换）时 A 的告警和轨迹不当成 B 的', () => {
    const s = run(
      { type: 'events', deviceId: 1, events: [ev(1, 'alarm', 1)], at: now },
      { type: 'track', deviceId: 1, positions: [fix(1, new Date(2026, 10, 18, 22))] },
    )
    expect(eventsOf(s, 2, now)).toBeNull()
    expect(trackOf(s, 2, now)).toBeNull()
    // 还是 A：原样在，不用重新拉
    expect(eventsOf(s, 1, now)?.length).toBe(1)
  })

  it('B 的请求晚回来、A 的晚回来都按设备号放：只认现在选中的那台', () => {
    const s = run(
      { type: 'events', deviceId: 2, events: [ev(5, 'alarm', 1, 2)], at: now },
      { type: 'events', deviceId: 1, events: [ev(1, 'alarm', 1)], at: now },
    )
    expect(eventsOf(s, 2, now)).toBeNull()
    expect(eventsOf(s, 1, now)?.map((e) => e.id)).toEqual([1])
  })
})

describe('重连补拉（审计 #3）', () => {
  it('补拉回来的和请求在途中推来的按 id 合并，都留着', () => {
    const s = run(
      { type: 'events', deviceId: 1, events: [ev(1, 'alarm', 3)], at: now },
      // 断线期间的求救只在补拉结果里；补拉请求在途中又推来一条
      { type: 'socket', selectedId: 1, trackOn: false, message: msg({ events: [ev(3, 'alarm', 0)] }) },
      { type: 'events', deviceId: 1, events: [ev(1, 'alarm', 3), ev(2, 'alarm', 1)], at: now },
    )
    expect(eventsOf(s, 1, now)?.map((e) => e.id)).toEqual([3, 2, 1])
  })

  it('首次拉取还没回来时推来的先收着，拉回来后一起显示', () => {
    let s = run({ type: 'socket', selectedId: 1, trackOn: false, message: msg({ events: [ev(9, 'alarm', 0)] }) })
    expect(eventsOf(s, 1, now)).toBeNull()
    s = selectedReducer(s, { type: 'events', deviceId: 1, events: [ev(1, 'alarm', 2)], at: now })
    expect(eventsOf(s, 1, now)?.map((e) => e.id)).toEqual([9, 1])
  })
})
