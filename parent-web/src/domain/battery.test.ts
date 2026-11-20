import { describe, expect, it } from 'vitest'
import { batteryPercent, batteryTone, LOW_BATTERY } from './battery'

describe('电量颜色：平时灰、充电绿、≤20% 橙', () => {
  it('平时灰', () => {
    expect(batteryTone(64, false)).toBe('calm')
    expect(batteryTone(100, undefined)).toBe('calm')
  })
  it('阈值是 20%：20 及以下橙，21 灰（原来 15% 才变）', () => {
    expect(LOW_BATTERY).toBe(20)
    expect(batteryTone(20, false)).toBe('jing')
    expect(batteryTone(21, false)).toBe('calm')
    expect(batteryTone(16, false)).toBe('jing')
    expect(batteryTone(0, false)).toBe('jing')
  })
  it('按显示出来的整数判断：20.4 显示 20% 是橙，20.6 显示 21% 是灰', () => {
    expect(batteryTone(20.4, false)).toBe('jing')
    expect(batteryTone(20.6, false)).toBe('calm')
  })
  it('充电中优先绿，低电也是绿', () => {
    expect(batteryTone(64, true)).toBe('an')
    expect(batteryTone(12, true)).toBe('an')
  })
  it('显示的百分比取整并夹在 0–100', () => {
    expect(batteryPercent(63.6)).toBe(64)
    expect(batteryPercent(-3)).toBe(0)
    expect(batteryPercent(104)).toBe(100)
  })
})
