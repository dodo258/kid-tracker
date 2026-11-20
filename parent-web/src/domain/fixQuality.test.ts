import { describe, expect, it } from 'vitest'
import type { Position } from '../api/types'
import { accuracyText, atRest, fixSource, isRough, movesDot, steadyFixes, STOP_KMH } from './fixQuality'

/** 从 (30, 120) 往北 north 米、往东 east 米 */
const at = (north: number, east = 0, accuracy = 8, provider?: string, over: Partial<Pick<Position, 'speedKmh' | 'state' | 'steps' | 'bumps'>> = {}) => ({
  latitude: 30 + north / 111_320,
  longitude: 120 + east / (111_320 * Math.cos((30 * Math.PI) / 180)),
  accuracy,
  provider,
  speedKmh: 5,
  ...over,
})

describe('卫星点还是网络点', () => {
  it('App 标了来源就按它', () => {
    expect(fixSource({ provider: 'gps', accuracy: 80 })).toBe('satellite')
    expect(fixSource({ provider: 'wifi', accuracy: 30 })).toBe('network')
    expect(fixSource({ provider: 'network', accuracy: 30 })).toBe('network')
  })
  it('旧版 App 没标：精度 >50 米当网络点', () => {
    expect(fixSource({ accuracy: 50 })).toBe('satellite')
    expect(fixSource({ accuracy: 51 })).toBe('network')
    expect(fixSource({ accuracy: 0 })).toBe('satellite')
  })
  it('大概位置：网络点，或精度差于 50 米的卫星点', () => {
    expect(isRough({ provider: 'gps', accuracy: 6 })).toBe(false)
    expect(isRough({ provider: 'gps', accuracy: 80 })).toBe(true)
    expect(isRough({ provider: 'wifi', accuracy: 20 })).toBe(true)
  })
})

describe('低精度点落在精度圈里不挪圆点', () => {
  it('卫星点都挪，哪怕只差几米', () => {
    expect(movesDot(at(0, 0, 50, 'wifi'), at(5, 0, 6, 'gps'))).toBe(true)
  })
  it('Wi-Fi 点在现在的精度圈里：不挪', () => {
    expect(movesDot(at(0, 0, 55, 'wifi'), at(13, 5, 51, 'network'))).toBe(false)
    // 现在是卫星点（圈小），来的 Wi-Fi 点自己的圈罩得住现在的位置：也不挪
    expect(movesDot(at(0, 0, 4, 'gps'), at(30, 0, 55, 'wifi'))).toBe(false)
  })
  it('Wi-Fi 点跳到圈外（几百米）：挪', () => {
    expect(movesDot(at(0, 0, 59, 'wifi'), at(400, 0, 100, 'wifi'))).toBe(true)
  })
  it('明显更准的网络点（精度不到一半）：挪过去', () => {
    expect(movesDot(at(0, 0, 300, 'network'), at(80, 0, 60, 'wifi'))).toBe(true)
    expect(movesDot(at(0, 0, 100, 'network'), at(40, 0, 60, 'wifi'))).toBe(false)
  })
  it('第一个点总挪', () => {
    expect(movesDot(undefined, at(0, 0, 100, 'wifi'))).toBe(true)
  })
  // P20 11-17 07:05–07:10：人在家，每 10 秒一个 Wi-Fi/网络点，精度 45–60 米，来回差 7–18 米
  it('一串在家漂的 Wi-Fi 点只有第一个挪圆点，其余换成圆点所在的点；跳出去的才算', () => {
    const fixes = [at(0, 0, 45, 'network'), at(7, 3, 51, 'network'), at(-6, 10, 55, 'wifi'), at(5, -9, 53, 'wifi'), at(-12, 4, 60, 'wifi'), at(400, 0, 100, 'wifi')]
    const out = steadyFixes(fixes)
    expect(out.filter((f) => !f.held)).toEqual([fixes[0], fixes[5]])
    const held = { ...fixes[0], speedKmh: 0, course: undefined, held: true }
    expect(out.slice(1, 5)).toEqual([held, held, held, held])
  })
})

describe('人没在赶路时网络点整体不挪圆点', () => {
  it('范围内活动、静止，或报移动中但运动摘要一步没走、没颠簸，算没在赶路', () => {
    expect(atRest({ state: 'zone' })).toBe(true)
    expect(atRest({ state: 'still' })).toBe(true)
    expect(atRest({ state: 'moving', steps: 0, bumps: 0 })).toBe(true)
    expect(atRest({ state: 'moving', steps: 0, bumps: 9 })).toBe(false)
    expect(atRest({ state: 'moving' })).toBe(false)
    expect(atRest({})).toBe(false)
  })
  // 模拟 home-wifi-drift：屋里活动 8 分钟，Wi-Fi 定位一分钟一个、每个比上一个远 90 米，漂到 700 米外
  it('Wi-Fi 一点点漂走：一个都不挪', () => {
    const fixes = [at(0, 0, 40, 'wifi', { state: 'zone' }), ...[1, 2, 3, 4, 5, 6, 7, 8].map((i) => at(i * 88, 0, 45, 'wifi', { state: 'zone', steps: 70 }))]
    expect(steadyFixes(fixes).filter((f) => !f.held)).toEqual([fixes[0]])
  })
  it('误判成移动中、一步没走：每 10 秒一个漂的点也不挪', () => {
    const anchor = at(0, 0, 40, 'wifi', { state: 'still' })
    const drift = [1, 2, 3, 4, 5, 6].map((i) => at(i * 25, 0, 50, 'wifi', { state: 'moving', steps: 0, bumps: 0 }))
    expect(steadyFixes([anchor, ...drift]).filter((f) => !f.held)).toEqual([anchor])
  })
  it('没在赶路时只有圈里、明显更准的网络点能把圆点挪准一点；卫星点照常挪', () => {
    const anchor = at(0, 0, 300, 'network', { state: 'zone' })
    expect(movesDot(anchor, at(80, 0, 60, 'wifi', { state: 'zone' }))).toBe(true)
    expect(movesDot(anchor, at(500, 0, 60, 'wifi', { state: 'zone' }))).toBe(false)
    expect(movesDot(anchor, at(500, 0, 8, 'gps', { state: 'zone', speedKmh: 0 }))).toBe(true)
  })
  it('在赶路的网络点照旧：跳到圈外就挪', () => {
    expect(movesDot(at(0, 0, 40, 'wifi'), at(400, 0, 50, 'wifi', { state: 'moving', steps: 30 }))).toBe(true)
  })
})

describe('停下后卫星点在精度圈里抖：不挪', () => {
  it('测速≈0、离圆点不超过两个精度加起来：不挪', () => {
    expect(movesDot(at(0, 0, 8, 'gps'), at(5, 3, 6, 'gps', { speedKmh: 0 }))).toBe(false)
    expect(movesDot(at(0, 0, 8, 'gps'), at(13, 0, 6, 'gps', { speedKmh: 0 }))).toBe(false)
    expect(movesDot(at(0, 0, 8, 'gps'), at(5, 3, 6, 'gps', { speedKmh: STOP_KMH - 0.1 }))).toBe(false)
  })
  it('还在走（测速 ≥2 km/h）、离得比两个精度加起来还远、或明显更准：挪', () => {
    expect(movesDot(at(0, 0, 8, 'gps'), at(5, 3, 6, 'gps', { speedKmh: STOP_KMH }))).toBe(true)
    expect(movesDot(at(0, 0, 8, 'gps'), at(15, 0, 6, 'gps', { speedKmh: 0 }))).toBe(true)
    expect(movesDot(at(0, 0, 30, 'gps'), at(5, 0, 6, 'gps', { speedKmh: 0 }))).toBe(true)
  })
})

describe('状态卡上的精度', () => {
  it('卫星点只写误差', () => {
    expect(accuracyText({ provider: 'gps', accuracy: 6.4 })).toBe('误差约 6 米')
    expect(accuracyText({ accuracy: 8 })).toBe('误差约 8 米')
  })
  it('网络点写大概位置和来源', () => {
    expect(accuracyText({ provider: 'wifi', accuracy: 100 })).toBe('大概位置（Wi-Fi/基站定位）· 误差约 100 米')
    expect(accuracyText({ accuracy: 120 })).toBe('大概位置（Wi-Fi/基站定位）· 误差约 120 米')
    expect(accuracyText({ provider: 'gps', accuracy: 80 })).toBe('大概位置 · 误差约 80 米')
  })
  it('没报精度不写', () => {
    expect(accuracyText({ accuracy: 0 })).toBeNull()
  })
})
