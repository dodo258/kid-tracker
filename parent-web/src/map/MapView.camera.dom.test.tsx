// @vitest-environment happy-dom
// MapView 的镜头（2026-11-19 重写镜头部分前写，钉住要保留的行为）：真的挂上 MapView，maplibre 换成假地图，记下每次框选、居中的参数。
// - 首次有位置时跳到选中那台、点「回到孩子」飞过去：圆点放在露出那块的正中，不缩小到默认级别以下；
// - 「所有孩子」：框住所有圆点；
// - 网络估算圈第一次出现（换孩子也算）：框住圆点和整个圈，没有圆点时只框圈；之后估算挪了不再框；看今天的轨迹、选点、填表时不框；
// - 今天的轨迹：第一次画出来时框住整条，之后追加的点不挪地图；
// - 「设为常去地点」的圈：圆心放到图钉的位置，缩放到能看全整个圈，不放大过默认级别。
import { act } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Position } from '../api/types'
import { circle, type LngLat } from './geometry'
import type { KidOnMap } from './useKids'

type Bounds = [LngLat, LngLat]
type Padding = { top: number; bottom: number; left: number; right: number }
type Call =
  | { kind: 'fit'; bounds: Bounds; padding: Padding; maxZoom?: number }
  | { kind: 'ease'; center: LngLat; zoom?: number; offset?: [number, number]; duration?: number }
  | { kind: 'cameraFor'; bounds: Bounds; padding: Padding }

const calls: Call[] = []
const maps: { fire: (event: string) => void }[] = []

/** 框选范围统一成 [[西南], [东北]]：不管传的是 LngLatBounds 还是数组 */
function toBounds(b: unknown): Bounds {
  if (b instanceof FakeBounds) return [b.sw, b.ne]
  if (Array.isArray(b) && b.length === 2 && Array.isArray(b[0]) && Array.isArray(b[1])) {
    return [[Number(b[0][0]), Number(b[0][1])], [Number(b[1][0]), Number(b[1][1])]]
  }
  throw new Error(`认不出的范围：${JSON.stringify(b)}`)
}

function toLngLat(c: unknown): LngLat {
  if (Array.isArray(c)) return [Number(c[0]), Number(c[1])]
  if (c && typeof c === 'object' && 'lng' in c && 'lat' in c) return [Number(c.lng), Number(c.lat)]
  throw new Error(`认不出的坐标：${JSON.stringify(c)}`)
}

class FakeBounds {
  sw: LngLat
  ne: LngLat
  constructor(a: LngLat, b: LngLat) {
    this.sw = [Math.min(a[0], b[0]), Math.min(a[1], b[1])]
    this.ne = [Math.max(a[0], b[0]), Math.max(a[1], b[1])]
  }
  extend(c: LngLat) {
    this.sw = [Math.min(this.sw[0], c[0]), Math.min(this.sw[1], c[1])]
    this.ne = [Math.max(this.ne[0], c[0]), Math.max(this.ne[1], c[1])]
    return this
  }
}

vi.mock('maplibre-gl', () => {
  class FakeMap {
    handlers = new Map<string, (() => void)[]>()
    zoom = 15
    canvas = document.createElement('div')
    constructor() {
      maps.push({ fire: (event) => this.handlers.get(event)?.forEach((h) => h()) })
    }
    addControl() {}
    on(event: string, h: () => void) {
      this.handlers.set(event, [...(this.handlers.get(event) ?? []), h])
    }
    off() {}
    setMinZoom() {}
    getCanvasContainer() {
      return this.canvas
    }
    getContainer() {
      return { clientWidth: 400, clientHeight: 800 }
    }
    getBearing() {
      return 0
    }
    getZoom() {
      return this.zoom
    }
    isMoving() {
      return false
    }
    getSource() {
      return { setData() {} }
    }
    project([lng, lat]: LngLat) {
      return { x: lng, y: lat }
    }
    unproject([x, y]: [number, number]) {
      return { lng: x, lat: y }
    }
    jumpTo() {}
    queryRenderedFeatures() {
      return []
    }
    easeTo(o: { center: unknown; zoom?: number; offset?: [number, number]; duration?: number }) {
      calls.push({ kind: 'ease', center: toLngLat(o.center), zoom: o.zoom, offset: o.offset, duration: o.duration })
    }
    fitBounds(b: unknown, o: { padding: Padding; maxZoom?: number }) {
      calls.push({ kind: 'fit', bounds: toBounds(b), padding: o.padding, maxZoom: o.maxZoom })
    }
    cameraForBounds(b: unknown, o: { padding: Padding }) {
      calls.push({ kind: 'cameraFor', bounds: toBounds(b), padding: o.padding })
      return { zoom: 13 }
    }
    remove() {}
  }
  class Marker {
    setLngLat() {
      return this
    }
    addTo() {
      return this
    }
    remove() {}
  }
  return { Map: FakeMap, Marker, AttributionControl: class {}, LngLatBounds: FakeBounds }
})
vi.mock('maplibre-gl/dist/maplibre-gl.css', () => ({}))
vi.mock('./baseMap', () => ({
  BASE_LAYER: 'base',
  EMPTY: { type: 'FeatureCollection', features: [] },
  FIRST_OVERLAY: 'places-fill',
  initialStyle: () => ({}),
  isDark: () => false,
  pickBase: () => Promise.resolve('osm'),
  rasterPaint: () => ({}),
  rasterSource: () => ({}),
  subscribeDark: () => () => {},
  themeColor: () => '#000000',
}))
vi.mock('./kidMarker', () => ({
  createKidView: () => ({ setLook() {}, moveTo() {}, setArrow() {}, remove() {} }),
  createAccuracyView: () => ({ update() {}, remove() {} }),
  isOnKid: () => false,
}))

const { MapView } = await import('./MapView')

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true

const T0 = Date.UTC(2026, 10, 19, 8, 0)
const INSET = 120
const DEFAULT_ZOOM = 16

function fix(id: number, lng: number, lat: number): Position {
  const t = new Date(T0 + id * 1000)
  return { id, deviceId: id, latitude: lat, longitude: lng, fixTime: t, deviceTime: t, serverTime: t, state: 'still', speedKmh: 0, accuracy: 10, provider: 'gps', geofenceIds: [] }
}

function kid(id: number, at: LngLat | null): KidOnMap {
  return { id, name: `孩子${id}`, freshness: 'fresh', live: null, hold: false, fixes: at ? [fix(id, at[0], at[1])] : [] }
}

const A: LngLat = [120.14, 30.27]
const B: LngLat = [120.16, 30.29]
const EST = { latitude: 30.3, longitude: 120.2, radius: 500 }

type Props = Parameters<typeof MapView>[0]
const base: Props = {
  kids: [kid(1, A), kid(2, B)],
  selectedId: 1,
  onSelect: () => {},
  trail: null,
  track: null,
  places: [],
  draft: null,
  estimate: null,
  recenter: 0,
  fitAll: 0,
  bottomInset: INSET,
  clock: () => T0 + 60_000,
  basePref: 'osm',
}

let root: Root
let host: HTMLDivElement

function render(patch: Partial<Props> = {}) {
  act(() => root.render(<MapView {...base} {...patch} />))
}

/** 挂上、地图加载完（load 事件），清掉加载时的那次居中 */
function open(patch: Partial<Props> = {}) {
  render(patch)
  act(() => maps.at(-1)?.fire('load'))
}

const fits = () => calls.filter((c): c is Extract<Call, { kind: 'fit' }> => c.kind === 'fit')
const eases = () => calls.filter((c): c is Extract<Call, { kind: 'ease' }> => c.kind === 'ease')

function ringBounds(c: { latitude: number; longitude: number; radius: number }, extra: LngLat[] = []): Bounds {
  const pts = [...(circle([c.longitude, c.latitude], c.radius).coordinates[0] as LngLat[]), ...extra]
  return [
    [Math.min(...pts.map((p) => p[0])), Math.min(...pts.map((p) => p[1]))],
    [Math.max(...pts.map((p) => p[0])), Math.max(...pts.map((p) => p[1]))],
  ]
}

function expectBounds(got: Bounds | undefined, want: Bounds) {
  expect(got).toBeDefined()
  for (let i = 0; i < 2; i++) for (let j = 0; j < 2; j++) expect(got?.[i][j]).toBeCloseTo(want[i][j], 9)
}

const KIDS_PADDING = { top: 96, left: 56, right: 56, bottom: INSET + 48 }

beforeEach(() => {
  calls.length = 0
  maps.length = 0
  vi.stubGlobal('requestAnimationFrame', () => 1)
  vi.stubGlobal('cancelAnimationFrame', () => {})
  vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener() {}, removeEventListener() {} }))
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
    },
  )
  host = document.createElement('div')
  document.body.append(host)
  root = createRoot(host)
})

afterEach(() => {
  act(() => root.unmount())
  host.remove()
  vi.unstubAllGlobals()
})

describe('居中到选中的那台', () => {
  it('地图加载完、有圆点：直接跳过去，圆点在露出那块的正中，缩放不低于默认级别', () => {
    open()
    const e = eases().at(-1)
    expect(e?.center).toEqual(A)
    expect(e?.offset).toEqual([0, -INSET / 2])
    expect(e?.zoom).toBe(DEFAULT_ZOOM)
    expect(e?.duration).toBe(0)
  })
  it('点「回到孩子」、换孩子（recenter 变了）：飞过去', () => {
    open()
    calls.length = 0
    render({ recenter: 1, selectedId: 2 })
    const e = eases().at(-1)
    expect(e?.center).toEqual(B)
    expect(e?.duration).toBe(500)
  })
})

describe('所有孩子', () => {
  it('框住所有圆点，下面让出卡片', () => {
    open({ fitAll: 1 })
    const f = fits().at(-1)
    expectBounds(f?.bounds, [A, B])
    expect(f?.padding).toEqual(KIDS_PADDING)
    expect(f?.maxZoom).toBe(DEFAULT_ZOOM)
  })
})

describe('网络估算圈', () => {
  it('第一次出现：框住圆点和整个圈', () => {
    open({ estimate: EST })
    const f = fits().at(-1)
    expectBounds(f?.bounds, ringBounds(EST, [A]))
    expect(f?.padding).toEqual(KIDS_PADDING)
  })
  it('没有圆点（手机从没定到过位置）：只框圈', () => {
    open({ kids: [kid(1, null), kid(2, B)], estimate: EST })
    expectBounds(fits().at(-1)?.bounds, ringBounds(EST))
  })
  it('估算挪了不再框；换到别的孩子再框一次；圈没了又出现也再框', () => {
    open({ estimate: EST })
    expect(fits()).toHaveLength(1)
    render({ estimate: { ...EST, latitude: 30.31 } })
    expect(fits()).toHaveLength(1)
    render({ estimate: EST, selectedId: 2 })
    expect(fits()).toHaveLength(2)
    expectBounds(fits().at(-1)?.bounds, ringBounds(EST, [B]))
    render({ estimate: null, selectedId: 2 })
    render({ estimate: EST, selectedId: 2 })
    expect(fits()).toHaveLength(3)
  })
  it('看今天的轨迹、填「设为常去地点」时不框估算', () => {
    open({ estimate: EST, track: [fix(1, A[0], A[1]), fix(2, B[0], B[1])] })
    expect(fits().filter((f) => f.padding.top === KIDS_PADDING.top)).toHaveLength(0)
    calls.length = 0
    render({ estimate: null, track: null })
    render({ estimate: EST, draft: { latitude: A[1], longitude: A[0], radius: 200 } })
    expect(fits()).toHaveLength(0)
  })
})

describe('今天的轨迹', () => {
  it('第一次画出来框住整条，追加的点不挪地图；关了再开再框', () => {
    const track = [fix(1, A[0], A[1]), fix(2, B[0], B[1])]
    open({ track })
    expect(fits()).toHaveLength(1)
    expectBounds(fits()[0].bounds, [A, B])
    expect(fits()[0].padding).toEqual({ top: 80, left: 32, right: 32, bottom: INSET + 24 })
    render({ track: [...track, fix(3, 120.18, 30.3)] })
    expect(fits()).toHaveLength(1)
    render({ track: null })
    render({ track })
    expect(fits()).toHaveLength(2)
  })
  it('只有一个点不框', () => {
    open({ track: [fix(1, A[0], A[1])] })
    expect(fits()).toHaveLength(0)
  })
})

describe('「设为常去地点」的圈', () => {
  it('圆心放到图钉的位置，缩放到能看全整个圈，不放大过默认级别', () => {
    const draft = { latitude: 30.28, longitude: 120.15, radius: 1000 }
    open({ draft })
    const c = calls.find((x): x is Extract<Call, { kind: 'cameraFor' }> => x.kind === 'cameraFor')
    expectBounds(c?.bounds, ringBounds(draft))
    expect(c?.padding).toEqual({ top: 64, bottom: INSET + 64, left: 24, right: 24 })
    const e = eases().at(-1)
    expect(e?.center).toEqual([draft.longitude, draft.latitude])
    expect(e?.zoom).toBe(13)
    expect(e?.offset).toEqual([0, -INSET / 2])
    expect(e?.duration).toBe(400)
  })
})
