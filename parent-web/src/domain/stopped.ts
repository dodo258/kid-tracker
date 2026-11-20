// 「这一轮」里的两件事（「这一轮」本身是 presence.currentRun：从最新往前连着报同一个状态的那串上报）：
//
// 刚停下（方案 docs/specs/2026-11-18-parent-web-polish.md 第 1 条）：旧上报端从「移动中」转回范围内要 8–10 分钟，
// 这期间手机一直报移动中。网页不等它：这一轮在路上最新连着的几个卫星点，手机测速都接近 0（低于 1 米/秒，
// 和圆点停住不外推是同一个判断 fixQuality.nearlyStopped）、彼此不超过 15 米、跨了 30 秒以上，就是停下了
//（慢慢走 3 km/h 的测速也低，但 30 秒走 25 米，过不了 15 米这条）。停多久按这串第一个点的定位时间算。
// 停车之后又有了移动的证据就撤下：网络点连着计步、又离开了停车处；手机中途报过别的状态又报移动中（那是新的一轮，之前的停车不算）。
//
// 静止多久：这一轮静止从哪条上报开始。30 分钟窗口滚动以后起点会滚出去，所以每台设备记着（track.ts 的 History.still），
// 窗口里看得到起点就用看到的；看不到时，中间一条没漏才沿用记着的，漏看过一段（页面放后台）就只写「至少」。
// 都是纯函数。
import type { Position } from '../api/types'
import { isRough, nearlyStopped } from './fixQuality'
import { distanceMeters } from './places'
import { contactTime, currentRun, timelineTime, type Report } from './presence'
import { MINUTE } from './time'

/** 停着的卫星点跨这么久以上（按定位时间）才算停下：等红灯也算 */
export const STOPPED_MIN_MS = 30_000
/** 停着的卫星点都在最新那个点这么远以内（卫星点精度一般 3–10 米，停着时点之间差 0–5 米） */
export const STOPPED_RADIUS_M = 15
/** 停车以后至少来了这么多个带步数的网络点（最新那个也带），又离开了停车处：人又走起来了 */
const WALK_AWAY_FIXES = 2

type Fix = Report & Pick<Position, 'latitude' | 'longitude' | 'accuracy' | 'provider' | 'steps'>

/** 停着的那串卫星点：first 最早、newest 最新；after 是 newest 之后来的网络点（新的在前） */
type Halt = { first: Fix; newest: Fix; after: Fix[] }

/**
 * 从最新往前找停着的那串（fixes 只给这一轮在路上的点，新的在前）：网络点（Wi-Fi、基站，测速不准）跳过、不打断；
 * 碰到测速 ≥1 米/秒、或离最新那个卫星点超过 15 米就停
 */
function haltOf(newestFirst: readonly Fix[]): Halt | null {
  const after: Fix[] = []
  let newest: Fix | undefined
  let first: Fix | undefined
  for (const f of newestFirst) {
    if (isRough(f)) {
      if (!newest) after.push(f)
      continue
    }
    if (!nearlyStopped(f)) break
    newest ??= f
    if (distanceMeters(f.latitude, f.longitude, newest.latitude, newest.longitude) > STOPPED_RADIUS_M) break
    first = f
  }
  return newest && first ? { first, newest, after } : null
}

/** 停车以后的网络点说人又走起来了：连着计步，最新那个已经出了停车处（它的精度圈加 15 米） */
function walkedAway({ after, newest }: Halt): boolean {
  const walking = after.filter((f) => (f.steps ?? 0) > 0)
  if (walking.length < WALK_AWAY_FIXES || walking[0] !== after[0]) return false
  const d = distanceMeters(after[0].latitude, after[0].longitude, newest.latitude, newest.longitude)
  return d > after[0].accuracy + STOPPED_RADIUS_M
}

/** 这一轮在路上的点，新的在前：报移动中、而且是这一轮开始以后收到的（since 不知道就不限） */
function movingFixes(fixes: readonly Fix[], since: number | undefined): Fix[] {
  const out: Fix[] = []
  for (let i = fixes.length - 1; i >= 0; i--) {
    const f = fixes[i]
    if (f.state !== 'moving' || (since !== undefined && contactTime(f).getTime() < since)) break
    out.push(f)
  }
  return out
}

/**
 * 手机还报着「移动中」时，人是不是已经停下了：返回停下的时刻（停着那串第一个卫星点的定位时间），没停下返回 null。
 * fixes 是最近 30 分钟带坐标的点、reports 是所有上报（domain/track 的 History，按时间排好），latest 是最新一条上报（可以不带坐标）。
 * 旧版 App（没有 state）不判
 */
export function stoppedSince(h: { fixes: readonly Fix[]; reports: readonly Report[] }, latest: Pick<Position, 'state'>): Date | null {
  if (latest.state !== 'moving') return null
  const run = currentRun(h.reports)
  const halt = haltOf(movingFixes(h.fixes, run?.state === 'moving' ? run.since.getTime() : undefined))
  if (!halt || walkedAway(halt)) return null
  return timelineTime(halt.newest) - timelineTime(halt.first) >= STOPPED_MIN_MS ? halt.first.fixTime : null
}

/**
 * 这一轮静止从什么时候开始；exact 为假是只看到了一段（起点没看到），真正开始得更早。
 * until 是记下它时看到的最后一条静止上报的联系时间：下次窗口里全是静止时，靠它判断中间有没有漏看
 */
export type StillRun = { since: Date; exact: boolean; until: Date }

/**
 * 收到新的上报以后，这一轮静止从什么时候开始。reports 是最近 30 分钟的上报（按联系时间排好），prev 是这台设备上次记下的。
 * 最新一条不是 still 返回 undefined。窗口里看得到起点（前面有别的状态）就用它。
 * 看不到起点（窗口里全是静止）时，只有上次看到的最后一条还在窗口里（从那以后的每条上报都在窗口里、都是静止）才沿用上次记下的起点；
 * 否则中间有一段没看到（页面放后台、锁屏，回来只补拉最近 30 分钟），那段里可能动过又静止，只能从窗口里最早那条算、写「至少」。
 * 也不拿呼吸点沿用的旧定位时间去猜（那可能比静止早几个小时）
 */
export function nextStillRun(prev: StillRun | undefined, reports: readonly Report[]): StillRun | undefined {
  const run = currentRun(reports)
  const last = reports.at(-1)
  if (run?.state !== 'still' || !last) return undefined
  const until = contactTime(last)
  const seenAll = prev !== undefined && prev.until >= contactTime(reports[0]) && prev.since <= run.since
  if (run.exact || !seenAll) return { since: run.since, exact: run.exact, until }
  return { since: prev.since, exact: prev.exact, until }
}

/** 「12 分钟」「2 小时」「3 天」；不到 1 分钟是空串 */
export function durationText(ms: number): string {
  if (ms < MINUTE) return ''
  if (ms < 60 * MINUTE) return `${Math.floor(ms / MINUTE)} 分钟`
  if (ms < 24 * 60 * MINUTE) return `${Math.floor(ms / (60 * MINUTE))} 小时`
  return `${Math.floor(ms / (24 * 60 * MINUTE))} 天`
}
