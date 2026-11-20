import { describe, expect, it } from 'vitest'
import type { ReportState } from '../api/types'
import {
  compareReports,
  currentRun,
  contactTime,
  headingLabel,
  holdsDot,
  lostLabel,
  movingSince,
  offlinePresence,
  onTheMove,
  parentOfflineSince,
  presenceOf,
  shortAddress,
  timelineTime,
  type Presence,
} from './presence'

const kidStatus = (p: Parameters<typeof presenceOf>[0], now: Date, since?: Date) => presenceOf(p, now, since)

const MIN = 60_000
const now = new Date(2026, 10, 17, 16, 42)
const at = (minutesAgo: number) => new Date(now.getTime() - minutesAgo * MIN)
const point = (state: ReportState, fixMinutesAgo: number, contactMinutesAgo = fixMinutesAgo, speedKmh = 0) => ({
  state,
  fixTime: at(fixMinutesAgo),
  deviceTime: at(contactMinutesAgo),
  speedKmh,
})

describe('三种状态的颜色档', () => {
  it('移动中、位置 2 分钟内：实时', () => {
    expect(kidStatus(point('moving', 0.2, 0.2, 12), now)).toEqual({ freshness: 'fresh', live: 'live' })
    expect(kidStatus(point('moving', 2, 2, 12), now)).toEqual({ freshness: 'fresh', live: 'live' })
  })
  it('移动中、位置停在 2 分钟以前（隧道）：黄色，不假装实时', () => {
    expect(kidStatus(point('moving', 5, 5, 40), now)).toEqual({ freshness: 'stale', live: 'movingOld' })
  })
  it('移动中只看 state，速度 0（等红灯）也是实时', () => {
    expect(kidStatus(point('moving', 0.5, 0.5, 0), now)).toEqual({ freshness: 'fresh', live: 'live' })
  })
  it('范围内、静止：正常，不论位置多久以前（静止时位置本来就不变）', () => {
    expect(kidStatus(point('zone', 1), now)).toEqual({ freshness: 'fresh', live: null })
    expect(kidStatus(point('still', 180, 3), now)).toEqual({ freshness: 'fresh', live: null })
  })
  it('超过 15 分钟没有任何上报：失联（红色）', () => {
    expect(kidStatus(point('still', 180, 16), now)).toEqual({ freshness: 'lost', live: null })
    expect(kidStatus(point('zone', 15.5), now)).toEqual({ freshness: 'lost', live: null })
    expect(kidStatus(point('moving', 16, 16, 30), now)).toEqual({ freshness: 'lost', live: null })
  })
  // P20 11-17 16:48：华为把一次 5 分钟呼吸推迟到约 10 分钟，不能就判失联
  it('一次呼吸迟到、隔了 11 分钟：不算失联', () => {
    expect(kidStatus(point('still', 180, 11), now)).toEqual({ freshness: 'fresh', live: null })
  })
  it('孩子手机时间慢 15 分钟，但服务器刚收到：不报失联', () => {
    expect(kidStatus({ ...point('still', 15.5, 15.5), serverTime: at(0.5) }, now)).toEqual({ freshness: 'fresh', live: null })
    expect(kidStatus({ ...point('zone', 15.5, 15.5), serverTime: at(0.5) }, now)).toEqual({ freshness: 'fresh', live: null })
  })
  it('服务器已经 16 分钟没收到：失联，不看手机时间', () => {
    const p = { ...point('still', 1, 1), serverTime: at(16) }
    expect(kidStatus(p, now)).toEqual({ freshness: 'lost', live: null })
    expect(lostLabel(contactTime(p), now)).toBe('已失联 16 分钟')
  })
  it('孩子手机时间往后错了：黄色，不判实时', () => {
    expect(kidStatus(point('moving', -20, -20, 12), now)).toEqual({ freshness: 'stale', live: null })
  })
  it('没有 state 的旧点照旧按速度', () => {
    expect(kidStatus({ fixTime: at(6), deviceTime: at(6), speedKmh: 0 }, now)).toEqual({ freshness: 'fresh', live: null })
    expect(kidStatus({ fixTime: at(0.5), deviceTime: at(0.5), speedKmh: 12 }, now)).toEqual({ freshness: 'fresh', live: 'live' })
  })
})

describe('三种状态的文案：第一行只写状态', () => {
  it('失联写多久', () => {
    expect(lostLabel(at(23), now)).toBe('已失联 23 分钟')
    expect(lostLabel(at(185), now)).toBe('已失联 3 小时')
    expect(lostLabel(at(26 * 60), now)).toBe('已失联，最后联系 昨天 14:42')
  })
  it('八个方向', () => {
    expect([0, 44, 90, 135, 180, 225, 270, 315, 359, -90].map(headingLabel)).toEqual([
      '向北', '向东北', '向东', '向东南', '向南', '向西南', '向西', '向西北', '向北', '向西',
    ])
  })
  it('地址去掉省市区街道', () => {
    expect(shortAddress('浙江省杭州市西湖区西溪街道教工路 18 号')).toBe('教工路 18 号')
    expect(shortAddress('北京市朝阳区建国路 88 号')).toBe('建国路 88 号')
    expect(shortAddress('浙江省杭州市西湖区文三路 90 号东部软件园')).toBe('文三路 90 号东部软件园')
    expect(shortAddress('教工路 18 号')).toBe('教工路 18 号')
    expect(shortAddress('  ')).toBeUndefined()
  })
  it('县级市两级「市」，区县一级不吞到小区、社区的「区」', () => {
    expect(shortAddress('江苏省苏州市昆山市玉山镇震川路88号某某小区3幢')).toBe('震川路88号某某小区3幢')
    expect(shortAddress('江苏省苏州市昆山市玉山镇某某小区3幢')).toBe('某某小区3幢')
    expect(shortAddress('浙江省杭州市西湖区西溪街道某某社区服务中心')).toBe('某某社区服务中心')
  })
  it('东莞、中山不设区：市下面直接是镇街', () => {
    expect(shortAddress('广东省东莞市南城街道鸿福路200号')).toBe('鸿福路200号')
    // 松山湖园区在东莞相当于镇街一级，去掉后剩小区名
    expect(shortAddress('广东省东莞市松山湖园区某某小区5栋')).toBe('某某小区5栋')
    expect(shortAddress('广东省中山市石岐街道孙文中路1号')).toBe('孙文中路1号')
    expect(shortAddress('广东省中山市小榄镇某某花园小区')).toBe('某某花园小区')
  })
  it('自治区、直辖市', () => {
    expect(shortAddress('内蒙古自治区呼和浩特市新城区海拉尔东街1号')).toBe('海拉尔东街1号')
    expect(shortAddress('上海市浦东新区陆家嘴街道世纪大道100号')).toBe('世纪大道100号')
  })
})

describe('在路上：正在移动 · 定位中', () => {
  it('这次上报没带新坐标（位置比上报早 1 分钟以上）：不写实时，写定位中', () => {
    // 位置 1.5 分钟前，上报刚刚：以前会写「实时 · 12 km/h」，速度是旧的
    expect(kidStatus(point('moving', 1.5, 0.1, 12), now)).toEqual({ freshness: 'fresh', live: 'locating' })
  })
  it('静止一小时后刚出门：位置还是家里那个旧点，3 分钟内写定位中（绿色）', () => {
    const p = point('moving', 60, 0.2)
    expect(kidStatus(p, now, at(1))).toEqual({ freshness: 'fresh', live: 'locating' })
    expect(kidStatus(p, now, at(3))).toEqual({ freshness: 'fresh', live: 'locating' })
  })
  it('走了 3 分钟以上还没新位置（隧道）：黄色位置停在 X 分钟前', () => {
    expect(kidStatus(point('moving', 60, 0.2), now, at(4))).toEqual({ freshness: 'stale', live: 'movingOld' })
    // 不知道从什么时候开始走的：照旧
    expect(kidStatus(point('moving', 60, 0.2), now)).toEqual({ freshness: 'stale', live: 'movingOld' })
  })
  it('失联优先：15 分钟没有任何上报，不管走没走', () => {
    expect(kidStatus(point('moving', 20, 16), now, at(17))).toEqual({ freshness: 'lost', live: null })
  })
  it('在路上的才匀速播放、外推（实时和定位中），位置旧了、静止、失联不算', () => {
    const p = (live: Presence['live'], freshness: Presence['freshness'] = 'fresh'): Presence => ({ freshness, live })
    expect(onTheMove(p('live'))).toBe(true)
    expect(onTheMove(p('locating'))).toBe(true)
    expect(onTheMove(p('movingOld', 'stale'))).toBe(false)
    expect(onTheMove(p(null))).toBe(false)
    expect(onTheMove(p(null, 'lost'))).toBe(false)
  })
  // 上报端 v2 主坐标只放卫星点：进楼、到站卫星没了只报时间（Traccar 标 outdated、抄上一个点的定位时间），还报着移动中
  it('最新一条只报了时间、0 步 0 颠簸：圆点停住不往前推；带了新坐标、在走、在车上颠、旧版 App 没运动摘要的照常', () => {
    const timeOnly = { ...point('moving', 0.2, 0), outdated: true, steps: 0, bumps: 0 }
    expect(holdsDot(timeOnly)).toBe(true)
    // 服务器没标 outdated、但定位时间比上报早 1 分钟以上，也是没带新坐标
    expect(holdsDot({ ...point('moving', 1.5, 0), steps: 0, bumps: 0 })).toBe(true)
    expect(holdsDot({ ...point('moving', 0, 0), steps: 0, bumps: 0 })).toBe(false)
    expect(holdsDot({ ...timeOnly, steps: 12 })).toBe(false)
    expect(holdsDot({ ...timeOnly, bumps: 3 })).toBe(false)
    expect(holdsDot({ ...point('moving', 0.2, 0), outdated: true })).toBe(false)
  })
})

describe('这一轮在路上从什么时候开始', () => {
  const r = (state: ReportState | undefined, minutesAgo: number) => ({ ...point(state ?? 'still', minutesAgo), state })
  it('从最新往前数连续的 moving，取最早那条', () => {
    expect(movingSince([r('still', 10), r('moving', 6), r('moving', 3), r('moving', 0.2)])?.getTime()).toBe(at(6).getTime())
  })
  it('最新一条不是 moving：没有', () => {
    expect(movingSince([r('moving', 6), r('zone', 1)])).toBeUndefined()
    expect(movingSince([])).toBeUndefined()
  })
  it('按服务器收到的时间', () => {
    const p = { ...r('moving', 6), serverTime: at(5) }
    expect(movingSince([r('still', 10), p])?.getTime()).toBe(at(5).getTime())
  })
})

// 旧版 App（没有 state）：原 live.test.ts、motion.test.ts 的用例
describe('旧版 App：实时分档', () => {
  const legacy = (minutesAgo: number, speedKmh: number, heartbeatMinutesAgo = minutesAgo) => ({
    fixTime: at(minutesAgo),
    deviceTime: at(heartbeatMinutesAgo),
    speedKmh,
  })
  it('2 分钟内（含）、速度 >1 km/h：实时，绿色', () => {
    expect(kidStatus(legacy(0, 12), now)).toEqual({ freshness: 'fresh', live: 'live' })
    expect(kidStatus(legacy(2, 1.5), now)).toEqual({ freshness: 'fresh', live: 'live' })
  })
  it('移动中、位置超过 2 分钟：移动中位置旧了，黄色（不假装实时）', () => {
    expect(kidStatus(legacy(2.1, 12), now)).toEqual({ freshness: 'stale', live: 'movingOld' })
    expect(kidStatus(legacy(6, 12), now)).toEqual({ freshness: 'stale', live: 'movingOld' })
    expect(kidStatus(legacy(25, 12), now)).toEqual({ freshness: 'stale', live: 'movingOld' })
  })
  it('静止（≤1 km/h）照旧按多久前，不判实时', () => {
    expect(kidStatus(legacy(0, 0), now)).toEqual({ freshness: 'fresh', live: null })
    expect(kidStatus(legacy(1, 1), now)).toEqual({ freshness: 'fresh', live: null })
    expect(kidStatus(legacy(6, 0.8), now)).toEqual({ freshness: 'fresh', live: null })
    expect(kidStatus(legacy(25, 0), now)).toEqual({ freshness: 'stale', live: null })
  })
  it('失联（红色）规则不变：移动中也不判实时', () => {
    expect(kidStatus(legacy(40, 12), now)).toEqual({ freshness: 'lost', live: null })
  })
  it('心跳还在、位置停在 25 分钟前且当时在动：黄色移动中位置旧了', () => {
    expect(kidStatus(legacy(25, 12, 1), now)).toEqual({ freshness: 'stale', live: 'movingOld' })
  })
  it('孩子手机时间往后错了 5 分钟以上：照旧写时间不对，不判实时', () => {
    expect(kidStatus(legacy(-20, 12), now)).toEqual({ freshness: 'stale', live: null })
  })
  it('时间往后错几分钟以内当成时钟误差：移动中算实时', () => {
    expect(kidStatus(legacy(-3, 12), now)).toEqual({ freshness: 'fresh', live: 'live' })
  })
})


describe('手机时间快的超前点（审计 #2）', () => {

  it('超前点之后关机：过了 15 分钟是失联，不是一直「手机时间不对」（状态句见 status.test）', () => {
    const p = { state: 'still' as const, fixTime: new Date(at(20).getTime() + 120 * MIN), deviceTime: at(20), serverTime: at(20), speedKmh: 0 }
    expect(presenceOf(p, now).freshness).toBe('lost')
  })

  it('时间轴：超前点按服务器收到的时间排，校时以后的正常点比它新', () => {
    const ahead = { id: 1, fixTime: new Date(at(10).getTime() + 120 * MIN), deviceTime: at(10), serverTime: at(10) }
    const normal = { id: 2, fixTime: at(5), deviceTime: at(5), serverTime: at(5) }
    expect(timelineTime(ahead)).toBe(at(10).getTime())
    expect(compareReports(normal, ahead)).toBeGreaterThan(0)
  })

  it('定位时间相同（心跳抄上一个点）：服务器后收到的新；离线补传的旧点不算新', () => {
    const fix = { id: 5, fixTime: at(10), deviceTime: at(10), serverTime: at(10) }
    const beat = { id: 6, fixTime: at(10), deviceTime: at(4), serverTime: at(4) }
    const late = { id: 7, fixTime: at(12), deviceTime: at(12), serverTime: at(1) }
    expect(compareReports(beat, fix)).toBeGreaterThan(0)
    expect(compareReports(late, beat)).toBeLessThan(0)
  })
})

describe('家长这边断网（审计 #6）', () => {
  const synced = at(20)
  it('推送断着、请求失败、上次同步超过 1 分钟：返回最后同步时刻', () => {
    expect(parentOfflineSince({ live: false, error: '暂时连不上服务器', syncedAt: synced }, now)).toBe(synced)
  })
  it('推送通着、没出错、刚同步过：不算断网', () => {
    expect(parentOfflineSince({ live: true, error: 'x', syncedAt: synced }, now)).toBeNull()
    expect(parentOfflineSince({ live: false, error: null, syncedAt: synced }, now)).toBeNull()
    expect(parentOfflineSince({ live: false, error: 'x', syncedAt: at(0.5) }, now)).toBeNull()
  })
  it('孩子同步那一刻还在线：黄色，不是红色失联；那一刻已经失联的照旧红色', () => {
    const online = { state: 'still' as const, fixTime: at(21), deviceTime: at(21), serverTime: at(21), speedKmh: 0 }
    expect(presenceOf(online, now).freshness).toBe('lost')
    expect(offlinePresence(online, synced)).toEqual({ freshness: 'stale', live: null })
    const lost = { ...online, fixTime: at(40), deviceTime: at(40), serverTime: at(40) }
    expect(offlinePresence(lost, synced).freshness).toBe('lost')
  })
})

describe('这一轮 currentRun（解读层重写：在路上起点、静止起点、刚停下都按它）', () => {
  const r = (state: ReportState | undefined, minutesAgo: number) => ({ ...point(state ?? 'zone', minutesAgo), state, serverTime: at(minutesAgo) })
  it('从最新往前连着同一状态，起点是这串最早那条的联系时间；前面有别的状态算看得到起点', () => {
    expect(currentRun([r('zone', 20), r('still', 12), r('still', 5)])).toEqual({ state: 'still', since: at(12), exact: true })
  })
  it('窗口里全是同一状态：起点看不到（exact 为假）', () => {
    expect(currentRun([r('moving', 9), r('moving', 3)])).toEqual({ state: 'moving', since: at(9), exact: false })
  })
  it('旧版 App（没有 state）也是一轮；没有上报是 undefined', () => {
    expect(currentRun([r(undefined, 4), r(undefined, 1)])?.state).toBeUndefined()
    expect(currentRun([])).toBeUndefined()
  })
  it('movingSince 就是这一轮在路上时的起点', () => {
    expect(movingSince([r('still', 10), r('moving', 6), r('moving', 2)])).toEqual(at(6))
    expect(movingSince([r('moving', 6), r('still', 2)])).toBeUndefined()
  })
})
