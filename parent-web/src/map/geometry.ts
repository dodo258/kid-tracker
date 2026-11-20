// 地图上的坐标：Traccar 的点是 WGS-84，高德、腾讯底图上要先转 GCJ-02，OSM 底图上原样画；在地图上选的点反过来转回 WGS-84。
import type { Polygon } from 'geojson'
import type { Map as MlMap } from 'maplibre-gl'
import type { LatLon } from '../api/types'
import { fromMapDatum, toMapDatum, type MapDatum } from '../domain/coord'

/** 底图依次退：高德 → 腾讯（同样是 GCJ-02，不用另转坐标）→ OSM（WGS-84，国内慢） */
export type Base = 'amap' | 'tencent' | 'osm'

/** MapLibre 的 [经度, 纬度] */
export type LngLat = [number, number]

export const datumOf = (base: Base): MapDatum => (base === 'osm' ? 'wgs84' : 'gcj02')

/** WGS-84 → 这张底图上的 [经度, 纬度] */
export function project(base: Base, p: LatLon): LngLat {
  const g = toMapDatum(datumOf(base), p)
  return [g.longitude, g.latitude]
}

/** 地图上一个屏幕点（相对地图左上角）→ WGS-84 */
export function pointToWgs(map: MlMap, base: Base, x: number, y: number): LatLon {
  const c = map.unproject([x, y])
  return fromMapDatum(datumOf(base), { latitude: c.lat, longitude: c.lng })
}

/** 以米为半径的圆（64 边形）：常去地点、选点预览 */
export function circle(center: LngLat, meters: number): Polygon {
  const [lng, lat] = center
  const dLat = meters / 111320
  const dLng = meters / (111320 * Math.cos((lat * Math.PI) / 180))
  const ring: LngLat[] = []
  for (let i = 0; i <= 64; i++) {
    const a = (i / 64) * 2 * Math.PI
    ring.push([lng + dLng * Math.cos(a), lat + dLat * Math.sin(a)])
  }
  return { type: 'Polygon', coordinates: [ring] }
}

/** 这个纬度、这个缩放级别下一个屏幕像素是多少米（MapLibre 按 512 像素一块算） */
export function metersPerPixel(latitude: number, zoom: number): number {
  return (40075016.686 * Math.cos((latitude * Math.PI) / 180)) / (512 * 2 ** zoom)
}

/** 最小缩放时屏幕宽度能看这么多个经度：约一个洲（中国东西约 62°、欧洲约 50°），像打车软件，不再能缩到整个地球 */
export const CONTINENT_DEG = 60
/** 不管屏幕多宽，最小缩放都在这个范围里（MapLibre 按 512 像素世界算级别） */
const MIN_ZOOM_RANGE: [number, number] = [2, 5]

/** 地图最多缩小到哪一级：让 widthPx 宽的地图正好看 CONTINENT_DEG 个经度。手机（390 宽）约 2.2，电脑（1280 宽）约 3.9 */
export function minZoomFor(widthPx: number): number {
  const z = Math.log2((Math.max(1, widthPx) * 360) / (CONTINENT_DEG * 512))
  return Math.min(MIN_ZOOM_RANGE[1], Math.max(MIN_ZOOM_RANGE[0], z))
}
