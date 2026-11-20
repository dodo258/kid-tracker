// 选位置时各来源的坐标怎么转（docs/specs/2026-11-16-places-v2.md 第 3 节）：
// - 存进 Traccar 一律 WGS-84；
// - 高德接口（inputtips、place/text 的 location，regeo 的入参）一律 GCJ-02，不管底图是哪家；
// - 地图上的点按底图：高德、腾讯是 GCJ-02，OSM 是 WGS-84；
// - 浏览器定位（navigator.geolocation）是 WGS-84。
// 页面里只传 Spot：wgs 拿去存、拿去画（MapView 再按底图转），gcj 拿去查地址。
import type { LatLon } from '../api/types'
import { gcj02ToWgs84, wgs84ToGcj02, type MapDatum } from './coord'

export type Spot = { wgs: LatLon; gcj: LatLon }

const copy = (p: LatLon): LatLon => ({ latitude: p.latitude, longitude: p.longitude })

/** 地图上的点（图钉对准的地方）：高德、腾讯底图上本来就是 GCJ-02，查地址直接用；OSM 上是 WGS-84，查地址前转 GCJ-02 */
export function spotFromMap(datum: MapDatum, p: LatLon): Spot {
  return datum === 'gcj02'
    ? { wgs: gcj02ToWgs84(p.latitude, p.longitude), gcj: copy(p) }
    : { wgs: copy(p), gcj: wgs84ToGcj02(p.latitude, p.longitude) }
}

/** 高德搜索结果：GCJ-02，存之前转 WGS-84（OSM 底图上也要转） */
export function spotFromAmap(gcj: LatLon): Spot {
  return { wgs: gcj02ToWgs84(gcj.latitude, gcj.longitude), gcj: copy(gcj) }
}

/** 浏览器定位、Traccar 里的点：WGS-84 */
export function spotFromWgs(wgs: LatLon): Spot {
  return { wgs: copy(wgs), gcj: wgs84ToGcj02(wgs.latitude, wgs.longitude) }
}
