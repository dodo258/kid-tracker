// WGS-84 ⇄ GCJ-02，逐行移植自 sdk/core/src/commonMain/kotlin/org/traccar/client/CoordinateTransform.kt。
// Traccar 存的是 WGS-84，画到高德底图上之前要转成 GCJ-02，在高德底图上选的点存回去之前转回 WGS-84；OSM 底图两头都不转。

import type { LatLon } from '../api/types'

// Krasovsky 1940 椭球（GCJ-02 用的）
const SEMI_MAJOR_AXIS = 6378245.0
const ECCENTRICITY_SQUARED = 0.006693421622965943 // Kotlin 里写的 0.00669342162296594323 取成 double 就是这个值
// 反算迭代到 1e-9 度（约 0.1 毫米）为止
const TOLERANCE = 1e-9
const MAX_ITERATIONS = 30

export function isOutsideChina(latitude: number, longitude: number): boolean {
  return !(longitude >= 72.004 && longitude <= 137.8347 && latitude >= 0.8293 && latitude <= 55.8271)
}

function gcj02Offset(latitude: number, longitude: number): LatLon {
  const x = longitude - 105.0
  const y = latitude - 35.0
  const PI = Math.PI
  let dLat = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
  dLat += ((20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0) / 3.0
  dLat += ((20.0 * Math.sin(y * PI) + 40.0 * Math.sin((y / 3.0) * PI)) * 2.0) / 3.0
  dLat += ((160.0 * Math.sin((y / 12.0) * PI) + 320.0 * Math.sin((y * PI) / 30.0)) * 2.0) / 3.0
  let dLon = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
  dLon += ((20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0) / 3.0
  dLon += ((20.0 * Math.sin(x * PI) + 40.0 * Math.sin((x / 3.0) * PI)) * 2.0) / 3.0
  dLon += ((150.0 * Math.sin((x / 12.0) * PI) + 300.0 * Math.sin((x / 30.0) * PI)) * 2.0) / 3.0

  const radLat = (latitude / 180.0) * PI
  const sinLat = Math.sin(radLat)
  const magic = 1 - ECCENTRICITY_SQUARED * sinLat * sinLat
  const sqrtMagic = Math.sqrt(magic)
  dLat = (dLat * 180.0) / (((SEMI_MAJOR_AXIS * (1 - ECCENTRICITY_SQUARED)) / (magic * sqrtMagic)) * PI)
  dLon = (dLon * 180.0) / ((SEMI_MAJOR_AXIS / sqrtMagic) * Math.cos(radLat) * PI)
  return { latitude: dLat, longitude: dLon }
}

export function wgs84ToGcj02(latitude: number, longitude: number): LatLon {
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude) || isOutsideChina(latitude, longitude)) {
    return { latitude, longitude }
  }
  const offset = gcj02Offset(latitude, longitude)
  return { latitude: latitude + offset.latitude, longitude: longitude + offset.longitude }
}

/** GCJ-02 → WGS-84：反复用正向公式逼近。国外和非法输入原样返回；是否在国内只按输入判断一次，和 sdk 一致 */
export function gcj02ToWgs84(latitude: number, longitude: number): LatLon {
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude) || isOutsideChina(latitude, longitude)) {
    return { latitude, longitude }
  }
  let lat = latitude
  let lon = longitude
  for (let i = 0; i < MAX_ITERATIONS; i++) {
    const offset = gcj02Offset(lat, lon)
    const dLat = latitude - (lat + offset.latitude)
    const dLon = longitude - (lon + offset.longitude)
    lat += dLat
    lon += dLon
    if (Math.abs(dLat) < TOLERANCE && Math.abs(dLon) < TOLERANCE) break
  }
  return { latitude: lat, longitude: lon }
}

/** 底图用的坐标系：高德、腾讯是 GCJ-02，OSM 是 WGS-84 */
export type MapDatum = 'gcj02' | 'wgs84'

/** Traccar 的 WGS-84 → 底图坐标（画点、画圈） */
export function toMapDatum(datum: MapDatum, p: LatLon): LatLon {
  return datum === 'gcj02' ? wgs84ToGcj02(p.latitude, p.longitude) : { latitude: p.latitude, longitude: p.longitude }
}

/** 底图上选的点 → 存进 Traccar 的 WGS-84 */
export function fromMapDatum(datum: MapDatum, p: LatLon): LatLon {
  return datum === 'gcj02' ? gcj02ToWgs84(p.latitude, p.longitude) : { latitude: p.latitude, longitude: p.longitude }
}
