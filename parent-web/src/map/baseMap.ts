// 底图和图层：MapLibre（显卡绘制，双指缩放、旋转、惯性都顺手，和 Traccar 完整后台同一个地图库）+ 高德栅格瓦片（不要 Key），
// 高德打不开换腾讯，再不行换 OSM（设置里也能固定一家）。最多缩到看一个洲（geometry.minZoomFor，地图 2 级以上），高德 3 级瓦片起就够。
import type { FeatureCollection } from 'geojson'
import { setWorkerUrl, type StyleSpecification } from 'maplibre-gl'
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url'
import type { Base } from './geometry'

// 打包后 MapLibre 按自己文件的位置找 worker 会找错，明确告诉它
setWorkerUrl(workerUrl)

// scl=1 带地名、路名、店名；scl=2 是不带文字的底图（高清屏也别用，否则地图上一个字都没有）
const AMAP_TILES = ['1', '2', '3', '4'].map(
  (s) => `https://wprd0${s}.is.autonavi.com/appmaptile?x={x}&y={y}&z={z}&lang=zh_cn&size=1&scl=1&style=7`,
)
const amapTileUrl = (t: { z: number; x: number; y: number }) =>
  AMAP_TILES[(t.x + t.y) % AMAP_TILES.length].replace('{x}', `${t.x}`).replace('{y}', `${t.y}`).replace('{z}', `${t.z}`)
// 腾讯瓦片的 y 是 TMS 编号（从下往上数），源上设 scheme: 'tms'；3–19 级有图、带跨域头（2026-11-16 实测）
const TENCENT_TILES = ['0', '1', '2', '3'].map((s) => `https://rt${s}.map.gtimg.com/tile?z={z}&x={x}&y={y}&styleid=1000&scene=0`)
const OSM_TILES = ['https://tile.openstreetmap.org/{z}/{x}/{y}.png']
/** 高德瓦片 3–18 级（0–2 级是空白图），18 级以上放大现有瓦片 */
const AMAP_MIN_TILE_ZOOM = 3
const TILE_MAX_ZOOM = 18
/** 探测底图能不能打开：一张中国范围的 3 级瓦片，这么久没回来就换下一家 */
const PROBE_TIMEOUT_MS = 8000

export const BASE_LAYER = 'base'
/** 在这一层下面插新的底图（换底图时） */
export const FIRST_OVERLAY = 'places-fill'
export const EMPTY: FeatureCollection = { type: 'FeatureCollection', features: [] }

export function rasterSource(base: Base) {
  switch (base) {
    case 'amap':
      return { type: 'raster' as const, tiles: AMAP_TILES, tileSize: 256, minzoom: AMAP_MIN_TILE_ZOOM, maxzoom: TILE_MAX_ZOOM, attribution: '© 高德地图' }
    case 'tencent':
      return { type: 'raster' as const, tiles: TENCENT_TILES, scheme: 'tms' as const, tileSize: 256, minzoom: 3, maxzoom: TILE_MAX_ZOOM, attribution: '© 腾讯地图' }
    case 'osm':
      return { type: 'raster' as const, tiles: OSM_TILES, tileSize: 256, maxzoom: 19, attribution: '© OpenStreetMap' }
  }
}

/** 先试一张 3 级瓦片（中国范围），打得开就用这家 */
function probeTile(url: string, timeoutMs: number): Promise<boolean> {
  return new Promise((resolve) => {
    const img = new Image()
    const timer = setTimeout(() => finish(false), timeoutMs)
    function finish(ok: boolean) {
      clearTimeout(timer)
      img.onload = img.onerror = null
      resolve(ok)
    }
    img.onload = () => finish(true)
    img.onerror = () => finish(false)
    img.src = url
  })
}

/** 高德打不开（只试一张瓦片）就试腾讯，腾讯也打不开再换 OSM；高德能用返回 'amap' */
export async function pickBase(): Promise<Base> {
  if (await probeTile(amapTileUrl({ z: 3, x: 6, y: 3 }), PROBE_TIMEOUT_MS)) return 'amap'
  // 腾讯 TMS：3 级 y=3 对应 2^3-1-3=4
  const tencent = TENCENT_TILES[0].replace('{z}', '3').replace('{x}', '6').replace('{y}', '4')
  return (await probeTile(tencent, PROBE_TIMEOUT_MS)) ? 'tencent' : 'osm'
}

/** 高德没有免 Key 的深色瓦片：深色时反色（亮度上下限对调）、转回原色相、压暗降饱和，接近系统地图的深色 */
export function rasterPaint(dark: boolean) {
  return dark
    ? { 'raster-brightness-min': 0.9, 'raster-brightness-max': 0.06, 'raster-hue-rotate': 180, 'raster-saturation': -0.5, 'raster-contrast': -0.12 }
    : { 'raster-brightness-min': 0, 'raster-brightness-max': 1, 'raster-hue-rotate': 0, 'raster-saturation': 0, 'raster-contrast': 0 }
}

/**
 * 图层从下往上：底图、常去地点（淡色虚线圈）、网络估算圈（位置可能没跟上时，淡色细实线）、正在添加的地点（实线圈）、最近 30 分钟轨迹线（移动中）、今天的轨迹。
 * 孩子的圆点、精度圈、地点名字是地图上面的 DOM 元素（MapLibre Marker），跟着圆点逐帧走。
 * base 是一开始就画的底图：设置里固定了哪家就直接用它，不先等高德的首屏瓦片（高德连不上时要等它超时才出地图）
 */
export function initialStyle(dark: boolean, base: Base): StyleSpecification {
  return {
    version: 8,
    sources: {
      [BASE_LAYER]: rasterSource(base),
      places: { type: 'geojson', data: EMPTY },
      estimate: { type: 'geojson', data: EMPTY },
      draft: { type: 'geojson', data: EMPTY },
      trail: { type: 'geojson', data: EMPTY },
      track: { type: 'geojson', data: EMPTY },
    },
    layers: [
      { id: BASE_LAYER, type: 'raster', source: BASE_LAYER, paint: rasterPaint(dark) },
      { id: FIRST_OVERLAY, type: 'fill', source: 'places', paint: { 'fill-color': ['get', 'color'], 'fill-opacity': 0.05 } },
      {
        id: 'places-line',
        type: 'line',
        source: 'places',
        paint: { 'line-color': ['get', 'color'], 'line-opacity': 0.55, 'line-width': 1.5, 'line-dasharray': [3, 2] },
      },
      { id: 'estimate-fill', type: 'fill', source: 'estimate', paint: { 'fill-color': ['get', 'color'], 'fill-opacity': 0.08 } },
      { id: 'estimate-line', type: 'line', source: 'estimate', paint: { 'line-color': ['get', 'color'], 'line-opacity': 0.5, 'line-width': 1.5 } },
      { id: 'draft-fill', type: 'fill', source: 'draft', paint: { 'fill-color': ['get', 'color'], 'fill-opacity': 0.16 } },
      { id: 'draft-line', type: 'line', source: 'draft', paint: { 'line-color': ['get', 'color'], 'line-opacity': 0.9, 'line-width': 2 } },
      {
        id: 'trail-line',
        type: 'line',
        source: 'trail',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': ['get', 'color'], 'line-width': 4, 'line-opacity': 0.6 },
      },
      {
        id: 'track-line',
        type: 'line',
        source: 'track',
        filter: ['==', ['geometry-type'], 'LineString'],
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': ['get', 'color'], 'line-width': 5, 'line-opacity': 0.85 },
      },
      {
        id: 'track-start',
        type: 'circle',
        source: 'track',
        filter: ['==', ['geometry-type'], 'Point'],
        paint: { 'circle-radius': 5, 'circle-color': '#ffffff', 'circle-stroke-color': ['get', 'color'], 'circle-stroke-width': 3 },
      },
    ],
  }
}

// 深色模式：默认跟随系统；设置里固定了浅色、深色时看 html 上的 data-theme（ui/theme.ts）
const DARK_QUERY = '(prefers-color-scheme: dark)'
export function subscribeDark(cb: () => void) {
  const mq = window.matchMedia(DARK_QUERY)
  mq.addEventListener('change', cb)
  const mo = new MutationObserver(cb)
  mo.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
  return () => {
    mq.removeEventListener('change', cb)
    mo.disconnect()
  }
}
export function isDark(): boolean {
  const fixed = document.documentElement.dataset.theme
  return fixed === 'dark' || (fixed !== 'light' && window.matchMedia(DARK_QUERY).matches)
}

/** 颜色从 index.css 的主题变量取，和页面其他地方一致；深浅色切换后重新取 */
export function themeColor(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}
