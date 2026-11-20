import { describe, expect, it } from 'vitest'
import p20Json from '../../fixtures/p20.json'
import rideJson from '../../fixtures/p20-ride-1118.json'
import { parsePositions } from '../api/parse'
import type { Position, ReportState } from '../api/types'
import { statusLine } from './status'
import { durationText, nextStillRun, stoppedSince } from './stopped'
import { addPositions, EMPTY_HISTORY } from './track'

const T0 = new Date('2026-11-18T06:38:00Z').getTime()
const M_PER_DEG = 111_320

/** 第 s 秒、往北 north 米的一个点 */
function fix(s: number, north: number, patch: Partial<Position> = {}): Position {
  const t = new Date(T0 + s * 1000)
  return {
    id: s,
    deviceId: 1,
    fixTime: t,
    deviceTime: t,
    serverTime: new Date(t.getTime() + 500),
    latitude: 30.27 + north / M_PER_DEG,
    longitude: 120.15,
    speedKmh: 0,
    accuracy: 5,
    provider: 'gps',
    state: 'moving',
    geofenceIds: [],
    ...patch,
  }
}

/** 骑车 20 km/h 往北，每 10 秒一个点，到 60 秒；之后 stopFor 秒每 10 秒一个停着的点（测速 0–1 km/h、来回差 0–4 米） */
function rideThenStop(stopFor: number): Position[] {
  const out: Position[] = []
  for (let s = 0; s <= 60; s += 10) out.push(fix(s, s * 5.5, { speedKmh: 20 }))
  for (let s = 10; s <= stopFor; s += 10) out.push(fix(60 + s, 330 + (s % 20 === 0 ? 4 : 0), { speedKmh: s % 30 === 0 ? 1 : 0.3 }))
  return out
}

describe('刚停下：手机还报移动中，最新几个卫星点测速接近 0、位置没动、跨了 30 秒以上', () => {
  it('停了 40 秒：返回停下那一刻（第一个停着的点）', () => {
    const fixes = rideThenStop(40)
    expect(stoppedSince({ fixes, reports: fixes }, fixes.at(-1) as Position)?.getTime()).toBe(T0 + 70_000)
  })
  it('停了 20 秒还不算（等 30 秒，免得减速一下就说停了）', () => {
    const fixes = rideThenStop(20)
    expect(stoppedSince({ fixes, reports: fixes }, fixes.at(-1) as Position)).toBeNull()
  })
  it('一直在动：不算', () => {
    const fixes = rideThenStop(0)
    expect(stoppedSince({ fixes, reports: fixes }, fixes.at(-1) as Position)).toBeNull()
  })
  it('又起步了（最新一个卫星点测速 12 km/h）：不算', () => {
    const fixes = [...rideThenStop(60), fix(130, 340, { speedKmh: 12 })]
    expect(stoppedSince({ fixes, reports: fixes }, fixes.at(-1) as Position)).toBeNull()
  })
  it('测速低但位置在挪（慢慢走 3 km/h，40 秒挪了 30 米）：不算', () => {
    const fixes = [fix(0, 0, { speedKmh: 3 }), fix(10, 8, { speedKmh: 3 }), fix(20, 16, { speedKmh: 3 }), fix(30, 23, { speedKmh: 3 }), fix(40, 30, { speedKmh: 3 })]
    // 离最新的点 15 米以内的只有最后三个点（20–40 秒），跨 20 秒，不到 30 秒
    expect(stoppedSince({ fixes, reports: fixes }, fixes.at(-1) as Position)).toBeNull()
  })
  it('停着时夹了 Wi-Fi 网络点（测速 0 但不准）：跳过，不打断也不算数', () => {
    const fixes = rideThenStop(40)
    fixes.splice(-2, 0, fix(85, 200, { provider: 'wifi', accuracy: 40 }))
    expect(stoppedSince({ fixes, reports: fixes }, fixes.at(-1) as Position)?.getTime()).toBe(T0 + 70_000)
  })
  it('手机已经不报移动中（范围内、静止）：交给手机的状态，不判', () => {
    const fixes = rideThenStop(60)
    for (const state of ['zone', 'still'] satisfies ReportState[]) {
      expect(stoppedSince({ fixes, reports: fixes }, { state })).toBeNull()
    }
  })
  it('停着的点前面是上一轮的范围内活动：只看这一轮在路上的点', () => {
    const fixes = [fix(0, 0, { state: 'zone' }), fix(20, 0, { state: 'zone' }), fix(40, 0), fix(55, 1)]
    expect(stoppedSince({ fixes, reports: fixes }, fixes.at(-1) as Position)).toBeNull()
  })
  it('旧版 App（没有 state）不判', () => {
    const fixes = rideThenStop(60).map((p) => ({ ...p, state: undefined }))
    expect(stoppedSince({ fixes, reports: fixes }, fixes.at(-1) as Position)).toBeNull()
  })
})

describe('P20 11-18 骑电动车等红灯（真实点）', () => {
  const ride = parsePositions(rideJson)
  /** 按服务器收到的时间一个个到，看页面手里最近 30 分钟的点 */
  const historyAt = (iso: string) => {
    const t = new Date(iso).getTime()
    return addPositions(EMPTY_HISTORY, ride.filter((p) => (p.serverTime ?? p.deviceTime).getTime() <= t))
  }
  const stoppedAt = (iso: string) => {
    const h = historyAt(iso)
    return stoppedSince(h, h.reports.at(-1) as Position)
  }

  it('14:38:56 刹停、之后点只差 0–5 米：14:39:30 已经判出刚停下，停下时刻是刹停那个点', () => {
    expect(stoppedAt('2026-11-18T14:39:30+08:00')?.toISOString()).toBe(new Date('2026-11-18T14:38:56+08:00').toISOString())
  })
  it('刹停后不到 30 秒（14:39:10）还不说', () => {
    expect(stoppedAt('2026-11-18T14:39:10+08:00')).toBeNull()
  })
  it('起步以后（14:40:10）不再是刚停下', () => {
    expect(stoppedAt('2026-11-18T14:40:10+08:00')).toBeNull()
  })
  it('一路骑的时候（14:35）不误判', () => {
    expect(stoppedAt('2026-11-18T14:35:00+08:00')).toBeNull()
  })
})

describe('静止多久', () => {
  const still = (minutesAgo: number, fixMinutesAgo = minutesAgo, state: ReportState = 'still') => {
    const t = new Date(T0 - minutesAgo * 60_000)
    return fix(-minutesAgo * 60, 0, { state, deviceTime: t, serverTime: t, fixTime: new Date(T0 - fixMinutesAgo * 60_000) })
  }
  it('从这一轮第一条静止上报算', () => {
    const reports = [still(30, 30, 'zone'), still(12, 12), still(7, 12), still(2, 12)]
    expect(nextStillRun(undefined, reports)).toEqual({ since: new Date(T0 - 12 * 60_000), exact: true, until: new Date(T0 - 2 * 60_000) })
  })
  it('30 分钟窗口里全是静止、之前没记过：从窗口里最早那条静止算、写「至少」，不拿呼吸点沿用的旧定位时间猜（审计 #6）', () => {
    const reports = [still(25, 80), still(20, 80), still(15, 80), still(10, 80), still(5, 80), still(0, 80)]
    expect(nextStillRun(undefined, reports)).toEqual({ since: new Date(T0 - 25 * 60_000), exact: false, until: new Date(T0) })
  })
  it('最新一条不是静止：没有', () => {
    const r = still(0, 0, 'zone')
    expect(nextStillRun({ since: new Date(T0 - 60 * 60_000), exact: true, until: new Date(T0 - 5 * 60_000) }, [r])).toBeUndefined()
  })
  it('时长的说法', () => {
    expect(durationText(40_000)).toBe('')
    expect(durationText(12 * 60_000 + 59_000)).toBe('12 分钟')
    expect(durationText(80 * 60_000)).toBe('1 小时')
    expect(durationText(50 * 3600_000)).toBe('2 天')
  })
})

describe('有新的移动证据就撤下「刚停下」（审计 11-18 #3）', () => {
  /** 一个个按到达顺序合进 30 分钟窗口（和页面收到推送一样） */
  const historyOf = (rows: Position[]) => rows.reduce((h, p) => addPositions(h, [p]), EMPTY_HISTORY)
  const stop = [fix(0, 0), fix(15, 1), fix(30, 0)]
  /** 卡片第一行（在路上、位置实时） */
  const headline = (rows: Position[]) => {
    const latest = rows.at(-1) as Position
    const now = latest.serverTime ?? latest.deviceTime
    const history = historyOf(rows)
    return statusLine({ position: latest, presence: { freshness: 'fresh', live: 'live' }, history, placeName: undefined, offlineSince: null, updating: false, now }).text
  }
  it('只停了 30 秒、还没新的证据：刚停下', () => {
    expect(headline([...stop, fix(40, 1)])).toBe('刚停下')
  })

  it('停了 30 秒以后又走起来：只来了带步数的 Wi-Fi 点、一路离开停车处，不再是刚停下', () => {
    const walk = [60, 120, 180, 240, 300].map((s) => fix(s, s, { provider: 'wifi', accuracy: 30, steps: 60 }))
    expect(headline([...stop, ...walk])).toBe('在路上')
  })
  it('停着时夹了不带步数、没离开的 Wi-Fi 点：还是刚停下', () => {
    const drift = [60, 120].map((s) => fix(s, 20, { provider: 'wifi', accuracy: 40, steps: 0 }))
    const h = historyOf([...stop, ...drift])
    expect(stoppedSince(h, h.reports.at(-1) as Position)?.getTime()).toBe(T0)
  })
  it('只来了一个带步数的点（在原地挪了挪）：还不撤', () => {
    const h = historyOf([...stop, fix(60, 80, { provider: 'wifi', accuracy: 30, steps: 20 })])
    expect(stoppedSince(h, h.reports.at(-1) as Position)?.getTime()).toBe(T0)
  })
  it('中途报过静止又重新移动（两次都沿用停车时的坐标）：之前的停车不算这一轮的', () => {
    const old = fix(30, 0).fixTime
    const rows = [...stop, fix(600, 0, { state: 'still', fixTime: old }), fix(660, 0, { fixTime: old }), fix(680, 0)]
    expect(headline(rows)).toBe('在路上')
  })
})

describe('每台设备记住本轮静止起点，窗口滚动时长不跳（审计 11-18 #6）', () => {
  const MIN = 60_000
  const report = (minute: number, state: ReportState, fixMinute: number): Position => {
    const t = new Date(T0 + minute * MIN)
    return fix(minute * 60, 0, { id: 1000 + minute, state, deviceTime: t, serverTime: t, fixTime: new Date(T0 + fixMinute * MIN) })
  }

  it('范围内 → 静止以后只有呼吸点（沿用两小时前的定位）：上一条范围内滚出 30 分钟窗口，起点也不变', () => {
    let h = EMPTY_HISTORY
    h = addPositions(h, [report(0, 'zone', -120)])
    const seen: (number | undefined)[] = []
    for (let m = 1; m <= 60; m += 5) {
      h = addPositions(h, [report(m, 'still', -120)])
      seen.push(h.still?.since.getTime())
    }
    expect(new Set(seen)).toEqual(new Set([T0 + MIN]))
    expect(h.still?.exact).toBe(true)
    expect(h.reports.some((p) => p.state === 'zone')).toBe(false)
  })
  it('又动了再静止：重新计时', () => {
    let h = EMPTY_HISTORY
    for (const r of [report(0, 'still', 0), report(5, 'moving', 5), report(10, 'still', 10)]) h = addPositions(h, [r])
    expect(h.still?.since).toEqual(new Date(T0 + 10 * MIN))
    expect(h.still?.exact).toBe(true)
  })
  it('页面放后台漏看了一段（中间可能动过又静止）：回前台只补拉到 30 分钟，不沿用之前记下的起点，写「至少」（审查退回第 2 轮）', () => {
    // 07:55、07:59 在路上，08:00 起静止，页面一直开着看到了起点
    let h = EMPTY_HISTORY
    for (const r of [report(-5, 'moving', -5), report(-1, 'moving', -1), report(0, 'still', 0), report(5, 'still', 0), report(10, 'still', 0)]) {
      h = addPositions(h, [r])
    }
    expect(h.still?.since).toEqual(new Date(T0))
    expect(h.still?.exact).toBe(true)
    // 锁屏到 10:00 回来，补拉最近 30 分钟：09:30–10:00 每 5 分钟一条静止，中间 08:10–09:30 没看到
    const back = [90, 95, 100, 105, 110, 115, 120].map((m) => report(m, 'still', 0))
    h = addPositions(h, back)
    expect(h.still?.since).toEqual(new Date(T0 + 90 * MIN))
    expect(h.still?.exact).toBe(false)
  })
  it('页面一直开着、中间只是没新消息又补拉到了（窗口里还有上次看到的那条）：起点照旧', () => {
    let h = EMPTY_HISTORY
    for (const r of [report(-1, 'zone', -1), report(0, 'still', 0), report(5, 'still', 0)]) h = addPositions(h, [r])
    h = addPositions(h, [10, 15, 20, 25, 30, 35].map((m) => report(m, 'still', 0)))
    expect(h.reports.some((p) => p.state === 'zone')).toBe(false)
    expect(h.still?.since).toEqual(new Date(T0))
    expect(h.still?.exact).toBe(true)
  })
  it('P20 11-17 逐点回放：静止期间每来一条，时长比上一条多的不超过实际过去的时间', () => {
    const rows = parsePositions(p20Json).sort((a, b) => (a.serverTime ?? a.deviceTime).getTime() - (b.serverTime ?? b.deviceTime).getTime())
    let h = EMPTY_HISTORY
    let prev: { at: number; since: number } | undefined
    let checked = 0
    for (const p of rows) {
      h = addPositions(h, [p])
      const at = (p.serverTime ?? p.deviceTime).getTime()
      if (!h.still) {
        prev = undefined
        continue
      }
      const since = h.still.since.getTime()
      if (prev) {
        // 时长 = at - since；比上一条多的 = (at - prev.at) + (prev.since - since)，起点只能不动
        expect(since).toBe(prev.since)
        checked++
      }
      prev = { at, since }
    }
    expect(checked).toBeGreaterThan(20)
  })
})
