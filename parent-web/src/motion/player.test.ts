import { describe, expect, it } from 'vitest'
import type { LatLon } from '../api/types'
import { distanceMeters } from '../domain/places'
import {
  CORRECT_MS,
  EXTRAPOLATE_MAX_M,
  EXTRAPOLATE_MAX_MS,
  FAST_TOTAL_MS,
  GLIDE_MS,
  SPEED_CAP_M_PER_MS,
  finalFrame,
  frameAt,
  isCatchUp,
  pushFix,
  retarget,
  setLive,
  startPlayer,
  type Fix,
  type Player,
  type PushMode,
} from './player'

const LAT0 = 30
const M_PER_DEG_LAT = 111_320
const M_PER_DEG_LON = 111_320 * Math.cos((LAT0 * Math.PI) / 180)
/** 从 (30, 120) 往北 north 米、往东 east 米 */
const pt = (north: number, east = 0): LatLon => ({ latitude: LAT0 + north / M_PER_DEG_LAT, longitude: 120 + east / M_PER_DEG_LON })
const northOf = (p: LatLon) => (p.latitude - LAT0) * M_PER_DEG_LAT
const eastOf = (p: LatLon) => (p.longitude - 120) * M_PER_DEG_LON
const dist = (a: LatLon, b: LatLon) => distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)

const T0 = Date.UTC(2026, 10, 17, 12, 0, 0)
const fix = (sec: number, north: number, east = 0, over: Partial<Fix> = {}): Fix => ({
  at: pt(north, east),
  time: T0 + sec * 1000,
  accuracy: 8,
  speedKmh: 18,
  ...over,
})
const LIVE: PushMode = { live: true, catchUp: false }
const CALM: PushMode = { live: false, catchUp: false }
const CATCH_UP: PushMode = { live: true, catchUp: true }

/** 从 from 到 to（毫秒，相对 T0）每 step 毫秒取一帧 */
function frames(p: Player, from: number, to: number, step = 16) {
  const out: { t: number; at: LatLon; heading: number | null; settled: boolean }[] = []
  for (let t = from; t <= to; t += step) out.push({ t, ...frameAt(p, T0 + t) })
  return out
}

/** 相邻两帧最大挪了多少米 */
function maxStep(fs: { at: LatLon }[]): number {
  let max = 0
  for (let i = 1; i < fs.length; i++) max = Math.max(max, dist(fs[i - 1].at, fs[i].at))
  return max
}

/** 按北向坐标，前后帧有没有往回走（容差 1 厘米） */
const goesBack = (fs: { at: LatLon }[]) => fs.some((f, i) => i > 0 && northOf(f.at) < northOf(fs[i - 1].at) - 0.01)

/** 每 10 秒一个点，往北 5 m/s，每个点在定位后 1 秒到 */
function steadyWalk(n: number): { p: Player; arrivals: number[] } {
  let p = startPlayer(fix(0, 0))
  const arrivals: number[] = []
  for (let i = 1; i <= n; i++) {
    const arrive = i * 10_000 + 1000
    arrivals.push(arrive)
    p = pushFix(p, fix(i * 10, i * 50), T0 + arrive, LIVE)
  }
  return { p, arrivals }
}

describe('晚一个上报间隔匀速播放', () => {
  it('新点到了：从上一个点匀速走过去，用时等于两个点的间隔', () => {
    let p = startPlayer(fix(0, 0))
    p = pushFix(p, fix(10, 50), T0 + 11_000, LIVE)
    expect(northOf(frameAt(p, T0 + 11_000).at)).toBeCloseTo(0, 3)
    expect(northOf(frameAt(p, T0 + 16_000).at)).toBeCloseTo(25, 1)
    expect(northOf(frameAt(p, T0 + 21_000).at)).toBeCloseTo(50, 1)
    // 匀速：每 100 毫秒走的一样多
    const fs = frames(p, 11_000, 21_000, 100)
    const steps = fs.slice(1).map((f, i) => northOf(f.at) - northOf(fs[i].at))
    expect(Math.max(...steps) - Math.min(...steps)).toBeLessThan(0.01)
  })
  it('一直按时来：圆点一直在走，不停不跳，每个点到的时候正好走到上一个点', () => {
    let p = startPlayer(fix(0, 0))
    const all: { at: LatLon }[] = []
    for (let i = 1; i <= 6; i++) {
      const arrive = i * 10_000 + 1000
      if (i > 1) expect(northOf(frameAt(p, T0 + arrive).at)).toBeCloseTo((i - 1) * 50, 0)
      p = pushFix(p, fix(i * 10, i * 50), T0 + arrive, LIVE)
      all.push(...frames(p, arrive, arrive + 10_000 - 16))
    }
    expect(goesBack(all)).toBe(false)
    // 5 m/s，每 16 毫秒 8 厘米
    expect(maxStep(all)).toBeLessThan(0.09)
  })
  it('走着走着方向：按这一段的方向（正北 0、正东 90）', () => {
    let p = startPlayer(fix(0, 0))
    p = pushFix(p, fix(10, 0, 50), T0 + 11_000, LIVE)
    expect(frameAt(p, T0 + 15_000).heading).toBeCloseTo(90, 0)
    p = pushFix(p, fix(20, 50, 50), T0 + 21_000, LIVE)
    expect(frameAt(p, T0 + 25_000).heading).toBeCloseTo(0, 0)
  })
})

describe('迟到时外推有上限', () => {
  it('下一个点没来：按最后的速度和方向往前推，最多 10 秒', () => {
    const { p } = steadyWalk(3) // 最后一段 21 s → 31 s 走到 150 米处
    const end = 41_000
    expect(northOf(frameAt(p, T0 + end).at)).toBeCloseTo(150, 0)
    expect(northOf(frameAt(p, T0 + end + 4_000).at)).toBeCloseTo(170, 0)
    expect(northOf(frameAt(p, T0 + end + EXTRAPOLATE_MAX_MS).at)).toBeCloseTo(200, 0)
    expect(northOf(frameAt(p, T0 + end + 30_000).at)).toBeCloseTo(200, 0)
    expect(frameAt(p, T0 + end + 30_000).settled).toBe(true)
    expect(frameAt(p, T0 + end + 4_000).settled).toBe(false)
  })
  it('走得快：最多推 80 米，推到就停', () => {
    let p = startPlayer(fix(0, 0))
    p = pushFix(p, fix(10, 250), T0 + 11_000, LIVE) // 25 m/s（90 km/h）
    expect(northOf(frameAt(p, T0 + 21_000 + 2_000).at)).toBeCloseTo(300, 0)
    expect(northOf(frameAt(p, T0 + 21_000 + 9_000).at)).toBeCloseTo(250 + EXTRAPOLATE_MAX_M, 0)
  })
  it('最后的点自己测的速度说几乎停着（刹车、等红灯）：走到它就停，不按最后一段的速度往前推', () => {
    let p = startPlayer(fix(0, 0))
    p = pushFix(p, fix(10, 50), T0 + 11_000, LIVE)
    p = pushFix(p, fix(20, 75, 0, { speedKmh: 1, stopped: true }), T0 + 21_000, LIVE)
    expect(northOf(frameAt(p, T0 + 31_000).at)).toBeCloseTo(75, 1)
    expect(northOf(frameAt(p, T0 + 31_000 + EXTRAPOLATE_MAX_MS).at)).toBeCloseTo(75, 3)
    expect(frameAt(p, T0 + 32_000).settled).toBe(true)
  })
  it('不在路上不外推', () => {
    let p = startPlayer(fix(0, 0))
    p = pushFix(p, fix(10, 50), T0 + 11_000, CALM)
    expect(northOf(frameAt(p, T0 + 11_000 + GLIDE_MS + 5_000).at)).toBeCloseTo(50, 3)
  })
})

describe('新点到了平滑校正，不回跳', () => {
  it('直着走、点迟到 4 秒：推出去的正好在该在的线上，接着往前走，不往回', () => {
    const { p: walk, arrivals } = steadyWalk(3)
    const late = arrivals[2] + 10_000 + 4_000 // 本该 41 s 到，45 s 才到
    const before = frames(walk, 21_000, late - 16)
    const p = pushFix(walk, fix(40, 200), T0 + late, LIVE)
    const after = frames(p, late, late + 12_000)
    const all = [...before, ...after]
    expect(goesBack(all)).toBe(false)
    expect(maxStep(all)).toBeLessThan(0.09)
    // 1 秒后已经在「准时到」该走的线上：41 s 起从 150 米走向 200 米
    expect(northOf(frameAt(p, T0 + late + CORRECT_MS).at)).toBeCloseTo(150 + ((late + CORRECT_MS - 41_000) / 10_000) * 50, 1)
  })
  it('迟到时拐了弯：1 秒内回到新的线上，中间不瞬移', () => {
    const { p: walk } = steadyWalk(3)
    const late = 45_000
    const before = frames(walk, 40_000, late - 16)
    const p = pushFix(walk, fix(40, 150, 50), T0 + late, LIVE) // 拐向正东
    const after = frames(p, late, late + 8_000)
    expect(maxStep([...before, ...after])).toBeLessThan(SPEED_CAP_M_PER_MS * 16)
    const onLine = frameAt(p, T0 + late + CORRECT_MS).at
    expect(northOf(onLine)).toBeCloseTo(150, 0)
    expect(eastOf(onLine)).toBeGreaterThan(0)
    // 一个间隔内走到新点
    expect(dist(frameAt(p, T0 + 51_000).at, pt(150, 50))).toBeLessThan(0.5)
  })
  it('迟到很久又跳得很远：从现在的位置直接走过去，不超过速度上限', () => {
    const { p: walk } = steadyWalk(3)
    const late = 41_000 + 25_000
    const p = pushFix(walk, fix(45, 1150), T0 + late, LIVE)
    const fs = frames(p, late - 1_000, late + 8_000)
    expect(maxStep(fs)).toBeLessThanOrEqual(SPEED_CAP_M_PER_MS * 16 + 0.01)
    expect(goesBack(fs)).toBe(false)
  })
  it('从推出去的位置直接走过去时，箭头朝圆点实际走的方向', () => {
    const { p: walk } = steadyWalk(3) // 往北走，41 s 起外推，推到 200 米处停住
    const late = 41_000 + 25_000
    // 新点在 (北 230, 东 600)，离推出去的位置太远不抹差，圆点从 200 米处直接走过去：方向约 87°（不是从 150 米处算的 82°）
    const p = pushFix(walk, fix(45, 230, 600), T0 + late, LIVE)
    const h = frameAt(p, T0 + late + 500).heading ?? -1
    expect(h).toBeGreaterThan(85)
    expect(h).toBeLessThan(95)
  })
  it('点来早了（几个一起到）：排在后面，最新的点在到达后一个间隔内走到', () => {
    let p = startPlayer(fix(0, 0))
    p = pushFix(p, fix(10, 50), T0 + 11_000, LIVE)
    p = pushFix(p, fix(20, 100), T0 + 11_500, LIVE)
    p = pushFix(p, fix(30, 150), T0 + 12_000, LIVE)
    const fs = frames(p, 11_000, 23_000)
    expect(goesBack(fs)).toBe(false)
    expect(northOf(frameAt(p, T0 + 22_000).at)).toBeCloseTo(150, 0)
    // 重新摊开：后面几段约 19 m/s，没有哪一帧冲出去
    expect(maxStep(fs)).toBeLessThan(0.35)
  })
})

describe('离线补传快放', () => {
  it('一批旧点按顺序快放，整批 4 秒内走完，每帧不超过速度上限', () => {
    let p = startPlayer(fix(0, 0))
    const arrive = 1_800_000 // 30 分钟后网恢复，一次传上来：120 个点，每 15 秒一个，约 1.3 公里
    // 120 个点在 0.6 秒内陆续推到（帧只能从最后一个点到了以后取）
    for (let i = 1; i <= 120; i++) p = pushFix(p, fix(i * 15, i * 10, (i % 7) * 2), T0 + arrive + i * 5, CATCH_UP)
    const fs = frames(p, arrive + 600, arrive + FAST_TOTAL_MS + 2_000)
    expect(goesBack(fs)).toBe(false)
    expect(maxStep(fs)).toBeLessThanOrEqual(SPEED_CAP_M_PER_MS * 16 + 0.01)
    expect(dist(frameAt(p, T0 + arrive + FAST_TOTAL_MS + 1_000).at, pt(1200, 2 * (120 % 7)))).toBeLessThan(0.5)
    // 中间确实一段段走过：2 秒时在半路
    const mid = northOf(frameAt(p, T0 + arrive + 2_000).at)
    expect(mid).toBeGreaterThan(200)
    expect(mid).toBeLessThan(1200)
  })
  it('补传的路太长（快过每秒 400 米）：放慢到速度上限，不瞬移', () => {
    let p = startPlayer(fix(0, 0))
    for (let i = 1; i <= 60; i++) p = pushFix(p, fix(i * 15, i * 50), T0 + 900_000, CATCH_UP)
    const fs = frames(p, 900_000, 900_000 + 9_000)
    expect(maxStep(fs)).toBeLessThanOrEqual(SPEED_CAP_M_PER_MS * 16 + 0.01)
    expect(northOf(frameAt(p, T0 + 900_000 + 7_600).at)).toBeCloseTo(3000, 0)
  })
  it('几个旧点：每段不到 0.6 秒，按顺序', () => {
    let p = startPlayer(fix(0, 0))
    for (let i = 1; i <= 3; i++) p = pushFix(p, fix(i * 10, i * 10), T0 + 600_000, CATCH_UP)
    expect(northOf(frameAt(p, T0 + 600_000 + 500).at)).toBeGreaterThan(5)
    expect(northOf(frameAt(p, T0 + 600_000 + 1_800).at)).toBeCloseTo(30, 1)
  })
})

describe('不在路上：滑过去', () => {
  it('0.8 秒先快后慢滑到，不画方向、不外推', () => {
    let p = startPlayer(fix(0, 0, 0, { speedKmh: 0 }))
    p = pushFix(p, fix(300, 30, 0, { speedKmh: 0 }), T0 + 301_000, CALM)
    expect(northOf(frameAt(p, T0 + 301_000 + GLIDE_MS / 2).at)).toBeCloseTo(15, 0)
    expect(northOf(frameAt(p, T0 + 301_000 + GLIDE_MS).at)).toBeCloseTo(30, 3)
    expect(frameAt(p, T0 + 301_000 + GLIDE_MS).settled).toBe(true)
  })
  it('隔得很远也不瞬移：按速度上限放慢', () => {
    let p = startPlayer(fix(0, 0))
    p = pushFix(p, fix(600, 2000), T0 + 601_000, CALM)
    const fs = frames(p, 601_000, 601_000 + 10_000)
    expect(maxStep(fs)).toBeLessThanOrEqual(SPEED_CAP_M_PER_MS * 16 + 0.01)
    expect(northOf(fs.at(-1)?.at ?? pt(0))).toBeCloseTo(2000, 1)
  })
  it('在路上但两个点隔了 30 秒以上：不按间隔慢慢走，滑过去', () => {
    let p = startPlayer(fix(0, 0))
    p = pushFix(p, fix(120, 60), T0 + 121_000, LIVE)
    expect(northOf(frameAt(p, T0 + 121_000 + GLIDE_MS).at)).toBeCloseTo(60, 3)
  })
  it('停下来时圆点正推在外面：滑回最后一个点，不跳', () => {
    const { p: walk } = steadyWalk(3)
    const t = 41_000 + 6_000
    const pushed = frameAt(walk, T0 + t).at
    expect(northOf(pushed)).toBeCloseTo(180, 0)
    const p = setLive(walk, false, T0 + t)
    expect(dist(frameAt(p, T0 + t).at, pushed)).toBeLessThan(0.01)
    expect(maxStep(frames(p, t, t + 2_000))).toBeLessThan(SPEED_CAP_M_PER_MS * 16)
    expect(northOf(frameAt(p, T0 + t + 2_000).at)).toBeCloseTo(150, 2)
  })
  it('比最后一个点旧的点不动圆点（补到轨迹线里就行）', () => {
    const { p } = steadyWalk(2)
    expect(pushFix(p, fix(5, 999), T0 + 30_000, LIVE)).toBe(p)
  })
})

describe('第一个点', () => {
  it('直接放在那儿；带了速度和方向就有方向', () => {
    const p = startPlayer(fix(0, 10, 0, { course: 45 }))
    expect(frameAt(p, T0).at).toEqual(pt(10))
    expect(frameAt(p, T0).heading).toBe(45)
    expect(frameAt(p, T0).settled).toBe(true)
    expect(startPlayer(fix(0, 10, 0, { course: 45, speedKmh: 0.5 })).heading).toBeNull()
  })
  it('减少动态效果：直接在最后一个点', () => {
    const { p } = steadyWalk(2)
    expect(finalFrame(p).at).toEqual(pt(100))
  })
})

describe('补拉以后圆点该停的点变早了', () => {
  it('滑回那个点，以后的新点从它接着算', () => {
    let p = startPlayer(fix(30, 20, 0, { speedKmh: 0 }))
    p = retarget(p, fix(10, 0, 0, { speedKmh: 0 }), T0 + 31_000)
    expect(northOf(frameAt(p, T0 + 31_000).at)).toBeCloseTo(20, 3)
    expect(northOf(frameAt(p, T0 + 31_000 + GLIDE_MS).at)).toBeCloseTo(0, 3)
    expect(p.last.time).toBe(T0 + 10_000)
    p = pushFix(p, fix(40, 60), T0 + 41_000, CALM)
    expect(northOf(frameAt(p, T0 + 41_000 + GLIDE_MS).at)).toBeCloseTo(60, 3)
  })
  it('同一时刻换了坐标（那个点被换成圆点该留的地方）：也滑过去', () => {
    const p = retarget(startPlayer(fix(10, 30)), fix(10, 0), T0 + 11_000)
    expect(northOf(frameAt(p, T0 + 11_000 + GLIDE_MS).at)).toBeCloseTo(0, 3)
  })
  it('比现在的新，或者就是现在这个点：不动', () => {
    const p = startPlayer(fix(10, 0))
    expect(retarget(p, fix(10, 0), T0 + 11_000)).toBe(p)
    expect(retarget(p, fix(20, 5), T0 + 21_000)).toBe(p)
  })
})

describe('已经走过的点（轨迹线画到这里再连到圆点）', () => {
  it('正在走的那段的出发点；走完了是终点', () => {
    const { p } = steadyWalk(2)
    expect(frameAt(p, T0 + 25_000).passed).toBe(T0 + 10_000)
    expect(frameAt(p, T0 + 35_000).passed).toBe(T0 + 20_000)
  })
})

describe('补传、补拉的旧点', () => {
  const at = (ms: number) => new Date(T0 + ms)
  it('定位后 1 分钟以上才传上服务器：旧点', () => {
    expect(isCatchUp({ fixTime: at(0), deviceTime: at(0), serverTime: at(90_000) }, T0 + 90_500)).toBe(true)
  })
  it('服务器收到 30 秒以后浏览器才拿到（回前台补拉）：旧点', () => {
    expect(isCatchUp({ fixTime: at(0), deviceTime: at(0), serverTime: at(1_000) }, T0 + 40_000)).toBe(true)
  })
  it('刚定位、刚收到：不是', () => {
    expect(isCatchUp({ fixTime: at(0), deviceTime: at(0), serverTime: at(1_500) }, T0 + 1_600)).toBe(false)
  })
})
