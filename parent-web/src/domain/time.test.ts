import { describe, expect, it } from 'vitest'
import { freshness, positionFreshness } from './presence'
import { agoLabel, clockLabel, isClockAhead } from './time'

const MIN = 60_000

describe('freshness 分档', () => {
  it('10 分钟以内（含）是正常', () => {
    expect(freshness(0)).toBe('fresh')
    expect(freshness(10 * MIN)).toBe('fresh')
  })
  it('10 到 30 分钟（含）是有点久', () => {
    expect(freshness(10 * MIN + 1)).toBe('stale')
    expect(freshness(30 * MIN)).toBe('stale')
  })
  it('超过 30 分钟是可能失联', () => {
    expect(freshness(30 * MIN + 1)).toBe('lost')
    expect(freshness(5 * 3600_000)).toBe('lost')
  })
})

describe('agoLabel', () => {
  const now = new Date(2026, 10, 16, 16, 42)
  it('一分钟内写刚刚', () => {
    expect(agoLabel(new Date(now.getTime() - 20_000), now)).toBe('刚刚')
  })
  it('写分钟和小时', () => {
    expect(agoLabel(new Date(now.getTime() - 3 * MIN), now)).toBe('3 分钟前')
    expect(agoLabel(new Date(now.getTime() - 59 * MIN), now)).toBe('59 分钟前')
    expect(agoLabel(new Date(now.getTime() - 125 * MIN), now)).toBe('2 小时前')
  })
  it('超过一天写具体时间', () => {
    expect(agoLabel(new Date(2026, 10, 14, 22, 10), now)).toBe('11月14日 22:10')
  })
  it('设备时钟比手机快时不出现负数', () => {
    expect(agoLabel(new Date(now.getTime() + 5 * MIN), now)).toBe('刚刚')
  })
})

describe('clockLabel', () => {
  const now = new Date(2026, 10, 16, 8, 5)
  it('今天、昨天、更早', () => {
    expect(clockLabel(new Date(2026, 10, 16, 7, 3), now)).toBe('07:03')
    expect(clockLabel(new Date(2026, 10, 15, 22, 10), now)).toBe('昨天 22:10')
    expect(clockLabel(new Date(2026, 10, 13, 9, 0), now)).toBe('11月13日 09:00')
  })
})

// 心跳没取到新位置：位置停在 fixTime，deviceTime 是手机最后一次联系服务器
describe('没坐标的心跳', () => {
  const now = new Date(2026, 10, 16, 16, 42)
  const at = (minutesAgo: number) => new Date(now.getTime() - minutesAgo * MIN)

  it('普通的点：正常；位置 25 分钟前按位置算多久前', () => {
    expect(positionFreshness({ fixTime: at(3), deviceTime: at(3) }, now)).toBe('fresh')
    expect(agoLabel(at(25), now)).toBe('25 分钟前')
  })
  it('手机还在联系就不说可能失联，位置旧最多算有点久', () => {
    expect(positionFreshness({ fixTime: at(5), deviceTime: at(1) }, now)).toBe('fresh')
    expect(positionFreshness({ fixTime: at(25), deviceTime: at(1) }, now)).toBe('stale')
    expect(positionFreshness({ fixTime: at(120), deviceTime: at(1) }, now)).toBe('stale')
  })
  it('心跳也断了才算可能失联', () => {
    expect(positionFreshness({ fixTime: at(120), deviceTime: at(40) }, now)).toBe('lost')
    expect(positionFreshness({ fixTime: at(120), deviceTime: at(20) }, now)).toBe('stale')
  })
})

describe('孩子手机时间往后错了（定位时间在将来）', () => {
  const now = new Date(2026, 10, 16, 16, 42)
  const ahead = (ms: number) => new Date(now.getTime() + ms)
  it('晚 5 分钟以内当成时钟误差，还是刚刚、正常', () => {
    expect(agoLabel(ahead(4 * MIN), now)).toBe('刚刚')
    expect(freshness(-4 * MIN)).toBe('fresh')
    expect(isClockAhead(ahead(5 * MIN), now)).toBe(false)
  })
  it('晚 5 分钟以上不写刚刚，写孩子手机时间不对、黄色', () => {
    expect(agoLabel(ahead(5 * MIN + 1000), now)).toBe('孩子手机时间不对')
    expect(agoLabel(ahead(3 * 3600_000), now)).toBe('孩子手机时间不对')
    expect(freshness(-6 * MIN)).toBe('stale')
    expect(freshness(-2 * 86_400_000)).toBe('stale')
  })
  it('一个点定位时间在将来：黄色', () => {
    expect(positionFreshness({ fixTime: ahead(20 * MIN), deviceTime: ahead(20 * MIN) }, now)).toBe('stale')
  })
})
