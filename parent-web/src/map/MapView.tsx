// 全屏地图：只负责画，数据和状态从 domain、motion 来。
// 底图和图层见 baseMap，设备圆点和镜头跟随见 useKids，常去地点、选点预览、今天的轨迹在这里。
//
// 镜头（2026-11-19 重写，原因见 DECISIONS.md 同日「MapView 镜头重写」）：自动挪镜头的五种情况——居中到选中那台、所有孩子、
// 今天的轨迹、网络估算圈、「设为常去地点」的圈——各自只回答「框住哪些点（或居中到哪）」和「什么时候来一次」；
// 怎么框（范围、留白、让出底部卡片、不放大过默认级别）只在 frame / centerAt 一处。行为钉在 MapView.camera.dom.test.tsx。
import type { Feature } from 'geojson'
import { AttributionControl, Map as MlMap, Marker, type GeoJSONSource } from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { LocateFixed, Navigation2 } from 'lucide-react'
import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import type { BasePref, Geofence, LatLon, Position } from '../api/types'
import { spotFromMap, type Spot } from '../domain/pick'
import { parseCircle, type Circle } from '../domain/places'
import {
  BASE_LAYER,
  EMPTY,
  FIRST_OVERLAY,
  initialStyle,
  isDark,
  pickBase,
  rasterPaint,
  rasterSource,
  subscribeDark,
  themeColor,
} from './baseMap'
import { circle, datumOf, minZoomFor, pointToWgs, project, type Base, type LngLat } from './geometry'
import { isOnKid } from './kidMarker'
import { longPress } from './longPress'
import { followPoint, useKids, type KidOnMap } from './useKids'

export type { KidOnMap } from './useKids'

const MAX_ZOOM = 19
const DEFAULT_ZOOM = 16
/** 常去地点的圈自动缩放时离屏幕上下、左右边的距离（上面让开顶栏） */
const FIT_PAD_Y = 64
const FIT_PAD_X = 24
/** 长按和右键可能同时来（安卓 Chrome 长按也会发 contextmenu），这么久内只认第一个 */
const LONG_PRESS_DEDUPE_MS = 1000
/** 网络估算圈最小画这么大（米）：估算没报误差或误差很小时，缩到能看见圆点和估算的级别也还认得出是个圈 */
const ESTIMATE_MIN_M = 100

type Props = {
  kids: KidOnMap[]
  selectedId: number | null
  /** 点地图上的点选中这台 */
  onSelect: (id: number) => void
  /** 选中设备最近 30 分钟的轨迹线（移动中）；null 不画 */
  trail: readonly Position[] | null
  /** 选中设备今天的轨迹 */
  track: Position[] | null
  /** 选中设备的常去地点：淡色虚线圈加名字（只画 CIRCLE） */
  places: Geofence[]
  /** 这个地点先不画（打开它的卡片时，圈用 draft 画成实线） */
  hiddenPlaceId?: number
  /** 点地图上的地点圈或圈上的名字；不传就不响应（选点、填表时） */
  onPlaceClick?: (id: number) => void
  /** 正在添加的常去地点，实线圈预览；打开时、换范围、换圆心都会缩放到能看全整个圈 */
  draft: Circle | null
  /** 位置可能没跟上（domain/lagging）：选中那台的网络估算，画淡色估算圈；第一次出现时把圆点和圈一起框进屏幕。null 不画 */
  estimate?: Circle | null
  /** 选点模式：屏幕中间（底部卡片上面那块）固定一个图钉，圈跟着图钉走，圆心用 onPickMove 报出来 */
  picking?: boolean
  /** 选点模式下图钉对准的地方，地图每动一下报一次 */
  onPickMove?: (p: Spot) => void
  /** 选点模式下地图停下来时报图钉的位置，开始动时报 null（查图钉地址用） */
  onPickSettle?: (p: Spot | null) => void
  /** 每次变化都重新把圈放到图钉位置并缩放（搜索结果、我的位置飞过去；同一点再选一次也要飞） */
  refit?: number
  /** 手机长按、电脑右键地图上一点（WGS-84）；不传就不响应 */
  onLongPress?: (p: LatLon) => void
  /** 每次变化都把地图拉回选中的那台 */
  recenter: number
  /** 每次变化都把所有设备框进屏幕 */
  fitAll: number
  /** 底部卡片挡住的高度，居中时往上让 */
  bottomInset: number
  /** 点一下或拖动地图（不含点孩子的圆点）：用来收起展开的状态卡 */
  onMapTouch?: () => void
  /** 现在几点（毫秒）：圆点动画用；截图回放时是假时钟 */
  clock: () => number
  /** 设置里选的底图：自动（高德 → 腾讯 → OSM）或固定一家 */
  basePref: BasePref
}

/** 自动框选的几种用途和它们的留白（像素；bottom 另加底部卡片挡住的高度，上面让开顶栏） */
type FitPurpose = 'kids' | 'estimate' | 'track'
const FIT_PADDING: Record<FitPurpose, { top: number; side: number; bottom: number }> = {
  kids: { top: 96, side: 56, bottom: 48 },
  estimate: { top: 96, side: 56, bottom: 48 },
  track: { top: 80, side: 32, bottom: 24 },
}

/** 一组点（底图坐标）的外接范围 [西南, 东北]；调用方保证至少一个点 */
function boundsOf(points: readonly LngLat[]): [LngLat, LngLat] {
  const lngs = points.map((p) => p[0])
  const lats = points.map((p) => p[1])
  return [
    [Math.min(...lngs), Math.min(...lats)],
    [Math.max(...lngs), Math.max(...lats)],
  ]
}

/** 圆（底图坐标的圆心、米）的一圈点 */
function ringOf(center: LngLat, radius: number): LngLat[] {
  return circle(center, radius).coordinates[0].map((c): LngLat => [c[0], c[1]])
}

/** 把这些点框进屏幕：留白按用途，下面让出底部卡片，不放大过默认级别 */
function frame(map: MlMap, purpose: FitPurpose, points: readonly LngLat[], bottomInset: number) {
  const pad = FIT_PADDING[purpose]
  map.fitBounds(boundsOf(points), {
    padding: { top: pad.top, left: pad.side, right: pad.side, bottom: bottomInset + pad.bottom },
    maxZoom: DEFAULT_ZOOM,
    bearing: map.getBearing(),
  })
}

/** 把一点放到露出那块（底部卡片上面）的正中 */
function centerAt(map: MlMap, at: LngLat, zoom: number, bottomInset: number, duration: number) {
  map.easeTo({ center: at, zoom, offset: [0, -bottomInset / 2], duration })
}

/** 地点名字小标签，贴在圈的正上方；能点时（onClick）点它打开地点卡片 */
function placeLabel(name: string, onClick: (() => void) | null): HTMLElement {
  const el = document.createElement('div')
  el.className = onClick ? 'place-label place-label-tap' : 'place-label'
  el.textContent = name
  if (onClick) {
    el.setAttribute('role', 'button')
    el.addEventListener('click', (e) => {
      e.stopPropagation()
      onClick()
    })
  }
  return el
}

export function MapView({
  kids,
  selectedId,
  onSelect,
  trail,
  track,
  places,
  hiddenPlaceId,
  onPlaceClick,
  draft,
  estimate = null,
  picking = false,
  onPickMove,
  onPickSettle,
  refit = 0,
  onLongPress,
  recenter,
  fitAll,
  bottomInset,
  onMapTouch,
  clock,
  basePref,
}: Props) {
  const el = useRef<HTMLDivElement>(null)
  const [map, setMap] = useState<MlMap | null>(null)
  /** 自动时探测到能用的那家（先当高德）；设置里固定了哪家就是哪家 */
  const [probed, setProbed] = useState<Base>('amap')
  const base: Base = basePref === 'auto' ? probed : basePref
  const [bearing, setBearing] = useState(0)
  /** 选点时地图在动：图钉抬起来一点 */
  const [moving, setMoving] = useState(false)
  const dark = useSyncExternalStore(subscribeDark, isDark)
  /** 跟随选中的那台：拖地图、看轨迹、框所有孩子、选点时关掉，「回到孩子」、点圆点、换孩子时打开 */
  const following = useRef(true)
  /** 地图被拖走了（没在跟随）：右下角出「回到孩子」圆钮 */
  const [away, setAway] = useState(false)
  const follow = (on: boolean) => {
    following.current = on
    setAway(!on)
  }
  const followRef = useRef(follow)
  const insetRef = useRef(bottomInset)
  const onMapTouchRef = useRef(onMapTouch)
  const onPickMoveRef = useRef(onPickMove)
  const onPickSettleRef = useRef(onPickSettle)
  const onPlaceClickRef = useRef(onPlaceClick)
  const onLongPressRef = useRef(onLongPress)
  const baseRef = useRef(base)
  useEffect(() => {
    insetRef.current = bottomInset
    onMapTouchRef.current = onMapTouch
    onPickMoveRef.current = onPickMove
    onPickSettleRef.current = onPickSettle
    onPlaceClickRef.current = onPlaceClick
    onLongPressRef.current = onLongPress
    baseRef.current = base
    followRef.current = follow
  })

  const kidsHandle = useKids({ map, base, kids, selectedId, onSelect, clock, following, bottomInset, trail, dark })

  useEffect(() => {
    if (!el.current) return
    const container = el.current
    const m = new MlMap({
      container,
      style: initialStyle(isDark(), baseRef.current),
      center: [105, 35],
      zoom: 4,
      minZoom: minZoomFor(container.clientWidth),
      maxZoom: MAX_ZOOM,
      // 能转方向，不倾斜成 3D
      maxPitch: 0,
      pitchWithRotate: false,
      touchPitch: false,
      attributionControl: false,
    })
    m.addControl(new AttributionControl({ compact: false }), 'top-right')
    m.on('load', () => setMap(m))
    // 屏幕变宽变窄（转屏、电脑上拉窗口）：最多缩到看一个洲
    m.on('resize', () => m.setMinZoom(minZoomFor(container.clientWidth)))
    m.on('dragstart', () => {
      followRef.current(false)
      onMapTouchRef.current?.()
    })
    // 点在地点圈里打开那个地点（几个圈叠着取最小的）。
    // 孩子的圆点、地点名字是放在地图容器里的 DOM 元素，点它们的 click 会冒泡到这里：
    // 圆点在地点圈里时，点圆点会被当成点了圈，所以点在孩子圆点上的直接不管（圆点自己选中那台）
    m.on('click', (e) => {
      if (isOnKid(e.originalEvent.target)) return
      onMapTouchRef.current?.()
      const open = onPlaceClickRef.current
      if (!open) return
      let best: { id: number; radius: number } | undefined
      for (const f of m.queryRenderedFeatures(e.point, { layers: [FIRST_OVERLAY] })) {
        const id = Number(f.properties.id)
        const radius = Number(f.properties.radius)
        if (Number.isFinite(id) && (!best || radius < best.radius)) best = { id, radius }
      }
      if (best) open(best.id)
    })
    m.on('rotate', () => setBearing(m.getBearing()))

    // 长按（手机）、右键（电脑）地图上一点：报这一点的 WGS-84 坐标
    let lastPress = 0
    const pressAt = (x: number, y: number) => {
      const cb = onLongPressRef.current
      if (!cb || Date.now() - lastPress < LONG_PRESS_DEDUPE_MS) return
      lastPress = Date.now()
      navigator.vibrate?.(10)
      cb(pointToWgs(m, baseRef.current, x, y))
    }
    const press = longPress(pressAt)
    const canvas = m.getCanvasContainer()
    const touchPoint = (e: TouchEvent): [number, number] => {
      const r = canvas.getBoundingClientRect()
      const t = e.touches[0]
      return [t.clientX - r.left, t.clientY - r.top]
    }
    const onTouchStart = (e: TouchEvent) => press.start(e.touches.length, ...touchPoint(e))
    const onTouchMove = (e: TouchEvent) => {
      if (e.touches.length > 0) press.move(...touchPoint(e))
    }
    canvas.addEventListener('touchstart', onTouchStart, { passive: true })
    canvas.addEventListener('touchmove', onTouchMove, { passive: true })
    canvas.addEventListener('touchend', press.cancel)
    canvas.addEventListener('touchcancel', press.cancel)
    m.on('contextmenu', (e) => {
      e.originalEvent.preventDefault()
      press.cancel()
      pressAt(e.point.x, e.point.y)
    })
    // 截图脚本靠它判断地图画完了；手势测试按缩放级别换算像素和米
    m.on('idle', () => {
      container.dataset.idle = '1'
      container.dataset.zoom = String(m.getZoom())
    })
    m.on('dataloading', () => {
      delete container.dataset.idle
    })
    return () => {
      press.cancel()
      canvas.removeEventListener('touchstart', onTouchStart)
      canvas.removeEventListener('touchmove', onTouchMove)
      canvas.removeEventListener('touchend', press.cancel)
      canvas.removeEventListener('touchcancel', press.cancel)
      m.remove()
      setMap(null)
    }
  }, [])

  // 自动：高德打不开（只试一张瓦片）就试腾讯，腾讯也打不开再换 OSM；设置里固定了哪家就直接用
  useEffect(() => {
    if (basePref !== 'auto') return
    let cancelled = false
    pickBase()
      .then((b) => {
        if (!cancelled) setProbed(b)
      })
      .catch((err: unknown) => console.warn('底图探测失败', err))
    return () => {
      cancelled = true
    }
  }, [basePref])

  // 换底图（地图一开始画的是创建时的那家：设置里固定的，自动时先当高德）
  const drawnBase = useRef<Base>(base)
  useEffect(() => {
    if (!map || base === drawnBase.current) return
    drawnBase.current = base
    map.removeLayer(BASE_LAYER)
    map.removeSource(BASE_LAYER)
    map.addSource(BASE_LAYER, rasterSource(base))
    map.addLayer({ id: BASE_LAYER, type: 'raster', source: BASE_LAYER, paint: rasterPaint(isDark()) }, FIRST_OVERLAY)
  }, [map, base])

  // 深浅色切换
  useEffect(() => {
    if (!map) return
    for (const [k, v] of Object.entries(rasterPaint(dark))) map.setPaintProperty(BASE_LAYER, k as keyof ReturnType<typeof rasterPaint>, v)
  }, [map, dark])

  // 常去地点：淡色虚线圈 + 圈上方的名字
  const placesTappable = onPlaceClick !== undefined
  useEffect(() => {
    if (!map) return
    const features: Feature[] = []
    const labels: Marker[] = []
    const placeColor = themeColor('--place')
    for (const g of places) {
      const c = parseCircle(g.area)
      if (!c || g.id === hiddenPlaceId) continue
      features.push({ type: 'Feature', properties: { color: placeColor, id: g.id, radius: c.radius }, geometry: circle(project(base, c), c.radius) })
      const label = placeLabel(g.name, placesTappable ? () => onPlaceClickRef.current?.(g.id) : null)
      labels.push(
        new Marker({ element: label, anchor: 'bottom', offset: [0, -2] })
          .setLngLat(project(base, { latitude: c.latitude + c.radius / 111320, longitude: c.longitude }))
          .addTo(map),
      )
    }
    map.getSource<GeoJSONSource>('places')?.setData({ type: 'FeatureCollection', features })
    return () => {
      for (const l of labels) l.remove()
    }
  }, [map, base, places, hiddenPlaceId, dark, placesTappable])

  // 正在添加的常去地点：绿色实线圈。选点时圈画在图钉上，地图每动一下跟着重画，并把图钉的位置报出去
  const draftLat = draft?.latitude
  const draftLon = draft?.longitude
  const draftRadius = draft?.radius
  useEffect(() => {
    if (!map) return
    const source = map.getSource<GeoJSONSource>('draft')
    if (draftLat === undefined || draftLon === undefined || draftRadius === undefined) {
      source?.setData(EMPTY)
      return
    }
    const color = themeColor('--an')
    const draw = (center: LngLat) =>
      source?.setData({ type: 'FeatureCollection', features: [{ type: 'Feature', properties: { color }, geometry: circle(center, draftRadius) }] })
    if (!picking) {
      draw(project(base, { latitude: draftLat, longitude: draftLon }))
      return
    }
    const pin = (): Spot => {
      const [x, y] = followPoint(map, bottomInset)
      const c = map.unproject([x, y])
      return spotFromMap(datumOf(base), { latitude: c.lat, longitude: c.lng })
    }
    const onMove = () => {
      const [x, y] = followPoint(map, bottomInset)
      const c = map.unproject([x, y])
      draw([c.lng, c.lat])
      onPickMoveRef.current?.(pin())
    }
    const onStart = () => {
      setMoving(true)
      onPickSettleRef.current?.(null)
    }
    const onEnd = () => {
      setMoving(false)
      onPickSettleRef.current?.(pin())
    }
    onMove()
    if (!map.isMoving()) onPickSettleRef.current?.(pin())
    map.on('move', onMove)
    map.on('movestart', onStart)
    map.on('moveend', onEnd)
    return () => {
      map.off('move', onMove)
      map.off('movestart', onStart)
      map.off('moveend', onEnd)
      setMoving(false)
    }
  }, [map, base, picking, draftLat, draftLon, draftRadius, bottomInset, dark])

  // 打开「设为常去地点」或地点卡片、换范围、换圆心、进出选点、搜索结果和我的位置（refit）：把圆心放到图钉的位置，缩放到能看全整个圈（小圈不放大过默认级别）
  useEffect(() => {
    if (!map || draftLat === undefined || draftLon === undefined || draftRadius === undefined) return
    followRef.current(false)
    const center = project(base, { latitude: draftLat, longitude: draftLon })
    // 圆心对准图钉（不是框住的范围的中心），缩放只取能看全整个圈的那一级：上下留白一样，下面另让出卡片
    const camera = map.cameraForBounds(boundsOf(ringOf(center, draftRadius)), {
      padding: { top: FIT_PAD_Y, bottom: bottomInset + FIT_PAD_Y, left: FIT_PAD_X, right: FIT_PAD_X },
      bearing: map.getBearing(),
    })
    centerAt(map, center, Math.min(camera?.zoom ?? map.getZoom(), DEFAULT_ZOOM), bottomInset, 400)
  }, [map, base, draftLat, draftLon, draftRadius, picking, bottomInset, refit])

  // 网络估算圈：淡色细实线（常去地点是虚线），圆点不动
  const estLat = estimate?.latitude
  const estLon = estimate?.longitude
  const estRadius = estimate ? Math.max(estimate.radius, ESTIMATE_MIN_M) : undefined
  useEffect(() => {
    if (!map) return
    const source = map.getSource<GeoJSONSource>('estimate')
    if (estLat === undefined || estLon === undefined || estRadius === undefined) {
      source?.setData(EMPTY)
      return
    }
    const color = themeColor('--mute')
    const center = project(base, { latitude: estLat, longitude: estLon })
    source?.setData({ type: 'FeatureCollection', features: [{ type: 'Feature', properties: { color }, geometry: circle(center, estRadius) }] })
  }, [map, base, estLat, estLon, estRadius, dark])

  // 今天的轨迹：第一次画出来时缩放到整条路线，之后实时追加的点不再挪动地图
  const fitted = useRef(false)
  useEffect(() => {
    if (!track) fitted.current = false
    if (!map) return
    const source = map.getSource<GeoJSONSource>('track')
    if (!track || track.length < 2) {
      source?.setData(EMPTY)
      return
    }
    const coords = track.map((p) => project(base, p))
    const color = themeColor('--an')
    source?.setData({
      type: 'FeatureCollection',
      features: [
        { type: 'Feature', properties: { color }, geometry: { type: 'LineString', coordinates: coords } },
        { type: 'Feature', properties: { color }, geometry: { type: 'Point', coordinates: coords[0] } },
      ],
    })
    if (!fitted.current) {
      fitted.current = true
      followRef.current(false)
      frame(map, 'track', coords, insetRef.current)
    }
  }, [map, base, track, dark])

  // 居中到选中的那台：首次有位置、换底图时直接跳过去；点「定位到孩子」、换孩子时飞过去（之后逐帧跟随交给 useKids）
  const hasSelected = kids.some((k) => k.id === selectedId && k.fixes.length > 0)
  const centerOn = (duration: number) => {
    if (!map || selectedId === null) return
    const dot = kidsHandle.dotOf(selectedId)
    if (dot) centerAt(map, dot, Math.max(map.getZoom(), DEFAULT_ZOOM), insetRef.current, duration)
  }
  const centerOnRef = useRef(centerOn)
  useEffect(() => {
    centerOnRef.current = centerOn
  })
  useEffect(() => {
    followRef.current(true)
    centerOnRef.current(500)
  }, [recenter])
  useEffect(() => {
    if (following.current && hasSelected) centerOnRef.current(0)
  }, [map, base, hasSelected])

  // 「所有孩子」：把所有设备框进屏幕（点了以后地图还没准备好或位置还没到，就等到了再框）
  const pendingFit = useRef(false)
  useEffect(() => {
    if (fitAll > 0) pendingFit.current = true
  }, [fitAll])
  useEffect(() => {
    if (!map || !pendingFit.current) return
    const coords = kids.map((k) => kidsHandle.dotOf(k.id)).filter((c): c is LngLat => c !== null)
    if (coords.length < 2) return
    pendingFit.current = false
    followRef.current(false)
    frame(map, 'kids', coords, insetRef.current)
  }, [map, base, kids, fitAll, kidsHandle])

  // 估算圈第一次出现时（换到这个孩子也算）把圆点和估算圈一起框进屏幕，不然离圆点 1 公里以上的圈在屏幕外看不到；
  // 手机从没定到过位置（没有圆点）时只框估算圈。之后估算更新不再挪地图，「回到孩子」照常回到圆点。选点、填表、看今天的轨迹时不框
  const estimateFitFor = useRef<number | null>(null)
  const busy = picking || draft !== null || track !== null
  useEffect(() => {
    if (estLat === undefined || estLon === undefined || estRadius === undefined) {
      estimateFitFor.current = null
      return
    }
    if (!map || selectedId === null || busy || estimateFitFor.current === selectedId) return
    const dot = kidsHandle.dotOf(selectedId)
    // 有圆点但还没摆上（地图刚建好）：等它摆上再框
    if (hasSelected && !dot) return
    estimateFitFor.current = selectedId
    followRef.current(false)
    const ring = ringOf(project(base, { latitude: estLat, longitude: estLon }), estRadius)
    frame(map, 'estimate', dot ? [dot, ...ring] : ring, insetRef.current)
  }, [map, base, kids, hasSelected, selectedId, busy, estLat, estLon, estRadius, kidsHandle])

  const rotated = Math.abs(bearing) > 0.5
  // 「回到孩子」：地图被拖走、看轨迹、框所有孩子以后才出现；选点、填表时不出
  const backToKid = away && hasSelected && !picking && draft === null
  return (
    <>
      {/* MapLibre 的样式会把地图容器改成 position: relative，所以全屏定位放在外面一层 */}
      <div className="absolute inset-0 bg-map">
        <div ref={el} className="h-full w-full" aria-label="地图" />
        {picking && (
          // 图钉尖对准底部卡片上面那块的正中；地图在动时图钉抬起来，落地的小影子不动
          <div className="pick-pin" style={{ top: `calc((100% - ${bottomInset}px) / 2)` }} data-moving={moving ? '1' : undefined} aria-hidden>
            <div className="pick-pin-body">
              <div className="pick-pin-head" />
              <div className="pick-pin-stem" />
            </div>
            <div className="pick-pin-ground" />
          </div>
        )}
      </div>
      {rotated && (
        <button
          type="button"
          onClick={() => map?.easeTo({ bearing: 0, duration: 300 })}
          aria-label="转回正北"
          className="absolute right-3 top-[calc(max(12px,env(safe-area-inset-top))+64px)] z-[1000] flex size-10 items-center justify-center rounded-full bg-float text-ink shadow-card"
        >
          <Navigation2 className="size-5 fill-current" style={{ transform: `rotate(${-bearing}deg)` }} strokeWidth={1.5} />
        </button>
      )}
      {backToKid && (
        <button
          type="button"
          onClick={() => {
            follow(true)
            centerOn(500)
          }}
          aria-label="回到孩子"
          title="回到孩子"
          style={{ bottom: bottomInset + 12 }}
          className="absolute right-3 z-[1000] flex size-11 items-center justify-center rounded-full bg-float text-ink shadow-card active:bg-press"
        >
          <LocateFixed size={20} strokeWidth={2} />
        </button>
      )}
    </>
  )
}
