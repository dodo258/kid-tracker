import { describe, expect, it } from 'vitest'
import type { Position } from '../api/types'
import { detailRows, fixSummary, withoutFix } from './details'

// 北京时间 2026-11-16 16:42
const NOW = new Date('2026-11-16T08:42:00Z')
const at = (hms: string) => new Date(`2026-11-16T${hms}+08:00`)
const pos = (patch: Partial<Position> = {}): Position => ({
  id: 1,
  deviceId: 1,
  fixTime: at('16:40:03'),
  deviceTime: at('16:40:03'),
  serverTime: at('16:40:05'),
  latitude: 30.27,
  longitude: 120.14,
  speedKmh: 0,
  accuracy: 8,
  geofenceIds: [],
  ...patch,
})
const rows = (patch: Partial<Position>) => Object.fromEntries(detailRows(pos(patch), NOW).map((r) => [r.label, r.value]))

describe('详细信息：字段齐全的移动中', () => {
  it('按方案表格的顺序和文案', () => {
    const p = pos({
      provider: 'gps',
      accuracy: 8.4,
      state: 'moving',
      speedKmh: 32.2,
      course: 92,
      net: 'cellular',
      batteryLevel: 80,
      charging: true,
      steps: 120,
      bumps: 0,
    })
    expect(detailRows(p, NOW)).toEqual([
      { label: '定位方式', value: '卫星定位（GPS）' },
      { label: '精度', value: '误差约 8 米' },
      { label: '网络', value: '移动网络' },
      { label: '电源', value: '充电中' },
      { label: '运动', value: '离上次上报走了 120 步' },
      { label: '手机判断', value: '移动中' },
      { label: '时间', value: '定位于 16:40:03 · 服务器收到 16:40:05' },
    ])
  })
})

describe('详细信息：有就显示、没有就不显示', () => {
  it('旧版 App 的点只有时间（电量在卡片上，这里不重复）', () => {
    expect(detailRows(pos({ accuracy: 0, batteryLevel: 58 }), NOW).map((r) => r.label)).toEqual(['时间'])
  })
  it('没有服务器时间只写定位时间', () => {
    expect(rows({ serverTime: undefined }).时间).toBe('定位于 16:40:03')
  })
  it('速度方向在卡片的状态句里，这里不写', () => {
    expect(rows({ state: 'moving', speedKmh: 12, course: 90 }).速度).toBeUndefined()
  })
})

describe('详细信息：各字段的取值', () => {
  it('定位方式', () => {
    expect(rows({ provider: 'wifi' }).定位方式).toBe('Wi-Fi 定位')
    expect(rows({ provider: 'network' }).定位方式).toBe('基站定位')
    expect(rows({ provider: 'fused' }).定位方式).toBe('fused')
  })
  it('没坐标的上报：写这次没有定位，不写精度', () => {
    const r = rows({ outdated: true, provider: 'gps', state: 'moving', speedKmh: 20, fixTime: at('16:20:00'), deviceTime: at('16:40:03') })
    expect(r.定位方式).toBe('这次没有定位（只报了在线）')
    expect(r.精度).toBeUndefined()
    // 当场发的，不算补传
    expect(r.时间).toBe('定位于 16:20:00 · 服务器收到 16:40:05')
  })
  it('网络：新字段 net', () => {
    expect(rows({ net: 'wifi' }).网络).toBe('Wi-Fi')
    expect(rows({ net: 'hotspot' }).网络).toBe('热点 / 随身 Wi-Fi')
    expect(rows({ net: 'cellular' }).网络).toBe('移动网络')
    expect(rows({ net: '5g' }).网络).toBe('5g')
  })
  it('网络：没有 net 时认旧字段 wifiType，两个都有按 net', () => {
    expect(rows({ wifiType: 'fixed' }).网络).toBe('Wi-Fi')
    expect(rows({ wifiType: 'none' }).网络).toBe('移动网络')
    expect(rows({ wifiType: 'hotspot', net: 'cellular' }).网络).toBe('移动网络')
  })
  it('电源：充电、省电模式（电量百分比在卡片上）', () => {
    expect(rows({ batteryLevel: 23.6, powerSave: true }).电源).toBe('省电模式开着')
    expect(rows({ charging: true, powerSave: true }).电源).toBe('充电中 · 省电模式开着')
    expect(rows({ batteryLevel: 80 }).电源).toBeUndefined()
  })
  it('运动：颠簸、一步没走', () => {
    expect(rows({ steps: 0, bumps: 14 }).运动).toBe('车上颠簸')
    expect(rows({ steps: 30, bumps: 2 }).运动).toBe('离上次上报走了 30 步 · 车上颠簸')
    expect(rows({ steps: 0, bumps: 0 }).运动).toBe('离上次上报没走动')
  })
  it('手机判断', () => {
    expect(rows({ state: 'zone' }).手机判断).toBe('在附近活动')
    expect(rows({ state: 'still' }).手机判断).toBe('静止')
  })
  it('补传：服务器收到比手机发出晚 1 分钟以上', () => {
    const r = rows({ fixTime: at('16:20:03'), deviceTime: at('16:20:03'), serverTime: at('16:32:10') })
    expect(r.时间).toBe('定位于 16:20:03 · 服务器收到 16:32:10 · 补传，晚到 12 分钟')
    expect(rows({ fixTime: at('14:20:00'), deviceTime: at('14:20:00'), serverTime: at('16:32:00') }).时间).toContain('晚到 2 小时')
    expect(rows({ serverTime: at('16:41:00') }).时间).not.toContain('补传')
  })
  it('昨天的点带日期', () => {
    const y = new Date('2026-11-15T23:10:00Z') // 北京时间 11-16 07:10
    expect(rows({ fixTime: new Date('2026-11-15T14:10:00Z'), deviceTime: y, serverTime: y }).时间).toMatch(/^定位于 昨天 22:10:00/)
  })
})

describe('收起时那行小字的定位方式', () => {
  it('新版 App 标了来源', () => {
    expect(fixSummary({ provider: 'gps', accuracy: 8 })).toBe('卫星定位 · 误差约 8 米')
    expect(fixSummary({ provider: 'wifi', accuracy: 62 })).toBe('Wi-Fi 定位 · 误差约 62 米')
    expect(fixSummary({ provider: 'anchor', accuracy: 20 })).toBe('按连着的 Wi-Fi 位置 · 误差约 20 米')
    expect(fixSummary({ provider: 'network', accuracy: 0 })).toBe('基站定位')
  })
  it('旧版 App 没标来源：按精度', () => {
    expect(fixSummary({ accuracy: 8 })).toBe('误差约 8 米')
    expect(fixSummary({ accuracy: 0 })).toBeNull()
  })
})

describe('这次有没有带坐标', () => {
  it('outdated 或位置比上报早 1 分钟以上', () => {
    expect(withoutFix(pos())).toBe(false)
    expect(withoutFix(pos({ outdated: true }))).toBe(true)
    expect(withoutFix(pos({ fixTime: at('16:30:00') }))).toBe(true)
  })
})
