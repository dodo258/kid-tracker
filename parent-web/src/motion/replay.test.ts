// 用 P20 真实上报（坐标已平移）回放：11-16 10:20 到 11-17 21:23 的 498 条（fixtures/p20.json），
// 11-18 14:27–14:50 骑电动车、中间等了 50 秒红灯的 61 条（fixtures/p20-ride-1118.json）。
// 每条按服务器收到的时间到达，走和页面一样的路（domain/track 合进最近 30 分钟、steadyOf 挑出挪圆点的点、motion/smooth 剔除跳点和平滑、
// presence 判在不在路上、最新一条只报时间没走动时不往前推（holdsDot）、motion/player 播放，和 map/useKids 一样），假时钟每 50 毫秒取一帧。查：圆点不瞬移（相邻帧不超过每秒 400 米）、屋里漂的 Wi-Fi 点不挪圆点。
import { describe, expect, it } from 'vitest'
import p20Json from '../../fixtures/p20.json'
import rideJson from '../../fixtures/p20-ride-1118.json'
import { parsePositions } from '../api/parse'
import type { LatLon, Position } from '../api/types'
import { nearlyStopped } from '../domain/fixQuality'
import { distanceMeters } from '../domain/places'
import { holdsDot, movingSince, onTheMove, presenceOf } from '../domain/presence'
import { addPositions, EMPTY_HISTORY, steadyOf } from '../domain/track'
import { smoothTrack } from './smooth'
import { frameAt, isCatchUp, pushFix, retarget, setLive, SPEED_CAP_M_PER_MS, startPlayer, type Fix, type Player } from './player'

const STEP_MS = 50
/** 一条到了以后最多看这么久（之后都停稳了） */
const WATCH_MS = 60_000

const toFix = (p: Position): Fix => ({ at: { latitude: p.latitude, longitude: p.longitude }, time: p.fixTime.getTime(), accuracy: p.accuracy, speedKmh: p.speedKmh, course: p.course, stopped: nearlyStopped(p) })
const received = (p: Position) => (p.serverTime ?? p.deviceTime).getTime()
const dist = (a: LatLon, b: LatLon) => distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)

type Sample = { t: number; at: LatLon }

/** 整段回放，返回每一帧 */
function replay(points: Position[]): Sample[] {
  const sorted = [...points].sort((a, b) => received(a) - received(b) || a.id - b.id)
  let history = EMPTY_HISTORY
  let latest: Position | undefined
  let player: Player | undefined
  const samples: Sample[] = []
  for (let i = 0; i < sorted.length; i++) {
    const p = sorted[i]
    const now = received(p)
    history = addPositions(history, [p])
    if (!latest || p.fixTime >= latest.fixTime) latest = p
    const moving = onTheMove(presenceOf(latest, new Date(now), movingSince(history.reports))) && !holdsDot(latest)
    const steady = smoothTrack(steadyOf(history)).dot
    const newest = steady.at(-1)
    if (!newest) continue
    if (!player) player = setLive(startPlayer(toFix(newest)), moving, now)
    else {
      player = retarget(player, toFix(newest), now)
      for (const f of steady) {
        if (f.fixTime.getTime() > player.last.time) player = pushFix(player, toFix(f), now, { live: moving, catchUp: isCatchUp(f, now) })
      }
      player = setLive(player, moving, now)
    }
    // 到下一条之前的每一帧
    const until = Math.min(i + 1 < sorted.length ? received(sorted[i + 1]) : now + WATCH_MS, now + WATCH_MS)
    for (let t = now; t < until; t += STEP_MS) samples.push({ t, at: frameAt(player, t).at })
  }
  return samples
}

const at = (iso: string) => new Date(iso).getTime()

/** [from, to) 里的帧 */
const between = (samples: Sample[], from: string, to: string) => samples.filter((x) => x.t >= at(from) && x.t < at(to))

/** 这串帧里圆点一共挪了多少米 */
function moved(s: Sample[]): number {
  let sum = 0
  for (let i = 1; i < s.length; i++) sum += dist(s[i - 1].at, s[i].at)
  return sum
}

/** t 时刻（含）以前最后一帧 */
function frameBy(samples: Sample[], t: number): Sample {
  const f = samples.filter((x) => x.t <= t).at(-1)
  if (!f) throw new Error(`${new Date(t).toISOString()} 以前没有帧`)
  return f
}

const points = parsePositions(p20Json)
const samples = replay(points)
const travelled = (from: string, to: string) => moved(between(samples, from, to))

describe('P20 两天真实上报回放', () => {
  it('498 条都解析出来了，回放了几万帧', () => {
    expect(points).toHaveLength(498)
    expect(samples.length).toBeGreaterThan(50_000)
  })

  it('圆点从不瞬移：相邻两帧（50 毫秒）不超过每秒 400 米', () => {
    let worst = { meters: 0, t: 0 }
    for (let i = 1; i < samples.length; i++) {
      const dt = samples[i].t - samples[i - 1].t
      // 两条之间隔得比 50 毫秒还近（同一秒到的几条）的那一帧按实际间隔算
      const d = dist(samples[i - 1].at, samples[i].at)
      const allowed = SPEED_CAP_M_PER_MS * Math.max(dt, 1) + 0.5
      if (d - allowed > worst.meters) worst = { meters: d - allowed, t: samples[i].t }
    }
    expect(worst.meters, `最超速的一帧在 ${new Date(worst.t).toISOString()}`).toBeLessThanOrEqual(0)
  })

  // 11-17 07:05–07:10：App 误判成移动中，每 10 秒一个 Wi-Fi/网络点，精度 45–60 米，来回差 7–18 米，人在家
  it('人在家、Wi-Fi 点在精度圈里来回漂：圆点一动不动', () => {
    expect(travelled('2026-11-17T07:05:30+08:00', '2026-11-17T07:11:00+08:00')).toBeLessThan(0.01)
  })

  // 11-17 12:14–13:58：范围内活动，Wi-Fi 点（精度 100 米）差 0–53 米
  it('范围内活动、Wi-Fi 点漂几十米：圆点不动', () => {
    expect(travelled('2026-11-17T12:14:00+08:00', '2026-11-17T13:58:00+08:00')).toBeLessThan(0.01)
  })

  // 11-17 20:03：卫星定位（精度 4–6 米）停在一处，每 10 秒一个点，差 0–13 米。
  // 窗口开头圆点还在播 20:03:05→06 那一段（晚一个间隔，13 米）；20:03:18 起测速≈0、在精度里的点不挪（fixQuality），
  // 20:03:49 来了精度 4 米的点（之前那个 16 米）把圆点挪准 9 米，之后不动。实测 16.3 米
  it('卫星点停在一处：圆点只跟着挪那十几米，不乱跳', () => {
    expect(travelled('2026-11-17T20:03:20+08:00', '2026-11-17T20:05:00+08:00')).toBeLessThan(17)
  })
})

// P20 11-18 骑电动车：14:38:46 还 20 km/h，14:38:56 刹到 2.3 km/h（离上一个点 24 米），之后 50 秒等红灯，
// 点之间只差 0–5 米、精度 3–6 米、测速 0–0.6 km/h；14:39:58 起步。以前圆点按停下前的速度往前推、滤波把刹停的点当跳点丢掉，
// 停车期间冲出停车点最远 47 米再拉回，45 秒里挪了 100 多米。
describe('P20 骑车等红灯真实上报回放', () => {
  const ride = parsePositions(rideJson)
  const frames = replay(ride)
  /** 停车点：测速 0 的那个点（14:39:26） */
  const stop = ride.find((p) => p.fixTime.getTime() === at('2026-11-18T14:39:26+08:00')) ?? ride[0]

  it('等红灯时圆点停在停车点 10 米内，不冲出去再拉回', () => {
    expect(stop.id, '停车点是 14:39:26 那个点').toBe(932)
    const wait = between(frames, '2026-11-18T14:39:06+08:00', '2026-11-18T14:39:47+08:00')
    expect(wait.length).toBeGreaterThan(500)
    const far = Math.max(...wait.map((x) => dist(x.at, stop)))
    expect(far, '离停车点最远').toBeLessThanOrEqual(10)
    // 停着的点之间本来就差 5 米多（14:38:56 刹停的点到 14:39:16 精度更好的点），圆点只跟着挪这一下
    expect(moved(wait), '一共挪了').toBeLessThan(10)
  })

  it('起步后照常跟上：每个点到达后一个间隔（10 秒）圆点已经走到它那里，最多再往前推一段', () => {
    const go = ride.filter((p) => received(p) >= at('2026-11-18T14:40:06+08:00') && received(p) <= at('2026-11-18T14:41:59+08:00'))
    expect(go.length).toBeGreaterThan(10)
    for (const p of go) {
      const f = frameBy(frames, received(p) + 10_000)
      const label = p.fixTime.toISOString()
      // 一直往前走：圆点离停车点不比这个点近（往西直走到 14:40:44，之后往北拐）
      expect(dist(f.at, stop) + 5, `${label} 圆点还没走到这个点`).toBeGreaterThanOrEqual(dist(p, stop))
      expect(dist(f.at, p), `${label} 圆点离这个点`).toBeLessThanOrEqual(70)
    }
  })
})

// 模拟上报端（tools/simulator，`sim.py export` 导出的服务器存下的点）按上报端 v2 的行为：主坐标只放卫星点，
// 进楼、到站卫星没了以后只报时间（Traccar 标 outdated、抄上一个点的坐标和定位时间），还报着移动中、运动摘要 0 步 0 颠簸，
// 过一阵才报「附近活动」。以前圆点按最后一段的速度往前推 10 秒，停在推出去的地方，等「附近活动」到了再拉回来：
// 到达后不动的那 2–3 分钟里挪 13–43 米。窗口是真实轨迹到达（stay 段开始）后 30 秒起，和 sim.py replay 的「不动时圆点不挪」一样
describe('模拟上报端 v2：到达后只报时间', () => {
  const cases: [string, string, string][] = [
    ['bus-lights-tunnel', '07:23:34', '07:25:34'],
    ['gps-cold-start', '07:43:13', '07:45:13'],
    ['offline-backfill', '16:46:19', '16:48:19'],
    ['process-killed', '17:26:59', '17:28:59'],
    ['walk-school-jumps', '07:34:12', '07:37:12'],
  ]
  const files = import.meta.glob<unknown>('../../fixtures/sim/*.json', { eager: true, import: 'default' })
  it.each(cases)('%s：到达后圆点不挪（%s–%s 不超过 5 米）', (name, from, to) => {
    const frames = replay(parsePositions(files[`../../fixtures/sim/${name}.json`]))
    const stay = between(frames, `2026-11-20T${from}+08:00`, `2026-11-20T${to}+08:00`)
    expect(stay.length).toBeGreaterThan(1000)
    expect(moved(stay)).toBeLessThanOrEqual(5)
  })
})
