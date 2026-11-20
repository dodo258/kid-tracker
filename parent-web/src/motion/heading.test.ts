import { describe, expect, it } from 'vitest'
import { approachHeading, bearing, screenAngle, wrap180 } from './heading'

describe('方位角', () => {
  it('正北 0、正东 90、正南 180、正西 270', () => {
    const o = { latitude: 30, longitude: 120 }
    expect(bearing(o, { latitude: 30.001, longitude: 120 })).toBeCloseTo(0, 3)
    expect(bearing(o, { latitude: 30, longitude: 120.001 })).toBeCloseTo(90, 1)
    expect(bearing(o, { latitude: 29.999, longitude: 120 })).toBeCloseTo(180, 3)
    expect(bearing(o, { latitude: 30, longitude: 119.999 })).toBeCloseTo(270, 1)
  })
})

describe('箭头转向走近的那边', () => {
  it('350° → 10°：经过 0° 转 20°，不倒转 340°', () => {
    const a = approachHeading(350, 10, 100, 250)
    expect(a > 350 || a < 10).toBe(true)
    let h = 350
    for (let i = 0; i < 60; i++) h = approachHeading(h, 10, 16, 250)
    expect(h).toBeCloseTo(10, 0)
  })
  it('10° → 350°：往回转 20°', () => {
    const a = approachHeading(10, 350, 100, 250)
    expect(a < 10 || a > 350).toBe(true)
  })
  it('一帧不跳到位：先转一部分；时间够长才到位', () => {
    const a = approachHeading(0, 90, 16, 250)
    expect(a).toBeGreaterThan(0)
    expect(a).toBeLessThan(10)
    expect(approachHeading(0, 90, 5_000, 250)).toBeCloseTo(90, 3)
  })
  it('掉头 180°：转过去，不卡在中间', () => {
    let h = 0
    for (let i = 0; i < 100; i++) h = approachHeading(h, 180, 16, 250)
    expect(h).toBeCloseTo(180, 0)
  })
})

describe('地图转了以后箭头在屏幕上的角度', () => {
  it('行进方向减地图朝向，规整到 ±180', () => {
    expect(screenAngle(90, 30)).toBe(60)
    expect(screenAngle(10, 350)).toBe(20)
    expect(screenAngle(350, 10)).toBe(-20)
    expect(screenAngle(0, 0)).toBe(0)
  })
  it('wrap180', () => {
    expect(wrap180(540)).toBe(180)
    expect(wrap180(-190)).toBe(170)
    expect(wrap180(-180)).toBe(180)
  })
})
