import { describe, expect, it } from 'vitest'
import type { LatLon } from '../api/types'
import { fromMapDatum, gcj02ToWgs84, toMapDatum, wgs84ToGcj02, type MapDatum } from './coord'
import { spotFromAmap, spotFromMap, spotFromWgs, type Spot } from './pick'
import { circleArea, distanceMeters } from './places'

// 方案第 3 节：底图（高德/腾讯 = GCJ-02，OSM = WGS-84）× 来源（拖图钉 / 搜索结果 / 我的位置），
// 每种组合都要对：存进 Traccar 的是 WGS-84，传给 regeo 的是 GCJ-02，地图飞过去后图钉对准的就是那个地方。
const meters = (a: LatLon, b: LatLon) => distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)

/** 杭州文三路一所学校：高德搜索返回的 GCJ-02，和它真正的 WGS-84 */
const SCHOOL_GCJ = { latitude: 30.278221, longitude: 120.147358 }
const SCHOOL_WGS = gcj02ToWgs84(SCHOOL_GCJ.latitude, SCHOOL_GCJ.longitude)
/** 家长手机浏览器定位（WGS-84） */
const PARENT_WGS = { latitude: 30.27139, longitude: 120.15322 }

/** 页面把 spot.wgs 交给地图，地图按底图画在哪 → 图钉对准那里时报回来的 Spot */
function flyAndPin(datum: MapDatum, spot: Spot): { onMap: LatLon; pinned: Spot } {
  const onMap = toMapDatum(datum, spot.wgs)
  return { onMap, pinned: spotFromMap(datum, onMap) }
}

describe('真坐标的前提：国内 GCJ-02 和 WGS-84 差几百米', () => {
  it('学校两种坐标差 > 300 米', () => {
    expect(meters(SCHOOL_GCJ, SCHOOL_WGS)).toBeGreaterThan(300)
  })
})

describe('拖图钉', () => {
  it('高德/腾讯底图：图钉坐标本来就是 GCJ-02，regeo 直接用，存之前转 WGS-84', () => {
    const pinOnMap = { latitude: 30.2756, longitude: 120.1442 }
    const s = spotFromMap('gcj02', pinOnMap)
    expect(s.gcj).toEqual(pinOnMap)
    expect(s.wgs).toEqual(gcj02ToWgs84(pinOnMap.latitude, pinOnMap.longitude))
    expect(meters(s.wgs, pinOnMap)).toBeGreaterThan(300)
    // 存进去的和原来地图坐标转换路径一致
    expect(s.wgs).toEqual(fromMapDatum('gcj02', pinOnMap))
  })

  it('OSM 底图：图钉坐标是 WGS-84，原样存；regeo 前转成 GCJ-02', () => {
    const pinOnMap = { latitude: 30.2756, longitude: 120.1442 }
    const s = spotFromMap('wgs84', pinOnMap)
    expect(s.wgs).toEqual(pinOnMap)
    expect(s.gcj).toEqual(wgs84ToGcj02(pinOnMap.latitude, pinOnMap.longitude))
    expect(meters(s.gcj, pinOnMap)).toBeGreaterThan(300)
  })
})

describe('搜索结果（高德返回 GCJ-02）', () => {
  it('高德/腾讯底图：地图上画在 GCJ-02 原坐标，存的是 WGS-84，regeo 用原坐标', () => {
    const { onMap, pinned } = flyAndPin('gcj02', spotFromAmap(SCHOOL_GCJ))
    expect(meters(onMap, SCHOOL_GCJ)).toBeLessThan(0.01)
    expect(meters(pinned.wgs, SCHOOL_WGS)).toBeLessThan(0.01)
    expect(meters(pinned.gcj, SCHOOL_GCJ)).toBeLessThan(0.01)
  })

  it('OSM 底图：也要转——地图上画在 WGS-84，存 WGS-84，regeo 仍是 GCJ-02 原坐标', () => {
    const { onMap, pinned } = flyAndPin('wgs84', spotFromAmap(SCHOOL_GCJ))
    expect(meters(onMap, SCHOOL_WGS)).toBeLessThan(0.01)
    expect(meters(onMap, SCHOOL_GCJ)).toBeGreaterThan(300)
    expect(meters(pinned.wgs, SCHOOL_WGS)).toBeLessThan(0.01)
    expect(meters(pinned.gcj, SCHOOL_GCJ)).toBeLessThan(0.01)
  })

  it('存成 CIRCLE 的是 WGS-84', () => {
    const area = circleArea({ ...spotFromAmap(SCHOOL_GCJ).wgs, radius: 200 })
    const r = SCHOOL_WGS
    expect(area).toBe(`CIRCLE (${Math.round(r.latitude * 1e6) / 1e6} ${Math.round(r.longitude * 1e6) / 1e6}, 200)`)
  })
})

describe('我的位置（浏览器定位 WGS-84）', () => {
  it('高德/腾讯底图：地图上画在转换后的 GCJ-02，存原来的 WGS-84', () => {
    const { onMap, pinned } = flyAndPin('gcj02', spotFromWgs(PARENT_WGS))
    expect(meters(onMap, PARENT_WGS)).toBeGreaterThan(300)
    expect(meters(pinned.wgs, PARENT_WGS)).toBeLessThan(0.01)
    expect(meters(pinned.gcj, wgs84ToGcj02(PARENT_WGS.latitude, PARENT_WGS.longitude))).toBeLessThan(0.01)
  })

  it('OSM 底图：两头都不转，regeo 用转换后的 GCJ-02', () => {
    const { onMap, pinned } = flyAndPin('wgs84', spotFromWgs(PARENT_WGS))
    expect(onMap).toEqual(PARENT_WGS)
    expect(pinned.wgs).toEqual(PARENT_WGS)
    expect(pinned.gcj).toEqual(wgs84ToGcj02(PARENT_WGS.latitude, PARENT_WGS.longitude))
  })
})

describe('国外的点', () => {
  it('不转换（GCJ-02 只在国内有偏移）', () => {
    const london = { latitude: 51.5074, longitude: -0.1278 }
    expect(spotFromAmap(london)).toEqual({ wgs: london, gcj: london })
    expect(spotFromWgs(london)).toEqual({ wgs: london, gcj: london })
  })
})
