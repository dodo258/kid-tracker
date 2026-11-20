import { describe, expect, it } from 'vitest'
import type { Geofence, Position } from '../api/types'
import { circleArea, distanceMeters, nearText, parseCircle, placeOf, radiusChoices } from './places'

function pos(latitude: number, longitude: number, geofenceIds: number[] = []): Position {
  return { id: 1, deviceId: 1, fixTime: new Date(0), deviceTime: new Date(0), latitude, longitude, speedKmh: 0, accuracy: 10, geofenceIds }
}

const home: Geofence = { id: 11, name: '家', area: 'CIRCLE (30.27439 120.140581, 100)' }
const school: Geofence = { id: 12, name: '学校', area: 'CIRCLE (30.276749 120.145126, 200)' }
const bigArea: Geofence = { id: 13, name: '小区', area: 'CIRCLE (30.27439 120.140581, 500)' }
const polygon: Geofence = { id: 14, name: '公园', area: 'POLYGON ((30 120, 30.1 120, 30.1 120.1, 30 120))' }

describe('CIRCLE 字符串', () => {
  it('按 Traccar 的格式生成：纬度 经度, 半径', () => {
    expect(circleArea({ latitude: 30.27439, longitude: 120.140581, radius: 200 })).toBe('CIRCLE (30.27439 120.140581, 200)')
  })
  it('坐标保留 6 位小数，半径取整', () => {
    expect(circleArea({ latitude: 30.123456789, longitude: -120.987654321, radius: 199.6 })).toBe('CIRCLE (30.123457 -120.987654, 200)')
  })
  it('生成的能解析回来', () => {
    const c = { latitude: 22.543096, longitude: 114.057865, radius: 500 }
    expect(parseCircle(circleArea(c))).toEqual(c)
  })
  it('解析完整后台画的圆（Traccar toWkt 带小数半径）', () => {
    expect(parseCircle('CIRCLE (39.9042 116.4074, 150.5)')).toEqual({ latitude: 39.9042, longitude: 116.4074, radius: 150.5 })
  })
  it('多边形、折线、格式不对都不算圆', () => {
    expect(parseCircle(polygon.area)).toBeUndefined()
    expect(parseCircle('LINESTRING (30 120, 31 121)')).toBeUndefined()
    expect(parseCircle('CIRCLE (30.1, 200)')).toBeUndefined()
    expect(parseCircle('CIRCLE (30.1 120.2 5, 200)')).toBeUndefined()
    expect(parseCircle('CIRCLE (abc 120.2, 200)')).toBeUndefined()
    expect(parseCircle('CIRCLE (30.1 120.2, 0)')).toBeUndefined()
    expect(parseCircle('CIRCLE (95 120.2, 100)')).toBeUndefined()
    expect(parseCircle('')).toBeUndefined()
  })
})

describe('距离', () => {
  it('和 Traccar DistanceCalculator 一致：纬度差 0.001 度约 111 米', () => {
    expect(distanceMeters(30, 120, 30.001, 120)).toBeCloseTo(111.32, 1)
    expect(distanceMeters(30, 120, 30, 120)).toBe(0)
  })
})

describe('孩子在哪个常去地点', () => {
  it('落在圆里就算在，刚添加的地点不用等服务器标', () => {
    expect(placeOf(pos(30.2745, 120.1406), [home, school])?.name).toBe('家')
  })
  it('在圆外不算', () => {
    expect(placeOf(pos(30.29, 120.16), [home, school])).toBeUndefined()
  })
  it('服务器标了 geofenceIds 的也算（完整后台画的多边形）', () => {
    expect(placeOf(pos(30.05, 120.05, [14]), [polygon])?.name).toBe('公园')
  })
  it('同时在几个地点里取最小的', () => {
    expect(placeOf(pos(30.27439, 120.140581), [bigArea, home])?.name).toBe('家')
  })
  it('没有常去地点', () => {
    expect(placeOf(pos(30.27439, 120.140581), [])).toBeUndefined()
  })
})

describe('地址那一行', () => {
  it('有地址写「在 …附近」', () => {
    expect(nearText('浙江省杭州市西湖区教工路 18 号')).toBe('在 浙江省杭州市西湖区教工路 18 号附近')
  })
  it('没有地址或只有空格不显示', () => {
    expect(nearText(undefined)).toBeNull()
    expect(nearText('')).toBeNull()
    expect(nearText('   ')).toBeNull()
  })
})

describe('改范围可选的半径', () => {
  it('常用四档', () => {
    expect(radiusChoices(200)).toEqual([100, 200, 500, 1000])
  })
  it('完整后台画的 300 米插进去', () => {
    expect(radiusChoices(300)).toEqual([100, 200, 300, 500, 1000])
    expect(radiusChoices(1500.4)).toEqual([100, 200, 500, 1000, 1500])
  })
})
