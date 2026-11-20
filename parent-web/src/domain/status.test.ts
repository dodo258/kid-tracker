import { describe, expect, it } from 'vitest'
import type { Position } from '../api/types'
import { acceptedFix, smoothTrack } from '../motion/smooth'
import { presenceOf, type Presence } from './presence'
import { addPositions, EMPTY_HISTORY, steadyOf } from './track'
import { situationOf, statusLine, whereLine, wording } from './status'

const MIN = 60_000
const NOW = new Date('2026-11-16T08:42:00Z')
const ago = (m: number) => new Date(NOW.getTime() - m * MIN)
const M_PER_DEG = 111_320

function pos(patch: Partial<Position> = {}): Position {
  return {
    id: 1,
    deviceId: 1,
    fixTime: ago(0.2),
    deviceTime: ago(0.2),
    serverTime: ago(0.2),
    latitude: 30.27,
    longitude: 120.14,
    speedKmh: 0,
    accuracy: 6,
    provider: 'gps',
    geofenceIds: [],
    ...patch,
  }
}

const FRESH: Presence = { freshness: 'fresh', live: null }
const LIVE: Presence = { freshness: 'fresh', live: 'live' }
const EMPTY = { reports: [], fixes: [] }

const whereText = (p: Position, placeName: string | undefined, shown: Position, lost: boolean) => whereLine(p, shown, placeName, lost, NOW).where

const line = (p: Position, presence: Presence, extra: Partial<Parameters<typeof statusLine>[0]> = {}) =>
  statusLine({ position: p, presence, history: EMPTY, placeName: undefined, offlineSince: null, updating: false, now: NOW, ...extra })

describe('状态句', () => {
  it('在路上：速度和方向，安色、圆点呼吸', () => {
    expect(line(pos({ state: 'moving', speedKmh: 22.4, course: 45 }), LIVE)).toEqual({ text: '在路上 · 22 km/h 向东北', tone: 'an', pulse: true })
    expect(line(pos({ state: 'moving', speedKmh: 0.5 }), LIVE).text).toBe('在路上')
    expect(line(pos({ state: 'moving' }), { freshness: 'fresh', live: 'locating' }).text).toBe('在路上 · 正在定位')
  })
  it('在路上但位置停在几分钟前（隧道）：灰色', () => {
    expect(line(pos({ state: 'moving', fixTime: ago(6), deviceTime: ago(0.5) }), { freshness: 'stale', live: 'movingOld' })).toEqual({
      text: '在路上 · 位置停在 6 分钟前',
      tone: 'calm',
      pulse: false,
    })
  })
  it('手机还报移动中，最新几个卫星点停着 2 分多钟：刚停下 · 2 分钟，不等手机改状态', () => {
    const fixes = [0, 40, 80, 120, 150].map((s, i) =>
      pos({ id: i, state: 'moving', speedKmh: s === 0 ? 1 : 0.2, fixTime: new Date(ago(2.6).getTime() + s * 1000), latitude: 30.27 + (i % 2) * 2 / M_PER_DEG }),
    )
    const latest = fixes[4]
    expect(line(latest, LIVE, { history: { reports: fixes, fixes } })).toEqual({ text: '刚停下 · 2 分钟', tone: 'an', pulse: false })
  })
  it('刚停下不到 1 分钟：只写「刚停下」', () => {
    const fixes = [0, 10, 20, 30, 40].map((s, i) => pos({ id: i, state: 'moving', speedKmh: 0, fixTime: new Date(ago(0.75).getTime() + s * 1000) }))
    expect(line(fixes[4], LIVE, { history: { reports: fixes, fixes } }).text).toBe('刚停下')
  })
  it('静止：在常去地点里写地点名和静止多久', () => {
    const p = pos({ state: 'still', fixTime: ago(12), deviceTime: ago(1), serverTime: ago(1) })
    const history = { reports: [p], fixes: [], still: { since: ago(12), exact: true, until: ago(1) } }
    expect(line(p, FRESH, { placeName: '家', history })).toEqual({ text: '在家 · 静止 12 分钟', tone: 'an', pulse: false })
    expect(line(p, FRESH, { history }).text).toBe('静止 12 分钟')
    // 打开页面时这一轮已经静止了很久、起点不在窗口里：写「至少」
    expect(line(p, FRESH, { history: { ...history, still: { since: ago(25), exact: false, until: ago(1) } } }).text).toBe('静止至少 25 分钟')
    // 刚打开、只看到最新一条：不写「至少 1 分钟」这种没用的数
    expect(line(p, FRESH, { history: { ...history, still: { since: ago(1), exact: false, until: ago(1) } } }).text).toBe('静止')
  })
  it('范围内活动：有常去地点写「在学校附近活动」，没有写「在附近活动」（地址在下一行）', () => {
    expect(line(pos({ state: 'zone' }), FRESH, { placeName: '学校' }).text).toBe('在学校附近活动')
    expect(line(pos({ state: 'zone' }), FRESH).text).toBe('在附近活动')
  })
  it('失联：警色「已失联 18 分钟」', () => {
    expect(line(pos({ state: 'still', deviceTime: ago(18), serverTime: ago(18), fixTime: ago(40) }), { freshness: 'lost', live: null })).toEqual({
      text: '已失联 18 分钟',
      tone: 'jing',
      pulse: false,
    })
  })
  it('手机时间不对（定位时间比现在晚 20 分钟）', () => {
    expect(line(pos({ state: 'still', fixTime: new Date(NOW.getTime() + 20 * MIN) }), { freshness: 'stale', live: null }).text).toBe('手机时间不对')
  })
  it('重启后还没解锁：灰色「手机刚重启」，先于在路上、静止、手机时间不对', () => {
    expect(line(pos({ state: 'still', locked: true }), FRESH)).toEqual({ text: '手机刚重启', tone: 'calm', pulse: false })
    expect(line(pos({ state: 'moving', speedKmh: 30, locked: true }), LIVE).text).toBe('手机刚重启')
    expect(line(pos({ state: 'still', locked: true, fixTime: new Date(NOW.getTime() + 20 * MIN) }), FRESH).text).toBe('手机刚重启')
    // 解锁后的点不带 locked，照常写
    expect(line(pos({ state: 'zone' }), FRESH).text).toBe('在附近活动')
  })
  it('重启后没解锁、之后又失联：写已失联', () => {
    const p = pos({ state: 'still', locked: true, deviceTime: ago(18), serverTime: ago(18), fixTime: ago(18) })
    expect(line(p, { freshness: 'lost', live: null }).text).toBe('已失联 18 分钟')
  })
  it('失联先于手机时间不对', () => {
    const p = pos({ state: 'still', fixTime: new Date(NOW.getTime() + 20 * MIN), deviceTime: ago(30), serverTime: ago(30) })
    expect(line(p, { freshness: 'lost', live: null }).tone).toBe('jing')
  })
  it('家长这边断网：说家长这边连不上，不说孩子失联', () => {
    expect(line(pos({ state: 'still' }), { freshness: 'lost', live: null }, { offlineSince: ago(3) })).toEqual({
      text: '家长这边连不上服务器',
      tone: 'calm',
      pulse: false,
    })
  })
  it('刚回到前台还在更新：先说「正在更新…」，不按旧数据闪「已失联」', () => {
    expect(line(pos({ state: 'still', deviceTime: ago(40), serverTime: ago(40) }), { freshness: 'lost', live: null }, { updating: true }).text).toBe('正在更新…')
  })
  it('旧版 App（没有 state）：没在动 / 位置旧了', () => {
    expect(line(pos(), FRESH, { placeName: '学校' }).text).toBe('在学校 · 没在动')
    expect(line(pos({ fixTime: ago(15), deviceTime: ago(15), serverTime: ago(15) }), { freshness: 'stale', live: null }).text).toBe('15 分钟前的位置')
    expect(line(pos({ speedKmh: 12, course: 90 }), LIVE).text).toBe('在路上 · 12 km/h 向东')
  })
})

describe('第三行：在哪、多久前', () => {
  it('有地址写短地址（去掉省市区）', () => {
    const p = pos({ address: '浙江省杭州市西湖区西溪街道教工路 18 号' })
    expect(whereText(p, undefined, p, false)).toBe('教工路 18 号')
    expect(whereText(p, '学校', p, false)).toBe('教工路 18 号')
  })
  it('没有地址：在常去地点里写地点名，否则写定位方式和误差', () => {
    const p = pos({ provider: 'wifi', accuracy: 60 })
    expect(whereText(p, '学校', p, false)).toBe('学校')
    expect(whereText(p, undefined, p, false)).toBe('Wi-Fi 定位 · 误差约 60 米')
  })
  it('失联时说「最后在 …」', () => {
    const p = pos({ address: '浙江省杭州市西湖区西溪街道教工路 18 号' })
    expect(whereText(p, undefined, p, true)).toBe('最后在 教工路 18 号')
  })
  it('多久前按最后一次联系（呼吸点也算），不按位置时间', () => {
    const p = pos({ fixTime: ago(80), deviceTime: ago(3), serverTime: ago(3) })
    expect(whereLine(p, p, undefined, false, NOW).ago).toBe('3 分钟前在线')
    expect(whereLine(pos(), pos(), undefined, false, NOW).ago).toBe('刚刚')
  })
})

describe('卡片地址、围栏、时间用地图实际采信的那个点（审计 11-18 #2）', () => {
  const historyOf = (rows: Position[]) => rows.reduce((h, p) => addPositions(h, [p]), EMPTY_HISTORY)
  const shownOf = (rows: Position[]) => acceptedFix(smoothTrack(steadyOf(historyOf(rows))))

  it('静止时来了 1 公里外的 Wi-Fi 漂移点：圆点不动，卡片还是甲区，最后联系按漂移点', () => {
    const a = pos({ id: 1, state: 'still', accuracy: 8, address: '甲区', fixTime: ago(5), deviceTime: ago(5), serverTime: ago(5) })
    const b = pos({ id: 2, state: 'still', accuracy: 90, provider: 'wifi', address: '乙区', latitude: 30.27 + 1113 / M_PER_DEG })
    const shown = shownOf([a, b])
    expect(shown?.id).toBe(1)
    expect(whereLine(b, shown ?? b, undefined, false, NOW)).toEqual({ where: '甲区', ago: '刚刚' })
  })
  it('最新一条只是心跳（定位沿用 80 分钟前的）：写「上次位置」和那次定位多久前，右边「刚刚在线」', () => {
    const a = pos({ id: 1, state: 'zone', address: '甲区', fixTime: ago(80), deviceTime: ago(80), serverTime: ago(80) })
    const beat = pos({ id: 2, state: 'zone', address: '甲区', fixTime: ago(80), deviceTime: ago(0.2), serverTime: ago(0.2) })
    const shown = shownOf([a, beat]) ?? beat
    expect(whereLine(beat, shown, '学校', false, NOW)).toEqual({ where: '上次位置 甲区 · 1 小时前', ago: '刚刚在线' })
  })
  it('新坐标照常：地址就是这次的，不加「上次位置」', () => {
    const p = pos({ address: '甲区' })
    expect(whereLine(p, p, undefined, false, NOW)).toEqual({ where: '甲区', ago: '刚刚' })
  })
})

describe('补传的旧点：失联判断和时长用同一个联系时间（审计 11-18 #4）', () => {
  it('旧版 App 一小时前的点刚补传上来：不说「已失联」，说刚收到补传', () => {
    const late = pos({ state: undefined, fixTime: ago(60), deviceTime: ago(60), serverTime: NOW })
    const presence = presenceOf(late, NOW)
    expect(presence.freshness).not.toBe('lost')
    expect(line(late, presence).text).toBe('刚收到补传，位置在 1 小时前')
    expect(whereLine(late, late, undefined, false, NOW).ago).toBe('刚刚')
  })
  it('新版 App 补传的静止点也一样', () => {
    const late = pos({ state: 'still', fixTime: ago(40), deviceTime: ago(40), serverTime: ago(1) })
    expect(line(late, presenceOf(late, NOW)).text).toBe('刚收到补传，位置在 40 分钟前')
  })
  it('真的失联（服务器 40 分钟没收到）：失联时长也按服务器收到的时间', () => {
    const p = pos({ state: undefined, fixTime: ago(40), deviceTime: ago(40), serverTime: ago(40) })
    const presence = presenceOf(p, NOW)
    expect(presence.freshness).toBe('lost')
    expect(line(p, presence).text).toBe('已失联 40 分钟')
  })
  it('心跳（手机刚发的、定位沿用旧的）不算补传', () => {
    const beat = pos({ state: 'still', fixTime: ago(60), deviceTime: ago(0.2), serverTime: ago(0.2) })
    expect(line(beat, presenceOf(beat, NOW)).text).toBe('静止')
  })
})

describe('先判断是哪种情况，再挑字（解读层重写）', () => {
  const sit = (p: Position, presence: Presence, extra: Partial<Parameters<typeof situationOf>[0]> = {}) =>
    situationOf({ position: p, presence, history: EMPTY, offlineSince: null, updating: false, now: NOW, ...extra }).kind
  const lostPresence: Presence = { freshness: 'lost', live: null }
  it('页面自己没同步好的先说：正在更新压过失联，家长断网也压过失联', () => {
    expect(sit(pos({ state: 'still' }), lostPresence, { updating: true })).toBe('updating')
    expect(sit(pos({ state: 'still' }), lostPresence, { offlineSince: ago(5) })).toBe('parentOffline')
  })
  it('失联压过手机时间不对、补传；时间不对压过补传', () => {
    expect(sit(pos({ fixTime: ago(-20) }), lostPresence)).toBe('lost')
    expect(sit(pos({ fixTime: ago(-20), deviceTime: ago(-20), serverTime: NOW }), FRESH)).toBe('clockWrong')
  })
  it('补传压过「在路上」', () => {
    expect(sit(pos({ state: 'moving', fixTime: ago(30), deviceTime: ago(30), serverTime: ago(0.2) }), { freshness: 'stale', live: 'movingOld' })).toBe('backfill')
  })
  it('孩子在干什么：在路上的几种、范围内、静止、旧版 App', () => {
    expect(sit(pos({ state: 'moving', speedKmh: 12 }), LIVE)).toBe('moving')
    expect(sit(pos({ state: 'moving' }), { freshness: 'fresh', live: 'locating' })).toBe('locating')
    expect(sit(pos({ state: 'zone' }), FRESH)).toBe('zone')
    expect(sit(pos({ state: 'still' }), FRESH)).toBe('still')
    expect(sit(pos(), FRESH)).toBe('legacyIdle')
    expect(sit(pos({ fixTime: ago(15), deviceTime: ago(15), serverTime: ago(15) }), { freshness: 'stale', live: null })).toBe('legacyStale')
  })
  it('同一种情况，在常去地点里时前面加地点名', () => {
    expect(wording({ kind: 'zone' }, '学校', NOW).text).toBe('在学校附近活动')
    expect(wording({ kind: 'still', run: { since: ago(12), exact: true, until: ago(1) } }, '家', NOW).text).toBe('在家 · 静止 12 分钟')
    expect(wording({ kind: 'legacyIdle' }, undefined, NOW).text).toBe('没在动')
  })
})
