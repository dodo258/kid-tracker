// 地图上孩子圆点怎么动（方案 docs/specs/2026-11-17-parent-web-rewrite.md「同类怎么做」：打车软件那样晚一个上报间隔再播）。
// 纯函数：时间都由调用方传进来（毫秒，和定位时间同一个时钟），单测用假时钟。
//
// - 在路上：第 N 个点到了，圆点从第 N-1 个点沿直线匀速走到第 N 个点，用时 = 两个点的定位间隔，
//   下一个点按时到的时候正好走到，看起来连续；代价是比真实位置晚一个间隔（默认 10 秒）。
// - 下一个点迟到：按最后一段的速度和方向往前推，最多推 10 秒、80 米，再晚就停在推到的地方。
//   最后那个点自己测的速度说人几乎停着（卫星测速 <1 米/秒，fixQuality.nearlyStopped）：不推，停在那个点上——
//   刹车、等红灯时最后一段的速度还是停下前的，按它推会冲出停车点几十米再拉回（P20 11-18 骑车等红灯）。
//   点到了，按「它准时到」该走的那条线接着走，和推出去的位置之间的差在 1 秒内平滑抹掉：直着走时看不出校正，拐弯了也不瞬移。
// - 点来得比播放快（网络抖动、几个一起到）：接在后面排队，重新分配时长，保证最新的点到达后一个间隔内走到，不越积越晚。
// - 补传的旧点（离线一阵后一批传上来、回到前台补拉）：按顺序快放（约 20 倍速），整批最多 4 秒。
// - 不在路上（静止、范围内、旧版 App 没在动）、或两个点隔了 30 秒以上：新位置 0.8 秒滑过去（先加速后减速），不外推。
// - 任何时候圆点每秒最多走 400 米（街道级别缩放下每帧几个像素），离得再远也是滑过去，不瞬移。
import type { LatLon } from '../api/types'
import { distanceMeters } from '../domain/places'
import { bearing } from './heading'

/** 一个定位点：坐标 WGS-84，time 是定位时间（毫秒）；stopped 是这个点自己测的速度说人几乎停着（走到它以后不外推） */
export type Fix = { at: LatLon; time: number; accuracy: number; speedKmh: number; course?: number; stopped?: boolean }

/**
 * 一帧：圆点在哪、朝哪（不知道方向时 null）、是不是已经停稳（停稳了地图可以不再逐帧重画）、
 * 已经走过的最后一个点的定位时间（轨迹线画到这个点再连到圆点，不画到圆点前面去）
 */
export type Frame = { at: LatLon; heading: number | null; settled: boolean; accuracy: number; passed: number }

/** 这个点怎么播：live 在路上（匀速、迟到外推），catchUp 是补传或补拉的旧点（快放） */
export type PushMode = { live: boolean; catchUp: boolean }

type Kind = 'live' | 'calm' | 'fast'

type Leg = {
  readonly from: LatLon
  readonly to: LatLon
  readonly start: number
  readonly end: number
  readonly kind: Kind
  /** 这一段的行进方向（度）；太短看不出方向时沿用上一段 */
  readonly heading: number | null
  /** 出发和到达的点的定位时间 */
  readonly fromTime: number
  readonly toTime: number
  /** 到达的点说人几乎停着：走完这段不外推 */
  readonly stops: boolean
}

/** 新点到了以后，显示位置和「该走的线」之间的差，1 秒内抹掉 */
type Correction = { readonly dLat: number; readonly dLon: number; readonly start: number }

export type Player = {
  /** 最后一个点：最后一段的终点 */
  readonly last: Fix
  readonly legs: readonly Leg[]
  readonly correction: Correction | null
  /** 没有哪一段可用时的方向：第一个点自带的方向（速度 >1 km/h 才算） */
  readonly heading: number | null
  /** 在路上：最后一段是匀速段时，点迟到了就外推 */
  readonly live: boolean
}

/** 两个点隔了这么久以上就不按间隔匀速走了（中间不知道走的哪条路），直接滑过去 */
export const LEG_MAX_MS = 30_000
export const GLIDE_MS = 800
const MIN_LEG_MS = 200
/** 圆点最快每毫秒 0.4 米（每秒 400 米） */
export const SPEED_CAP_M_PER_MS = 0.4
export const EXTRAPOLATE_MAX_MS = 10_000
export const EXTRAPOLATE_MAX_M = 80
export const CORRECT_MS = 1_000
/** 校正的差超过这么多米（点迟到很久、又跳得很远）就不抹差了，从现在的位置直接走过去 */
const CORRECT_MAX_M = 100
/** 补传的旧点快放：20 倍速，每段 0.12–0.6 秒，整批最多 4 秒 */
const FAST_RATE = 20
const FAST_MIN_MS = 120
const FAST_MAX_MS = 600
export const FAST_TOTAL_MS = 4_000
/** 一段短于这么多米看不出方向 */
const MIN_HEADING_M = 3
/** 最后一段走完这么久以后不再画方向（停住了，方向是旧的） */
const HEADING_HOLD_MS = LEG_MAX_MS
/** 比这慢（米/毫秒，约 1 km/h）不外推 */
const MIN_EXTRAPOLATE_SPEED = 1 / 3600
const MOVING_KMH = 1

const linear = (u: number) => u
/** 先加速后减速（smoothstep：最快时是平均速度的 1.5 倍） */
const smooth = (u: number) => u * u * (3 - 2 * u)
const SMOOTH_PEAK = 1.5
const clamp = (x: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, x))

function lerp(a: LatLon, b: LatLon, k: number): LatLon {
  return { latitude: a.latitude + (b.latitude - a.latitude) * k, longitude: a.longitude + (b.longitude - a.longitude) * k }
}

function meters(a: LatLon, b: LatLon): number {
  return distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
}

/** 从 p 朝 heading 走 d 米（几十米内用平面近似） */
function offset(p: LatLon, headingDeg: number, d: number): LatLon {
  const h = (headingDeg * Math.PI) / 180
  const dLat = (d * Math.cos(h)) / 111_320
  const dLon = (d * Math.sin(h)) / (111_320 * Math.cos((p.latitude * Math.PI) / 180))
  return { latitude: p.latitude + dLat, longitude: p.longitude + dLon }
}

/** 一段至少要走多久：不能快过每秒 400 米（先快后慢的段按最快的那一刻算）；快放以外的段不短于 200 毫秒 */
function minDuration(from: LatLon, to: LatLon, kind: Kind = 'live'): number {
  const byCap = (meters(from, to) * (kind === 'calm' ? SMOOTH_PEAK : 1)) / SPEED_CAP_M_PER_MS
  return kind === 'fast' ? byCap : Math.max(MIN_LEG_MS, byCap)
}

/** 服务器收到以后这么久浏览器才拿到（回前台、重连后补拉的）算旧点 */
const LATE_ARRIVAL_MS = 30_000
/** 定位后这么久才传上服务器（离线时记下、恢复后补传的）算旧点 */
const LATE_UPLOAD_MS = 60_000

/** 补传或补拉的旧点：快放，不按间隔慢慢走 */
export function isCatchUp(p: { fixTime: Date; deviceTime: Date; serverTime?: Date }, now: number): boolean {
  const received = (p.serverTime ?? p.deviceTime).getTime()
  return now - received > LATE_ARRIVAL_MS || received - p.fixTime.getTime() > LATE_UPLOAD_MS
}

/** 第一个点：圆点直接放在那儿 */
export function startPlayer(fix: Fix): Player {
  const heading = fix.speedKmh > MOVING_KMH && fix.course !== undefined ? fix.course : null
  return { last: fix, legs: [], correction: null, heading, live: false }
}

/** t 时刻正在走（或刚走完）的那一段：开始时间不晚于 t 的最后一段 */
function legAt(legs: readonly Leg[], t: number): Leg | undefined {
  for (let i = legs.length - 1; i >= 0; i--) {
    if (legs[i].start <= t) return legs[i]
  }
  return undefined
}

/** 这一段走完以后再往前推多少米（不外推返回 0） */
function extrapolated(p: Player, leg: Leg, t: number): number {
  if (!p.live || leg.kind !== 'live' || leg.stops || t <= leg.end) return 0
  const speed = meters(leg.from, leg.to) / Math.max(1, leg.end - leg.start)
  if (speed < MIN_EXTRAPOLATE_SPEED) return 0
  return Math.min(speed * Math.min(t - leg.end, EXTRAPOLATE_MAX_MS), EXTRAPOLATE_MAX_M)
}

/** 不算校正的位置：沿着排好的线走 */
function basePosition(p: Player, t: number): LatLon {
  const leg = legAt(p.legs, t)
  if (!leg) return p.legs[0]?.from ?? p.last.at
  if (t < leg.end) {
    const u = (t - leg.start) / Math.max(1, leg.end - leg.start)
    return lerp(leg.from, leg.to, (leg.kind === 'calm' ? smooth : linear)(clamp(u, 0, 1)))
  }
  const ext = extrapolated(p, leg, t)
  return ext > 0 && leg.heading !== null ? offset(leg.to, leg.heading, ext) : leg.to
}

function correctionAt(c: Correction | null, t: number): { dLat: number; dLon: number } {
  if (!c || t >= c.start + CORRECT_MS) return { dLat: 0, dLon: 0 }
  const u = clamp((t - c.start) / CORRECT_MS, 0, 1)
  const left = 1 - u * u * (3 - 2 * u)
  return { dLat: c.dLat * left, dLon: c.dLon * left }
}

/** t 时刻圆点在哪 */
export function positionAt(p: Player, t: number): LatLon {
  const b = basePosition(p, t)
  const c = correctionAt(p.correction, t)
  return { latitude: b.latitude + c.dLat, longitude: b.longitude + c.dLon }
}

/** t 时刻的行进方向：正在走的那段的方向；走完 30 秒以后不再给（停住了） */
function headingAt(p: Player, t: number): number | null {
  const leg = legAt(p.legs, t)
  if (!leg) return p.heading
  return t - leg.end <= HEADING_HOLD_MS ? leg.heading : null
}

/** 最后一段走完以后正推在外面、还没推到头 */
function extrapolating(p: Player, t: number): boolean {
  const last = p.legs.at(-1)
  if (!last || t < last.end) return false
  const ext = extrapolated(p, last, t)
  return ext > 0 && ext < EXTRAPOLATE_MAX_M && t - last.end < EXTRAPOLATE_MAX_MS
}

/** t 时刻已经走过的最后一个点：正在走的那段的出发点，走完了就是终点 */
function passedAt(p: Player, t: number): number {
  const leg = legAt(p.legs, t)
  if (!leg) return p.legs.length > 0 ? p.legs[0].fromTime : p.last.time
  return t < leg.end ? leg.fromTime : leg.toTime
}

/** t 时刻这一帧 */
export function frameAt(p: Player, t: number): Frame {
  const last = p.legs.at(-1)
  const moving = last !== undefined && t < last.end
  const correcting = p.correction !== null && t < p.correction.start + CORRECT_MS
  return {
    at: positionAt(p, t),
    heading: headingAt(p, t),
    settled: !moving && !correcting && !extrapolating(p, t),
    accuracy: p.last.accuracy,
    passed: passedAt(p, t),
  }
}

/** 减少动态效果时：直接放在最后一个点上 */
export function finalFrame(p: Player): Frame {
  const last = p.legs.at(-1)
  return { at: p.last.at, heading: last ? last.heading : p.heading, settled: true, accuracy: p.last.accuracy, passed: p.last.time }
}

/**
 * 还没走完的段（end > now）从 now 起重新排：正在走的那段从现在的位置接着走，
 * 各段时长按原来的比例缩放到一共 total 毫秒，但每段不短于它的最短时长
 */
function retime(p: Player, legs: readonly Leg[], now: number, total: number): Leg[] {
  const done = legs.filter((l) => l.end <= now)
  const pending = legs
    .filter((l) => l.end > now)
    .map((l) => (l.start < now ? { ...l, from: basePosition({ ...p, legs }, now), start: now } : l))
  const sum = pending.reduce((s, l) => s + (l.end - l.start), 0)
  if (pending.length === 0 || sum <= 0) return [...legs]
  const scale = total / sum
  let at = now
  const out = pending.map((l) => {
    const d = Math.max((l.end - l.start) * scale, minDuration(l.from, l.to, l.kind))
    const leg = { ...l, start: at, end: at + d }
    at += d
    return leg
  })
  return [...done.slice(-1), ...out]
}

/** 最后一段之前的段不再用得着了：只留还没走完的，加上最后走完的那一段（外推、方向用） */
function prune(legs: readonly Leg[], now: number): Leg[] {
  const pending = legs.filter((l) => l.end > now)
  const lastDone = legs.filter((l) => l.end <= now).at(-1)
  return lastDone ? [lastDone, ...pending] : pending
}

/** 新点到了（now 是到达时间）。比最后一个点旧或一样的不管（轨迹线照样画它） */
export function pushFix(p: Player, fix: Fix, now: number, mode: PushMode): Player {
  if (fix.time <= p.last.time) return p
  const cur = positionAt(p, now)
  const gap = fix.time - p.last.time
  const legs = prune(p.legs, now)
  const prev = legs.at(-1)
  const prevEnd = prev?.end ?? now
  const busy = prevEnd > now
  const d = meters(p.last.at, fix.at)
  const fixHeading = fix.speedKmh > MOVING_KMH && fix.course !== undefined ? fix.course : null
  const heading = d >= MIN_HEADING_M ? bearing(p.last.at, fix.at) : (fixHeading ?? prev?.heading ?? p.heading)
  const base = { last: fix, heading: p.heading, live: mode.live }
  const stops = fix.stopped === true
  // 排在后面：从上一个点出发，接在最后一段后面
  const queued = (kind: Kind, duration: number): Leg => ({
    from: p.last.at,
    to: fix.at,
    start: prevEnd,
    end: prevEnd + Math.max(duration, minDuration(p.last.at, fix.at, kind)),
    kind,
    heading,
    fromTime: p.last.time,
    toTime: fix.time,
    stops,
  })
  // 从现在的位置出发（圆点停着、在外推、或在滑）：方向按圆点实际要走的方向
  const fromHere = (kind: Kind, duration: number): Leg => ({
    from: cur,
    to: fix.at,
    start: now,
    end: now + Math.max(duration, minDuration(cur, fix.at, kind)),
    kind,
    heading: meters(cur, fix.at) >= MIN_HEADING_M ? bearing(cur, fix.at) : heading,
    fromTime: p.last.time,
    toTime: fix.time,
    stops,
  })

  if (mode.catchUp) {
    // 排着的已经超过 4 秒：这一段按速度上限走（能多快多快），否则 20 倍速
    const queuedMs = legs.filter((l) => l.end > now).reduce((s, l) => s + (l.end - Math.max(l.start, now)), 0)
    const duration = queuedMs >= FAST_TOTAL_MS ? 0 : clamp(gap / FAST_RATE, FAST_MIN_MS, FAST_MAX_MS)
    const next = busy ? [...legs, queued('fast', duration)] : [...legs, fromHere('fast', duration)]
    const left = next.filter((l) => l.end > now).reduce((s, l) => s + (l.end - Math.max(l.start, now)), 0)
    const legsOut = left > FAST_TOTAL_MS ? retime(p, next, now, FAST_TOTAL_MS) : next
    return { ...base, legs: legsOut, correction: busy ? p.correction : null }
  }

  if (mode.live && gap <= LEG_MAX_MS) {
    if (busy) {
      // 来早了：排在后面，整串重新分配时长，最新的点在到达后一个间隔走到
      const next = [...legs, queued('live', gap)]
      return { ...base, legs: retime(p, next, now, gap), correction: p.correction }
    }
    if (prev && prev.kind === 'live' && p.live) {
      // 准时或迟到：按「准时到」该走的线走（从上一个点、上一段走完时出发），和外推出去的位置之间的差 1 秒内抹掉
      const end = Math.max(prev.end + gap, now + CORRECT_MS, prev.end + minDuration(p.last.at, fix.at))
      const planned: Leg = { from: p.last.at, to: fix.at, start: prev.end, end, kind: 'live', heading, fromTime: p.last.time, toTime: fix.time, stops }
      const next = { ...base, legs: [...legs, planned], correction: null }
      const on = basePosition(next, now)
      if (meters(cur, on) > CORRECT_MAX_M) return { ...base, legs: [...legs, fromHere('live', end - now)], correction: null }
      return { ...next, correction: { dLat: cur.latitude - on.latitude, dLon: cur.longitude - on.longitude, start: now } }
    }
    // 刚开始走：从现在的位置匀速走过去
    return { ...base, legs: [...legs, fromHere('live', gap)], correction: null }
  }

  // 不在路上，或隔太久：滑过去
  const next = busy ? [...legs, queued('calm', GLIDE_MS)] : [...legs, fromHere('calm', GLIDE_MS)]
  return { ...base, legs: next, correction: busy ? p.correction : null }
}

/**
 * 圆点该停的点换了：换成一个更早的点，或者同一时刻的点换了坐标（补拉到最近 30 分钟的点以后，发现最新那个只是屋里漂的
 * Wi-Fi 点，圆点该留在之前的点上，fixQuality 把它换成了圆点所在的点）。从现在的位置滑过去（0.8 秒），以后的新点从它接着算。
 * 比最后一个点新的、或者和它一样的不管
 */
export function retarget(p: Player, fix: Fix, now: number): Player {
  const same = fix.at.latitude === p.last.at.latitude && fix.at.longitude === p.last.at.longitude
  if (fix.time > p.last.time || (fix.time === p.last.time && same)) return p
  const cur = positionAt(p, now)
  const end = now + Math.max(GLIDE_MS, minDuration(cur, fix.at, 'calm'))
  const leg: Leg = { from: cur, to: fix.at, start: now, end, kind: 'calm', heading: null, fromTime: fix.time, toTime: fix.time, stops: true }
  return { ...p, last: fix, legs: [...prune(p.legs, now), leg], correction: null }
}

/**
 * 在不在路上变了（presence）。不在路上了而圆点正推在外面：从推到的地方滑回最后一个点（0.8 秒），不跳回去；
 * 正在走的段照常走完
 */
export function setLive(p: Player, live: boolean, now: number): Player {
  if (live === p.live) return p
  if (live || !extrapolatedNow(p, now)) return { ...p, live }
  const cur = positionAt(p, now)
  const to = p.last.at
  const end = now + Math.max(GLIDE_MS, minDuration(cur, to, 'calm'))
  const leg: Leg = { from: cur, to, start: now, end, kind: 'calm', heading: null, fromTime: p.last.time, toTime: p.last.time, stops: true }
  return { ...p, live, legs: [...prune(p.legs, now), leg], correction: null }
}

/** 最后一段走完了、圆点被推在外面（推到头停住的也算） */
function extrapolatedNow(p: Player, now: number): boolean {
  const last = p.legs.at(-1)
  return last !== undefined && now >= last.end && extrapolated(p, last, now) > 0
}
