// @vitest-environment happy-dom
// 真的挂上 useTracker（内存里的假接口、假定时器）看时序：推送不通时告警还在查（审计 11-18 #1），回前台刷新没结果前不判失联（#5）
import { act, useEffect } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Api, Position, SocketMessage, TraccarEvent } from '../api/types'
import { offlinePresence, parentOfflineSince, presenceOf } from '../domain/presence'
import { statusLine } from '../domain/status'
import { eventsStale } from './selected'
import { useTracker, type Tracker } from './useTracker'

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true

const T0 = Date.UTC(2026, 10, 18, 12, 0)
const at = (s: number) => new Date(T0 + s * 1000)
const position = (s: number, state: Position['state']): Position => ({
  id: 1 + s,
  deviceId: 1,
  latitude: 30.27,
  longitude: 120.14,
  fixTime: at(s),
  deviceTime: at(s),
  serverTime: at(s),
  state,
  speedKmh: 0,
  accuracy: 5,
  provider: 'gps',
  geofenceIds: [],
})
const sos: TraccarEvent = { id: 9, deviceId: 1, type: 'alarm', alarm: 'sos', eventTime: at(45), positionId: 0, geofenceId: 0 }

type Fake = {
  sec: number
  latestCalls: number
  eventCalls: number
  /** 下一次 latestPositions 先挂着，等 finish 再回 */
  holdLatest: boolean
  finish: ((ps: Position[]) => void) | null
  failEvents: boolean
  pushOpen: boolean
}

function fakeApi(f: Fake): Api {
  const unused = () => Promise.reject(new Error('测试里不用'))
  return {
    now: () => at(f.sec),
    devices: async () => [{ id: 1, name: '小宝' }],
    latestPositions() {
      f.latestCalls++
      if (!f.holdLatest) return Promise.resolve([position(0, 'still')])
      return new Promise((resolve) => {
        f.finish = resolve
      })
    },
    openSession: async () => {},
    // 推送：连上就断（首帧之前），或者一直挂着只发首帧
    subscribe(onMessage: (m: SocketMessage) => void, onClose: () => void) {
      if (f.pushOpen) queueMicrotask(() => onMessage({ devices: [], positions: [], events: [] }))
      else queueMicrotask(onClose)
      return () => {}
    },
    async events() {
      f.eventCalls++
      if (f.failEvents) throw new Error('网络不通')
      return f.sec < 45 ? [] : [sos]
    },
    positionsById: async () => [],
    route: async () => [],
    checkAuth: async () => {},
    login: unused,
    createToken: unused,
    endSession: unused,
    revokeToken: unused,
    geofences: async () => [],
    addGeofence: unused,
    updateGeofence: unused,
    deleteGeofence: unused,
    unlinkGeofence: unused,
    amapTips: unused,
    amapSearch: unused,
    amapRegeo: unused,
  }
}

let root: Root
let t: Tracker
let fake: Fake

function Probe({ api, onTracker }: { api: Api; onTracker: (t: Tracker) => void }) {
  const tracker = useTracker(api, 'tok', () => {})
  useEffect(() => onTracker(tracker))
  return null
}

async function mount(patch: Partial<Fake> = {}) {
  fake = { sec: 0, latestCalls: 0, eventCalls: 0, holdLatest: false, finish: null, failEvents: false, pushOpen: false, ...patch }
  const api = fakeApi(fake)
  root = createRoot(document.createElement('div'))
  await act(async () =>
    root.render(
      <Probe
        api={api}
        onTracker={(x) => {
          t = x
        }}
      />,
    ),
  )
}

async function tick(seconds: number) {
  for (let i = 0; i < seconds; i++) {
    fake.sec++
    await act(async () => {
      vi.advanceTimersByTime(1000)
    })
  }
}

/** 卡片第一行（和 App/StatusSheet 一样拼） */
function headline(): string {
  if (!t.position) return ''
  const offlineSince = parentOfflineSince(t, t.now)
  const presence = offlineSince ? offlinePresence(t.position, offlineSince) : presenceOf(t.position, t.now)
  return statusLine({
    position: t.position,
    presence,
    history: t.selectedHistory,
    placeName: undefined,
    offlineSince,
    updating: t.updating,
    slow: t.slow,
    now: t.now,
  }).text
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'setInterval', 'clearTimeout', 'clearInterval'] })
})
afterEach(async () => {
  await act(async () => root.unmount())
  vi.useRealTimers()
})

describe('推送不通时告警照样更新（审计 11-18 #1）', () => {
  it('推送首帧前就断了：每 30 秒查一次事件，第 45 秒的求救 90 秒内能看到', async () => {
    await mount()
    expect(t.events).toEqual([])
    await tick(90)
    expect(fake.eventCalls).toBeGreaterThanOrEqual(3)
    expect(t.events?.map((e) => e.id)).toEqual([9])
    expect(eventsStale(t.eventsAsOf, t.now)).toBe(false)
  })

  it('查询失败：记下错误，告警只核实到上次查成功那一刻；重试成功后恢复', async () => {
    await mount()
    fake.failEvents = true
    await tick(31)
    expect(t.eventsError).toBe('网络不通')
    expect(t.eventsAsOf?.getTime()).toBe(at(0).getTime())
    // 「现在」每 30 秒走一次：到第 120 秒时离上次查成功已经 2 分钟
    await tick(90)
    expect(eventsStale(t.eventsAsOf, t.now)).toBe(true)
    fake.failEvents = false
    await act(async () => t.reloadEvents())
    expect(t.eventsError).toBeNull()
    expect(t.events?.map((e) => e.id)).toEqual([9])
    expect(eventsStale(t.eventsAsOf, t.now)).toBe(false)
  })

  it('推送一直通着：不轮询事件（推来的就是最新的），告警算核实到现在', async () => {
    await mount({ pushOpen: true })
    const calls = fake.eventCalls
    await tick(90)
    expect(t.live).toBe(true)
    expect(fake.eventCalls).toBe(calls)
    expect(eventsStale(t.eventsAsOf, t.now)).toBe(false)
  })
})

describe('回到前台：刷新有结果之前不判失联（审计 11-18 #5）', () => {
  it('后台 1000 秒回来，刷新第 12 秒才回：第 10 秒说「同步较慢」，不说「已失联」', async () => {
    await mount()
    expect(headline()).toBe('静止')
    fake.sec = 1000
    fake.holdLatest = true
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'))
    })
    expect(headline()).toBe('正在更新…')
    await tick(10)
    expect(headline()).toMatch(/^同步较慢/)
    expect(headline()).not.toMatch(/失联/)
    await tick(2)
    await act(async () => fake.finish?.([position(1012, 'zone')]))
    expect(t.updating).toBe(false)
    expect(headline()).toBe('在附近活动')
  })
})
