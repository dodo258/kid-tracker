// 跳点剔除和平滑（方案 docs/specs/2026-11-17-parent-web-rewrite.md「补充：跳点剔除和平滑」）：
// 人从 A 往 B 走，信号差时有几个点跳到旁边几十上百米又回来，圆点要一直沿正确方向走，不被拽偏。
//
// 输入挪过圆点的点（domain/track.steadyOf，按定位时间排好），每次整串重算（最多几百个点），原始数据不删也不改：
// - 常速度卡尔曼滤波（kalman.ts）：按上一个状态的速度和方向预测，新点按精度加权修正；圆点和轨迹线用修正后的位置。
// - 可疑点：从上一个可信点到它的隐含速度超过当前出行方式的上限（按滤波出来的速度：走路 4、骑车 12、坐车 45 米/秒），
//   或偏离预测超过 max(1.5 倍精度, 30 米)。可疑点先不信：
//   · 后面来的点又回到原来的方向上 → 中间那几个是毛刺，不画进轨迹线，圆点在这段用预测的位置补上；
//   · 后面来的点和可疑点连得起来（从可疑点接着走，速度方向都对得上）→ 真拐弯、真停下、真加速了，接受；
//     最多晚 1–2 个点。精度差的点（网络定位、精度 >50 米）不能单独定出新方向，只能等它们自己回来，或连着 4 个时整体换过去。
//   · 还没看到后面的点时（最新那个是可疑点），圆点只沿原来的方向往前走它在这个方向上的那一截，不往旁边拐、也不超过预测。
// - 卫星点自己测的速度和方向（多普勒测速，不受位置跳的影响）和「从上一个可信点到它」这一步对得上（方向差 30° 以内、
//   速度差在 1.5 倍以内）：是真拐弯，马上接受，不先当可疑点。可疑点期间圆点沿原来的方向走，拐弯时会冲过路口
//   （模拟 bus-lights-tunnel 公交 45° 转弯冲出路线 51 米）；跳点的测速方向还是原来的方向，对不上，照旧当可疑点。
// - 卫星点自己测的速度说人几乎停着（<1 米/秒，fixQuality.nearlyStopped），位置又在原来的路线上、比预测短一截（刹车）：
//   是停下了（等红灯），马上接受，速度清零。常速度预测还按停下前的速度往前走，停车点离预测差半步以上会被当成可疑点，
//   紧跟着的「停着」点再把它当毛刺丢掉，圆点就被拉回停车前那个点（P20 11-18 骑车等红灯，拉回 29 米）。
// - 两个点隔了 1 分钟以上（不在路上时几分钟一个点、或断了一阵）中间不知道怎么走的，不预测，从新点重新开始；
//   重新开始以后先跟着点走（和以前一样），连着两步速度方向对得上才开始平滑、判跳点。
// - 人停着（fixQuality 标 held 的点）：圆点留在现在的位置，速度清零，不按停下前的速度往前冲；停了以后再来的点从它重新开始。
// - 网络估算当的位置（fixQuality.isNetEstimate，上报端 v2 屋里没卫星时确认过才报）：从它重新开始，不当可疑点。
import type { Position } from '../api/types'
import { isNetEstimate, isRough, nearlyStopped, type Steady } from '../domain/fixQuality'
import { norm, predict, startAt, sub, update, velocityBetween, type Kalman, type Vec } from './kalman'

export type Smoothed = {
  /** 圆点按这串走：可信点换成修正后的位置；毛刺和还没定下来的可疑点换成预测的位置（精度、来源沿用上一个可信点） */
  dot: Position[]
  /** 轨迹线按这串画：只有可信点（修正后的位置），毛刺和还没定下来的点不画 */
  kept: Position[]
}

/**
 * 地图实际采信的最后一个点（可信点，圆点停在它上面）：卡片的地址、围栏、时间按它。
 * 毛刺、还没定下来的可疑点不算；held 的点（人停着时没挪圆点的漂移点）坐标换成了圆点的，地址、时间还是漂移那次的，也不算
 */
export function acceptedFix(s: Smoothed): Position | undefined {
  return s.kept.filter((p) => !('held' in p && p.held)).at(-1)
}

/** 隔了这么久不预测，从新点重新开始 */
export const RESET_MS = 60_000
/** 偏离预测的门槛：max(1.5 倍精度, 30 米)（方案写的 3 倍会让精度 30 米、跳出去 50 米的点直接进滤波，把估计拽偏十几米，见 DECISIONS） */
const GATE_MIN_M = 30
const GATE_ACCURACY_K = 1.5
/** 车速快时缓弯的偏离和这一步一样按比例放宽 */
const CURVE_K = 0.4
/** 可疑点连成新轨迹时，每个点偏离新轨迹的预测不超过这一步的一半 */
const NEW_TRACK_STEP_K = 0.5
/** 网络定位、精度 >50 米的点按精度的 2 倍加权 */
const ROUGH_WEIGHT_K = 2
/** 连着这么多个可疑点以后，第 4 个还对不上原来的方向，就整体换到新的位置 */
const MAX_PENDING = 3
/** 隐含速度按至少 5 秒算（两个点隔 1 秒、差十几米是定位误差，不是 50 km/h） */
const SPEED_MIN_DT_S = 5
/** 各出行方式的速度上限（米/秒），按滤波出来的速度判断现在是哪种 */
const WALK_MAX = 4
const BIKE_MAX = 12
const CAR_MAX = 45
const WALKING_BELOW = 2.5
const CYCLING_BELOW = 7
/** 比这慢（米/秒）看不出方向：可疑点期间圆点不往前走 */
const MIN_DIRECTION_SPEED = 0.3
/** 卫星测速至少这么快（米/秒）才拿它的方向判拐弯 */
const TURN_MIN_SPEED = 1
/** 测速方向和这一步的方向最多差这么多度、速度最多差这么多倍，才算这一步是它自己走出来的 */
const TURN_MAX_ANGLE = 30
const TURN_SPEED_RATIO = 1.5
/** 刹车停下的点：离原来的路线两边、往后最多偏这么多米，比预测至少短这么多米；精度差时按 1.5 倍精度 */
const STOP_SLACK_M = 10
/** 按测速定下来的速度的标准差（米/秒） */
const DOPPLER_SPEED_SD = 1

const M_PER_DEG = 111_320
const RAD = Math.PI / 180

/** 现在的速度（米/秒）下，隐含速度最多多少 */
export function speedLimit(speed: number): number {
  if (speed < WALKING_BELOW) return WALK_MAX
  return speed < CYCLING_BELOW ? BIKE_MAX : CAR_MAX
}

type Plane = { toVec: (p: Position) => Vec; place: (p: Position, v: Vec) => Position }

/** 以第一个点为原点的平面（30 分钟里最多几十公里，平面近似够用） */
function planeAt(origin: Position): Plane {
  const kx = M_PER_DEG * Math.cos(origin.latitude * RAD)
  return {
    toVec: (p) => ({ x: (p.longitude - origin.longitude) * kx, y: (p.latitude - origin.latitude) * M_PER_DEG }),
    place: (p, v) => ({ ...p, latitude: origin.latitude + v.y / M_PER_DEG, longitude: origin.longitude + v.x / kx }),
  }
}

/** 一条可信的轨迹：滤波状态、最后一个可信点（定位时间、原始点）、是不是已经被下一个点确认过、是不是停着 */
type Track = { k: Kalman; time: number; last: Position; confirmed: boolean; halted?: boolean }

type Verdict = 'kept' | 'outlier' | 'pending'
/** verdict 不是 kept 的（毛刺、还没定下来的）at 是预测补上的位置 */
type Out = { p: Position; at: Vec; verdict: Verdict; ref: Track }

const seconds = (from: number, to: number) => (to - from) / 1000

/** 从上一个可信点到这个点的隐含速度没超过上限（还没确认的轨迹按坐车的上限） */
function speedOk(track: Track, z: Vec, p: Position): boolean {
  const dt = seconds(track.time, p.fixTime.getTime())
  const implied = norm(sub(z, track.k.pos)) / Math.max(dt, SPEED_MIN_DT_S)
  return implied <= (track.confirmed ? speedLimit(norm(track.k.vel)) : CAR_MAX)
}

/** 预测这一步走多远（米） */
const predictedStep = (track: Track, pred: Kalman) => norm(sub(pred.pos, track.k.pos))

/**
 * 这个点和这条轨迹对得上吗（pred 是预测到它的定位时间的状态）：隐含速度没超，偏离预测不超过
 * max(1.5 倍精度, 30 米, 一个上报间隔走的路的 0.4 倍)——车速快时拐个缓弯偏离也大（每个点转 20 度左右偏一步的 0.35 倍）。
 * stepS 是和前一个点隔的秒数（中间有可疑点时比离上一个可信点近，门槛不跟着可疑的那几个点拖长）
 */
function fits(track: Track, pred: Kalman, z: Vec, p: Position, stepS: number): boolean {
  const gate = Math.max(GATE_ACCURACY_K * p.accuracy, GATE_MIN_M, CURVE_K * norm(track.k.vel) * stepS)
  return speedOk(track, z, p) && norm(sub(z, pred.pos)) <= gate
}

/** 按精度加权时用的精度：网络定位、精度 >50 米的点报的精度偏乐观，按 2 倍算（权重只剩 1/4） */
const weightAccuracy = (p: Position) => (isRough(p) ? ROUGH_WEIGHT_K * p.accuracy : p.accuracy)

/**
 * 刹车停下了：测速说几乎停着，位置是刹车会停的地方——沿原来的方向，在上一个可信点和预测之间、比预测短一截以上，
 * 没往旁边偏出精度（侧跳点、拐弯的点往旁边偏，不算）。App 没测到速度时不报 speed，服务器存成 0，
 * 所以只看测速不够：照常往前走到预测位置附近的点不算停下
 */
function stoppedThere(track: Track, pred: Kalman, z: Vec, p: Position): boolean {
  const speed = norm(track.k.vel)
  if (!nearlyStopped(p) || speed < MIN_DIRECTION_SPEED || !speedOk(track, z, p)) return false
  const d = sub(z, track.k.pos)
  const slack = Math.max(GATE_ACCURACY_K * p.accuracy, STOP_SLACK_M)
  const along = (d.x * track.k.vel.x + d.y * track.k.vel.y) / speed
  const across = Math.abs(d.x * track.k.vel.y - d.y * track.k.vel.x) / speed
  return along >= -slack && along <= predictedStep(track, pred) - slack && across <= slack
}

/** 可疑点期间圆点该在哪：上一个可信点沿原来的方向，往前走测到的点在这个方向上的那一截（不后退、不超过预测） */
function alongTrack(track: Track, z: Vec, dt: number): Vec {
  const speed = norm(track.k.vel)
  if (speed < MIN_DIRECTION_SPEED) return track.k.pos
  const u = { x: track.k.vel.x / speed, y: track.k.vel.y / speed }
  const d = sub(z, track.k.pos)
  const along = Math.min(Math.max(d.x * u.x + d.y * u.y, 0), speed * dt)
  return { x: track.k.pos.x + u.x * along, y: track.k.pos.y + u.y * along }
}

/** 卫星测的速度（米/秒，平面向量）；没测速、太慢、或网络定位返回 undefined */
function dopplerVelocity(p: Position): Vec | undefined {
  const speed = p.speedKmh / 3.6
  if (isRough(p) || p.course === undefined || speed < TURN_MIN_SPEED) return undefined
  const c = p.course * RAD
  return { x: speed * Math.sin(c), y: speed * Math.cos(c) }
}

/** 从上一个可信点到这个点的这一步，和这个点自己测的速度、方向对得上（真拐弯：测速方向就是新路的方向） */
function headedThere(track: Track, z: Vec, p: Position, v: Vec): boolean {
  const step = sub(z, track.k.pos)
  const dt = Math.max(seconds(track.time, p.fixTime.getTime()), SPEED_MIN_DT_S)
  const implied = norm(step) / dt
  const speed = norm(v)
  if (implied > speed * TURN_SPEED_RATIO || implied * TURN_SPEED_RATIO < speed) return false
  const cos = (step.x * v.x + step.y * v.y) / (norm(step) * speed)
  return cos >= Math.cos(TURN_MAX_ANGLE * RAD)
}

/**
 * 从一个点重新开始，还没确认：给了上一个点就按两点算速度（下一个点对得上才确认），没给就不知道速度。
 * 没确认的轨迹不平滑，跟着点走
 */
function restart(z: Vec, p: Position, prev?: Track): Track {
  const dt = prev ? Math.max(seconds(prev.time, p.fixTime.getTime()), SPEED_MIN_DT_S) : 0
  const vel = prev ? velocityBetween(prev.k.pos, prev.k.cov.pp, z, p.accuracy, dt) : undefined
  return { k: startAt(z, p.accuracy, vel && norm(vel.v) <= CAR_MAX ? vel : undefined), time: p.fixTime.getTime(), last: p, confirmed: false }
}

/**
 * 可疑点连起来能不能成一条新轨迹：从第一个可疑点出发，速度按上一个可信点到它算，后面的点一个个都对得上。
 * 精度差的点不算数。成了返回新轨迹和各点修正后的位置
 */
function newTrack(track: Track, run: readonly { p: Position; z: Vec }[]): { track: Track; at: Vec[] } | null {
  if (run.some((r) => isRough(r.p))) return null
  const [first, ...rest] = run
  const dt = Math.max(seconds(track.time, first.p.fixTime.getTime()), SPEED_MIN_DT_S)
  const vel = velocityBetween(track.k.pos, track.k.cov.pp, first.z, first.p.accuracy, dt)
  if (norm(vel.v) > CAR_MAX) return null
  let t: Track = { k: startAt(first.z, first.p.accuracy, vel), time: first.p.fixTime.getTime(), last: first.p, confirmed: true }
  const at = [first.z]
  for (const r of rest) {
    const pred = predict(t.k, seconds(t.time, r.p.fixTime.getTime()))
    // 新轨迹要自己连得上：偏离预测不超过 max(精度, 30 米, 这一步的一半)。一团跳点从第一个跳点「接着走」，
    // 预测会再往外走一步、点却停在原处，偏离就是整整一步，过不了；缓弯、加减速只差一步的一小半
    const step = Math.max(predictedStep(t, pred), norm(sub(r.z, t.k.pos)))
    const gate = Math.max(r.p.accuracy, GATE_MIN_M, NEW_TRACK_STEP_K * step)
    if (!speedOk(t, r.z, r.p) || norm(sub(r.z, pred.pos)) >= gate) return null
    t = { k: update(pred, r.z, r.p.accuracy), time: r.p.fixTime.getTime(), last: r.p, confirmed: true }
    at.push(t.k.pos)
  }
  return { track: t, at }
}

/** 圆点该在哪的那串点（fixQuality.steadyFixes，按定位时间排好）→ 圆点走的那串和轨迹线画的那串 */
export function smoothTrack(fixes: readonly Steady<Position>[]): Smoothed {
  if (fixes.length === 0) return { dot: [], kept: [] }
  const plane = planeAt(fixes[0])
  const out: Out[] = []
  let track: Track | null = null
  let pending: { i: number; z: Vec }[] = []
  const drop = () => {
    for (const q of pending) out[q.i].verdict = 'outlier'
    pending = []
  }

  for (const p of fixes) {
    const z = plane.toVec(p)
    const time = p.fixTime.getTime()
    if (p.held && track) {
      // 人停着：圆点留在现在的位置，速度清零；还没定下来的可疑点算毛刺
      drop()
      const k: Kalman = { ...track.k, vel: { x: 0, y: 0 }, cov: { ...track.k.cov, pv: 0, vv: 0 } }
      track = { k, time, last: track.last, confirmed: track.confirmed, halted: true }
      out.push({ p, at: k.pos, verdict: 'kept', ref: track })
      continue
    }
    if (!track || time - track.time > RESET_MS || track.halted || isNetEstimate(p)) {
      // 隔太久，或停了一阵又走了：不知道中间怎么走、往哪走，从这个点重新开始。
      // 网络估算当的位置（上报端屋里没卫星时确认过两次才报）也不是跳点：圆点到那里，不等后面的点确认
      drop()
      track = restart(z, p)
      out.push({ p, at: z, verdict: 'kept', ref: track })
      continue
    }
    const pred = predict(track.k, seconds(track.time, time))
    // 前面有可疑点时，离上一个可疑点比离预测还近的，是同一团跳点（时间拖长以后它也可能落在门槛里），不算回来
    const lastPending = pending.at(-1)
    const withJumps = lastPending !== undefined && norm(sub(z, lastPending.z)) < norm(sub(z, pred.pos))
    if (stoppedThere(track, pred, z, p)) {
      // 停下了：从这个点接着算，速度按测速清零（不按停下前的速度预测），前面的可疑点算毛刺
      drop()
      track = { k: startAt(z, p.accuracy, { v: { x: 0, y: 0 }, variance: DOPPLER_SPEED_SD ** 2 }), time, last: p, confirmed: true }
      out.push({ p, at: z, verdict: 'kept', ref: track })
      continue
    }
    const stepS = seconds(out.at(-1)?.p.fixTime.getTime() ?? track.time, time)
    if (!withJumps && fits(track, pred, z, p, stepS)) {
      // 对得上：前面的可疑点是毛刺
      drop()
      track = { k: update(pred, z, weightAccuracy(p)), time, last: p, confirmed: true }
      out.push({ p, at: track.k.pos, verdict: 'kept', ref: track })
      continue
    }
    const doppler: Vec | undefined = track.confirmed ? dopplerVelocity(p) : undefined
    if (doppler && headedThere(track, z, p, doppler)) {
      // 真拐弯：这一步就是测速方向走出来的，马上换到新方向（速度按测速），前面的可疑点算毛刺
      drop()
      track = { k: startAt(z, p.accuracy, { v: doppler, variance: DOPPLER_SPEED_SD ** 2 }), time, last: p, confirmed: true }
      out.push({ p, at: z, verdict: 'kept', ref: track })
      continue
    }
    if (!track.confirmed) {
      // 重新开始后的点没被确认：听新的，速度按这两个点算
      track = restart(z, p, track)
      out.push({ p, at: z, verdict: 'kept', ref: track })
      continue
    }
    const run = [...pending.map((q) => ({ p: out[q.i].p, z: q.z })), { p, z }]
    const turned: { track: Track; at: Vec[] } | null = pending.length > 0 ? newTrack(track, run) : null
    if (turned) {
      // 真拐弯（或停下、加速）：可疑点都接受
      track = turned.track
      const idx = [...pending.map((q) => q.i), out.length]
      out.push({ p, at: z, verdict: 'kept', ref: track })
      idx.forEach((i, k) => Object.assign(out[i], { at: turned.at[k], verdict: 'kept', ref: track }))
      pending = []
      continue
    }
    if (pending.length >= MAX_PENDING) {
      // 连着好几个都对不上，也没有自己回来：换到新的位置
      drop()
      track = restart(z, p)
      out.push({ p, at: z, verdict: 'kept', ref: track })
      continue
    }
    pending.push({ i: out.length, z })
    out.push({ p, at: alongTrack(track, z, seconds(track.time, time)), verdict: 'pending', ref: track })
  }

  const dot = out.map((o) => {
    const placed = plane.place(o.p, o.at)
    if (o.verdict === 'kept') return placed
    // 预测补上的位置：精度和来源沿用上一个可信点，速度和方向按滤波
    const v = o.ref.k.vel
    const speed = norm(v)
    const course = speed >= MIN_DIRECTION_SPEED ? ((Math.atan2(v.x, v.y) / RAD + 360) % 360) : undefined
    return { ...placed, accuracy: o.ref.last.accuracy, provider: o.ref.last.provider, speedKmh: speed * 3.6, course }
  })
  return { dot, kept: dot.filter((_, i) => out[i].verdict === 'kept') }
}
