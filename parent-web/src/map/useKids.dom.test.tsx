// @vitest-environment happy-dom
// 真的挂上 useKids（假地图、假 requestAnimationFrame）：圆点停稳了逐帧循环要停；放后台再回来，
// 第一帧不拿挂起前的时间算出几分钟的 dt，挂起前排的那一帧丢了也能重新叫醒循环
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import type { Map as MlMap } from 'maplibre-gl'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Position } from '../api/types'
import { useKids, type KidOnMap } from './useKids'

vi.mock('./baseMap', () => ({ EMPTY: { type: 'FeatureCollection', features: [] }, themeColor: () => '#000000' }))
const moves: [number, number][] = []
vi.mock('./kidMarker', () => ({
  createKidView: () => ({ setLook() {}, moveTo: (p: [number, number]) => moves.push(p), setArrow() {}, remove() {} }),
  createAccuracyView: () => ({ update() {}, remove() {} }),
}))

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true

const T0 = Date.UTC(2026, 10, 19, 8, 0)
const kid: KidOnMap = {
  id: 1,
  name: '小宝',
  freshness: 'fresh',
  live: null,
  hold: false,
  fixes: [
    {
      id: 1,
      deviceId: 1,
      latitude: 30.27,
      longitude: 120.14,
      fixTime: new Date(T0),
      deviceTime: new Date(T0),
      serverTime: new Date(T0),
      state: 'still',
      speedKmh: 0,
      accuracy: 5,
      provider: 'gps',
      geofenceIds: [],
    } satisfies Position,
  ],
}

/** 假地图：屏幕 400×800，每度 1 万像素，镜头中心可以被 jumpTo 挪 */
const PX_PER_DEG = 10_000
type FakeMap = { center: [number, number]; jumps: number }
function fakeMap(f: FakeMap): MlMap {
  const map = {
    getBearing: () => 0,
    getZoom: () => 15,
    getSource: () => undefined,
    isMoving: () => false,
    getContainer: () => ({ clientWidth: 400, clientHeight: 800 }),
    project: ([lng, lat]: [number, number]) => ({ x: 200 + (lng - f.center[0]) * PX_PER_DEG, y: 400 - (lat - f.center[1]) * PX_PER_DEG }),
    unproject: ([x, y]: [number, number]) => ({ lng: f.center[0] + (x - 200) / PX_PER_DEG, lat: f.center[1] - (y - 400) / PX_PER_DEG }),
    jumpTo({ center }: { center: { lng: number; lat: number } }) {
      f.jumps++
      f.center = [center.lng, center.lat]
    },
    on() {},
    off() {},
  }
  // 测试只用到上面这几个方法
  return map as unknown as MlMap
}

let frames = new Map<number, FrameRequestCallback>()
let nextId = 1
let visibility: DocumentVisibilityState = 'visible'
let root: Root
let host: HTMLDivElement

/** 跑一帧（浏览器的帧时间 ts 毫秒）；返回这一帧有没有排下一帧 */
function frame(ts: number): boolean {
  const due = [...frames.values()]
  frames = new Map()
  act(() => due.forEach((f) => f(ts)))
  return frames.size > 0
}

/** 假时钟：现在几点（毫秒） */
let nowMs = T0 + 60_000

function Harness({ map, following, kids = [kid] }: { map: MlMap; following: boolean; kids?: KidOnMap[] }) {
  useKids({
    map,
    base: 'osm',
    kids,
    selectedId: 1,
    onSelect: () => {},
    clock: () => nowMs,
    following: { current: following },
    bottomInset: 0,
    trail: null,
    dark: false,
  })
  return null
}

function mount(map: MlMap, following: boolean, kids?: KidOnMap[]) {
  act(() => root.render(<Harness map={map} following={following} kids={kids} />))
}

beforeEach(() => {
  nowMs = T0 + 60_000
  frames = new Map()
  nextId = 1
  moves.length = 0
  visibility = 'visible'
  vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility)
  vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => {
    frames.set(nextId, cb)
    return nextId++
  })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id))
  vi.stubGlobal('matchMedia', () => ({ matches: false }))
  host = document.createElement('div')
  root = createRoot(host)
})

afterEach(() => {
  act(() => root.unmount())
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('useKids 逐帧循环', () => {
  it('圆点停稳、镜头对准以后循环停下，不再逐帧挪圆点', () => {
    const f = { center: [120.14, 30.27] as [number, number], jumps: 0 }
    mount(fakeMap(f), true)
    let ts = 1000
    let running = frame(ts)
    for (let i = 0; i < 100 && running; i++) running = frame((ts += 16))
    expect(running).toBe(false)
    const moved = moves.length
    // 之后没人叫醒就一帧都不画
    expect(frames.size).toBe(0)
    expect(moves.length).toBe(moved)
  })

  it('放后台再回来：第一帧按 16 毫秒算，镜头接着缓缓追，不拿挂起前的时间一下跳过去', () => {
    // 镜头离圆点 300 像素，跟随要追好多帧
    const f = { center: [120.14 - 0.03, 30.27] as [number, number], jumps: 0 }
    mount(fakeMap(f), true)
    expect(frame(1000)).toBe(true)
    expect(frame(1016)).toBe(true)
    // 放后台：浏览器不再给帧
    visibility = 'hidden'
    act(() => document.dispatchEvent(new Event('visibilitychange')))
    // 60 秒后回来
    visibility = 'visible'
    act(() => document.dispatchEvent(new Event('visibilitychange')))
    const before = 200 + (120.14 - f.center[0]) * PX_PER_DEG - 200
    expect(frame(61_016)).toBe(true)
    const after = 200 + (120.14 - f.center[0]) * PX_PER_DEG - 200
    // dt=16 毫秒时这一帧只追上 1-exp(-16/90)≈16%；拿 60 秒算会一下追满
    expect(after / before).toBeGreaterThan(0.7)
  })

  it('挂起前排的那一帧丢了：回到前台重新叫醒循环', () => {
    const f = { center: [120.14 - 0.03, 30.27] as [number, number], jumps: 0 }
    mount(fakeMap(f), true)
    expect(frame(1000)).toBe(true)
    // iOS 挂起页面时排着的那一帧没回来
    frames.clear()
    visibility = 'visible'
    act(() => document.dispatchEvent(new Event('visibilitychange')))
    const jumps = f.jumps
    expect(frame(60_000)).toBe(true)
    expect(f.jumps).toBeGreaterThan(jumps)
  })
})

describe('useKids 在路上：最新一条只报了时间', () => {
  // 往东走，每 10 秒一个卫星点（约 10 米）
  const walk = (i: number, extra: Partial<Position> = {}): Position => ({
    ...kid.fixes[0],
    id: i + 1,
    longitude: 120.14 + i * 0.0001,
    fixTime: new Date(T0 + i * 10_000),
    deviceTime: new Date(T0 + i * 10_000),
    serverTime: new Date(T0 + i * 10_000),
    state: 'moving',
    speedKmh: 3.6,
    course: 90,
    ...extra,
  })
  const walking = (n: number, hold = false): KidOnMap => ({ ...kid, live: 'live', hold, fixes: Array.from({ length: n }, (_, i) => walk(i)) })
  /** 跑到停稳，返回圆点最后在的经度 */
  function settle(): number {
    let ts = 1000
    for (let i = 0; i < 200 && frame((ts += 16)); i++) nowMs += 16
    const last = moves.at(-1)
    if (!last) throw new Error('圆点一帧都没摆')
    return last[0]
  }

  // 上报端 v2 进楼、到站以后只报时间（运动摘要 0 步 0 颠簸）：以前圆点按最后一段的速度往前推，停在推出去的地方
  it('0 步 0 颠簸（hold）：圆点停在最后一个卫星点上，不往前推', () => {
    const map = fakeMap({ center: [120.14, 30.27], jumps: 0 })
    nowMs = T0
    mount(map, false, [walking(1)])
    nowMs = T0 + 10_000
    mount(map, false, [walking(2)])
    nowMs = T0 + 12_000
    // 下一条（只报时间）在这段还没走完时就到了
    mount(map, false, [walking(2, true)])
    nowMs = T0 + 40_000
    frame(500)
    expect(settle()).toBeCloseTo(120.1401, 6)
  })

  it('还在走（没有 hold）：点迟到时照常往前推一段', () => {
    const map = fakeMap({ center: [120.14, 30.27], jumps: 0 })
    nowMs = T0
    mount(map, false, [walking(1)])
    nowMs = T0 + 10_000
    mount(map, false, [walking(2)])
    nowMs = T0 + 25_000
    frame(500)
    const lng = moves.at(-1)?.[0] ?? 0
    expect(lng).toBeGreaterThan(120.1401 + 0.00003)
  })
})

