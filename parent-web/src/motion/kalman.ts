// 常速度模型的卡尔曼滤波（方案 docs/specs/2026-11-17-parent-web-rewrite.md「补充：跳点剔除和平滑」）：
// 状态是平面上的位置和速度（米、米/秒，东和北两个方向互相独立、噪声一样，所以两个方向共用一个 2×2 协方差）。
// 按上一个状态的速度和方向预测下一个位置，新点按精度加权修正：精度差的点只把估计拉过去一点点。

/** 平面坐标（米）：x 往东，y 往北 */
export type Vec = { x: number; y: number }

/** 位置方差、位置速度协方差、速度方差（每个方向一样） */
type Cov = { pp: number; pv: number; vv: number }

export type Kalman = { pos: Vec; vel: Vec; cov: Cov }

/** 加速度噪声（米²/秒³）：走路、骑车、坐车在城里加减速、拐弯 */
const ACCEL_NOISE = 0.02
/** 精度没报或报得太小：按这么多米算 */
const MIN_ACCURACY_M = 5
/** 不知道速度时速度的标准差（米/秒） */
const UNKNOWN_SPEED_SD = 5

const sq = (v: number) => v * v
export const sub = (a: Vec, b: Vec): Vec => ({ x: a.x - b.x, y: a.y - b.y })
export const norm = (v: Vec) => Math.hypot(v.x, v.y)

/** 测量噪声的方差 */
export function measurementVar(accuracy: number): number {
  return sq(Math.max(accuracy, MIN_ACCURACY_M))
}

/** 从一个点开始：位置就是它；速度给了就用（连同它的方差，两个点相减得来的），没给当 0（标准差 5 米/秒） */
export function startAt(pos: Vec, accuracy: number, vel?: { v: Vec; variance: number }): Kalman {
  return { pos, vel: vel?.v ?? { x: 0, y: 0 }, cov: { pp: measurementVar(accuracy), pv: 0, vv: vel?.variance ?? sq(UNKNOWN_SPEED_SD) } }
}

/** 两个点相减算速度：from 是起点的位置和方差，隔 dt 秒到 to（精度 accuracy） */
export function velocityBetween(from: Vec, fromVar: number, to: Vec, accuracy: number, dt: number): { v: Vec; variance: number } {
  const d = sub(to, from)
  return { v: { x: d.x / dt, y: d.y / dt }, variance: (fromVar + measurementVar(accuracy)) / (dt * dt) }
}

/** 往后预测 dt 秒 */
export function predict(k: Kalman, dt: number): Kalman {
  const { pp, pv, vv } = k.cov
  const q = ACCEL_NOISE
  return {
    pos: { x: k.pos.x + k.vel.x * dt, y: k.pos.y + k.vel.y * dt },
    vel: k.vel,
    cov: {
      pp: pp + 2 * dt * pv + dt * dt * vv + (q * dt ** 3) / 3,
      pv: pv + dt * vv + (q * dt * dt) / 2,
      vv: vv + q * dt,
    },
  }
}

/** 用一个测到的位置修正（已经预测到这个点的时间） */
export function update(k: Kalman, z: Vec, accuracy: number): Kalman {
  const { pp, pv, vv } = k.cov
  const s = pp + measurementVar(accuracy)
  const kp = pp / s
  const kv = pv / s
  const inn = sub(z, k.pos)
  return {
    pos: { x: k.pos.x + kp * inn.x, y: k.pos.y + kp * inn.y },
    vel: { x: k.vel.x + kv * inn.x, y: k.vel.y + kv * inn.y },
    cov: { pp: (1 - kp) * pp, pv: (1 - kp) * pv, vv: vv - kv * pv },
  }
}
