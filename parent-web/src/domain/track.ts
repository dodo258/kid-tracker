// 一台设备最近收到的点，和读它们时要跨过 30 分钟窗口记住的东西。按收到的批次一批批合进来（addPositions），每批之后：
// - reports：最近 30 分钟所有上报（含不带坐标的呼吸、状态点），按联系时间排好：「这一轮」（presence.currentRun）、「在路上从什么时候开始」按它；
// - fixes：最近 30 分钟真正带了新坐标的点（Traccar 给呼吸点沿用上一个坐标和定位时间，按定位时间去重就去掉了；
//   手机从没定到过位置时的 0,0 占位 unlocated 不算），
//   按时间轴排好（presence.timelineTime：一般是定位时间，手机时间快的超前点按服务器收到的时间）：离线补传、补拉的轨迹插到该在的位置；
// - steady：圆点该在哪的那串点（fixQuality.steadyFixes：挪过圆点的点，加上没挪圆点、坐标换成圆点所在的点的 held），
//   地图圆点和轨迹线都按它。第一个可能早于 30 分钟（跨窗口记着的锚点）：静止几个小时后圆点还停在那个点上，
//   新来的 Wi-Fi 点要和它比，不能因为它过了 30 分钟就换成窗口里第一个漂移点；
// - still：这一轮静止从什么时候开始（跨窗口记着，stopped.nextStillRun）：起点滚出窗口后时长不跳。
// 窗口里的东西随时能从点重算；跨窗口记着的（steady 的锚点、still）只在这里接着往下传，别处不自己从窗口里猜。
import type { Position } from '../api/types'
import { steadyFixes, type Steady } from './fixQuality'
import { contactTime, timelineTime } from './presence'
import { nextStillRun, type StillRun } from './stopped'
import { MINUTE } from './time'

/** 轨迹线画多久的 */
export const TRAIL_MS = 30 * MINUTE
/** reports 最多留这么多条（30 分钟里每 10 秒一条是 180 条） */
const MAX_REPORTS = 400

/** still：这一轮静止从什么时候开始（stopped.nextStillRun），窗口滚动时记着，不在静止是 undefined */
export type History = { reports: readonly Position[]; fixes: readonly Position[]; steady: readonly Steady<Position>[]; still?: StillRun }

export const EMPTY_HISTORY: History = { reports: [], fixes: [], steady: [] }

const byContact = (a: Position, b: Position) => contactTime(a).getTime() - contactTime(b).getTime() || a.id - b.id
const byFix = (a: Position, b: Position) => timelineTime(a) - timelineTime(b) || a.id - b.id

type Fixes = Pick<History, 'fixes' | 'steady'>

/** 上报：按 id 去重后合进来、按联系时间排好、只留最新一条往前 30 分钟（最多 400 条）；这一轮静止的起点接着记 */
function mergeReports(h: History, fresh: readonly Position[]): Pick<History, 'reports' | 'still'> {
  const all = [...h.reports, ...fresh].sort(byContact)
  const newest = contactTime(all[all.length - 1]).getTime()
  const reports = all.filter((p) => contactTime(p).getTime() >= newest - TRAIL_MS).slice(-MAX_REPORTS)
  return { reports, still: nextStillRun(h.still, reports) }
}

/**
 * 定位点：同一个定位时间只留一个（呼吸、状态点沿用上一个坐标，不是新位置），按时间轴排好、只留最新一个往前 30 分钟；
 * 圆点那串从上次窗口外留下的锚点接着算（steady 的第一个可能早于 30 分钟）。没有新坐标返回 null（照旧）
 */
function mergeFixes(h: History, fresh: readonly Position[]): Fixes | null {
  const fixTimes = new Set(h.fixes.map((p) => p.fixTime.getTime()))
  const newFixes = fresh.filter((p) => !p.unlocated && !fixTimes.has(p.fixTime.getTime()) && fixTimes.add(p.fixTime.getTime()))
  if (newFixes.length === 0) return null
  const all = [...h.fixes, ...newFixes].sort(byFix)
  const cutoff = timelineTime(all[all.length - 1]) - TRAIL_MS
  const first = timelineTime(all[0])
  const seed = h.steady.filter((p) => timelineTime(p) < first).at(-1)
  const chain = steadyFixes(seed ? [seed, ...all] : all)
  const anchor = chain.filter((p) => timelineTime(p) < cutoff).at(-1)
  const recent = chain.filter((p) => timelineTime(p) >= cutoff)
  return { fixes: all.filter((p) => timelineTime(p) >= cutoff), steady: anchor ? [anchor, ...recent] : recent }
}

/** 收到一批点（打开时的最新位置、WebSocket 推来的、补拉的 30 分钟轨迹），合进去：按 id 去重，上报和定位点各自排好、滚动窗口 */
export function addPositions(h: History, incoming: readonly Position[]): History {
  const seen = new Set(h.reports.map((p) => p.id))
  const fresh = incoming.filter((p) => !seen.has(p.id) && seen.add(p.id))
  if (fresh.length === 0) return h
  return { ...h, ...mergeReports(h, fresh), ...mergeFixes(h, fresh) }
}

/** 圆点该在哪的那串点：地图上的圆点按这些走 */
export function steadyOf(h: History): readonly Steady<Position>[] {
  return h.steady
}

/** 轨迹线：最近 30 分钟（按 now 算）圆点走过的点，和圆点走的是同一串 */
export function trailOf(steady: readonly Position[], now: Date): Position[] {
  const from = now.getTime() - TRAIL_MS
  return steady.filter((p) => timelineTime(p) >= from)
}
