import { describe, expect, it } from 'vitest'
import golden from './coord.golden.json'
import inverseGolden from './coord.inverse.golden.json'
import { fromMapDatum, gcj02ToWgs84, toMapDatum, wgs84ToGcj02 } from './coord'
import { circleArea, distanceMeters } from './places'

// coord.golden.json 是 sdk 里 Kotlin 版 wgs84ToGcj02 跑出来的结果（生成方法见 parent-web/README.md「坐标转换」）。
describe('wgs84ToGcj02 与 sdk 的 Kotlin 实现对齐', () => {
  it.each(golden)('$wgs', ({ wgs, gcj }) => {
    const r = wgs84ToGcj02(wgs[0], wgs[1])
    expect(Math.abs(r.latitude - gcj[0])).toBeLessThan(1e-12)
    expect(Math.abs(r.longitude - gcj[1])).toBeLessThan(1e-12)
  })

  it('国内的点会偏几百米', () => {
    const r = wgs84ToGcj02(39.9087, 116.3975)
    expect(r.longitude - 116.3975).toBeGreaterThan(0.005)
  })

  it('国外和非法输入原样返回', () => {
    expect(wgs84ToGcj02(51.5074, -0.1278)).toEqual({ latitude: 51.5074, longitude: -0.1278 })
    expect(wgs84ToGcj02(Number.NaN, 116)).toEqual({ latitude: Number.NaN, longitude: 116 })
    expect(wgs84ToGcj02(39.9, Number.POSITIVE_INFINITY).longitude).toBe(Number.POSITIVE_INFINITY)
  })
})

// coord.inverse.golden.json 是 sdk 里 Kotlin 版 gcj02ToWgs84 跑出来的结果（同一套生成方法）。
describe('gcj02ToWgs84 与 sdk 的 Kotlin 实现对齐', () => {
  it.each(inverseGolden)('$gcj', ({ gcj, wgs }) => {
    const r = gcj02ToWgs84(gcj[0], gcj[1])
    expect(Math.abs(r.latitude - wgs[0])).toBeLessThan(1e-12)
    expect(Math.abs(r.longitude - wgs[1])).toBeLessThan(1e-12)
  })

  it('国外和非法输入原样返回', () => {
    expect(gcj02ToWgs84(51.5074, -0.1278)).toEqual({ latitude: 51.5074, longitude: -0.1278 })
    expect(gcj02ToWgs84(Number.NaN, 116)).toEqual({ latitude: Number.NaN, longitude: 116 })
  })
})

describe('WGS-84 → GCJ-02 → WGS-84 往返', () => {
  it('国内范围内撒点，往返误差都小于 1 米', () => {
    let worst = 0
    for (let lat = 18; lat <= 53; lat += 0.7) {
      for (let lon = 74; lon <= 134; lon += 0.9) {
        const g = wgs84ToGcj02(lat, lon)
        const back = gcj02ToWgs84(g.latitude, g.longitude)
        worst = Math.max(worst, distanceMeters(lat, lon, back.latitude, back.longitude))
      }
    }
    expect(worst).toBeLessThan(1)
  })
})

describe('底图坐标系', () => {
  const school = { latitude: 30.2741, longitude: 120.1551 }

  it('高德、腾讯（GCJ-02）上选的点转回 WGS-84，往返不到 1 米', () => {
    const onMap = toMapDatum('gcj02', school)
    expect(distanceMeters(onMap.latitude, onMap.longitude, school.latitude, school.longitude)).toBeGreaterThan(100)
    const back = fromMapDatum('gcj02', onMap)
    expect(distanceMeters(back.latitude, back.longitude, school.latitude, school.longitude)).toBeLessThan(1)
  })

  it('OSM（WGS-84）两头都不转', () => {
    expect(toMapDatum('wgs84', school)).toEqual(school)
    expect(fromMapDatum('wgs84', school)).toEqual(school)
  })

  it('高德上选的点存成 CIRCLE：坐标是转回的 WGS-84', () => {
    // 高德上量的西湖文化广场附近一点（GCJ-02），转回后和 sdk 对拍数据一致
    const picked = fromMapDatum('gcj02', { latitude: 30.271771223200254, longitude: 120.15979447929637 })
    expect(circleArea({ ...picked, radius: 500 })).toBe('CIRCLE (30.2741 120.1551, 500)')
  })

  it('OSM 上选的点原样存', () => {
    const picked = fromMapDatum('wgs84', { latitude: 30.271771, longitude: 120.159794 })
    expect(circleArea({ ...picked, radius: 1000 })).toBe('CIRCLE (30.271771 120.159794, 1000)')
  })
})
