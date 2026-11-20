import { describe, expect, it } from 'vitest'
import type { LatLon, Position } from '../api/types'
import { frameAt, pushFix, startPlayer, type Fix, type Player } from './player'
import { RESET_MS, smoothTrack, speedLimit } from './smooth'

const LAT0 = 30
const M_PER_DEG_LAT = 111_320
const M_PER_DEG_LON = 111_320 * Math.cos((LAT0 * Math.PI) / 180)
const northOf = (p: LatLon) => (p.latitude - LAT0) * M_PER_DEG_LAT
const eastOf = (p: LatLon) => (p.longitude - 120) * M_PER_DEG_LON

const T0 = Date.UTC(2026, 10, 18, 4, 0, 0)
const INTERVAL_S = 10
/** 每个点定位后 1 秒到浏览器 */
const DELAY_MS = 1000
const STEP_MS = 50

/** 第 sec 秒、往北 north 米、往东 east 米的一个卫星点 */
function pos(sec: number, north: number, east: number, accuracy = 10, provider = 'gps'): Position {
  const t = new Date(T0 + sec * 1000)
  return {
    id: sec,
    deviceId: 1,
    fixTime: t,
    deviceTime: t,
    serverTime: t,
    latitude: LAT0 + north / M_PER_DEG_LAT,
    longitude: 120 + east / M_PER_DEG_LON,
    speedKmh: 0,
    accuracy,
    geofenceIds: [],
    provider,
  }
}

const toFix = (p: Position): Fix => ({ at: { latitude: p.latitude, longitude: p.longitude }, time: p.fixTime.getTime(), accuracy: p.accuracy, speedKmh: p.speedKmh, course: p.course })

type Sample = { t: number; at: LatLon }

/** 和页面一样：每个点到了就整串重算，把比圆点手里更新的点按「在路上」喂给播放器；每 50 毫秒取一帧 */
function play(points: readonly Position[]): Sample[] {
  let player: Player | undefined
  const samples: Sample[] = []
  for (let i = 0; i < points.length; i++) {
    const now = points[i].fixTime.getTime() + DELAY_MS
    const { dot } = smoothTrack(points.slice(0, i + 1))
    if (!player) player = startPlayer(toFix(dot[0]))
    for (const f of dot) {
      if (f.fixTime.getTime() > player.last.time) player = pushFix(player, toFix(f), now, { live: true, catchUp: false })
    }
    const until = i + 1 < points.length ? points[i + 1].fixTime.getTime() + DELAY_MS : now + 2 * INTERVAL_S * 1000
    for (let t = now; t < until; t += STEP_MS) samples.push({ t, at: frameAt(player, t).at })
  }
  return samples
}

/** 往北匀速 speed 米/秒走 n 个点；jumps 里的第几个点换成侧跳点（往东 east 米，可负，精度 accuracy） */
function northWalk(speed: number, n: number, jumps: Map<number, { east: number; accuracy: number }> = new Map()): Position[] {
  return Array.from({ length: n }, (_, i) => {
    const j = jumps.get(i)
    return j ? pos(i * INTERVAL_S, i * INTERVAL_S * speed, j.east, j.accuracy, 'gps') : pos(i * INTERVAL_S, i * INTERVAL_S * speed, 0)
  })
}

const maxAbsEast = (s: readonly LatLon[]) => Math.max(...s.map((x) => Math.abs(eastOf(x))))

describe('侧跳点：A 往北走到 B，中间 1–3 个点跳到东边或西边', () => {
  const cases: { speed: number; count: number; offset: number; accuracy: number; side: 'east' | 'west' | 'both' }[] = []
  for (const speed of [1.4, 5])
    for (const count of [1, 2, 3])
      for (const offset of [50, 100, 150])
        for (const accuracy of [30, 60, 100])
          for (const side of ['east', 'west', 'both'] as const) cases.push({ speed, count, offset, accuracy, side })

  it.each(cases)('$speed 米/秒、$count 个点跳到 $side $offset 米、精度 $accuracy 米：圆点和轨迹线偏离 A–B 不超过 10 米', ({ speed, count, offset, accuracy, side }) => {
    const jumps = new Map<number, { east: number; accuracy: number }>()
    for (let k = 0; k < count; k++) {
      const sign = side === 'east' ? 1 : side === 'west' ? -1 : k % 2 === 0 ? 1 : -1
      jumps.set(12 + k, { east: sign * offset, accuracy })
    }
    const points = northWalk(speed, 30, jumps)
    const samples = play(points)
    expect(maxAbsEast(samples.map((x) => x.at))).toBeLessThanOrEqual(10)
    const { kept } = smoothTrack(points)
    expect(maxAbsEast(kept)).toBeLessThanOrEqual(10)
    // 圆点一直往北走，不往回拽
    const back = samples.some((s, i) => i > 0 && northOf(s.at) < northOf(samples[i - 1].at) - 0.01)
    expect(back).toBe(false)
    // 不越拖越晚：最后一个点到的时候，圆点离 B 不超过两个间隔的路
    const lastArrival = (points.at(-1)?.fixTime.getTime() ?? 0) + DELAY_MS
    const atLast = samples.find((s) => s.t >= lastArrival)?.at ?? { latitude: 0, longitude: 0 }
    expect(northOf(atLast)).toBeGreaterThan((29 - 2) * INTERVAL_S * speed)
  })

  it.each([1.4, 5])('%s 米/秒、正常点带 ±6 米误差、中间 2 个点跳到东边 100 米：正常点一个不丢，圆点偏离不超过 10 米', (speed) => {
    // 固定种子的伪随机数，每次一样
    let seed = 7
    const noise = () => ((seed = (seed * 16807) % 2147483647) / 2147483647 - 0.5) * 12
    const points = Array.from({ length: 60 }, (_, i) =>
      i === 30 || i === 31 ? pos(i * 10, i * 10 * speed, 100, 30) : pos(i * 10, i * 10 * speed + noise(), noise(), 10),
    )
    const { kept } = smoothTrack(points)
    expect(kept.map((p) => p.id)).toEqual(points.filter((_, i) => i !== 30 && i !== 31).map((p) => p.id))
    expect(maxAbsEast(play(points).map((x) => x.at))).toBeLessThanOrEqual(10)
  })

  it('跳出去的点不画进轨迹线，原始数据一个不少', () => {
    const jumps = new Map([[12, { east: 120, accuracy: 30 }], [13, { east: 130, accuracy: 30 }]])
    const points = northWalk(1.4, 30, jumps)
    const copy = points.map((p) => ({ ...p }))
    const { dot, kept } = smoothTrack(points)
    expect(points).toEqual(copy)
    expect(dot).toHaveLength(30)
    expect(kept.map((p) => p.id)).not.toContain(120)
    expect(kept.map((p) => p.id)).not.toContain(130)
    expect(kept).toHaveLength(28)
    // 圆点在这两个点上用预测的位置补上：在 A–B 线上，接着往北
    expect(Math.abs(eastOf(dot[12]))).toBeLessThan(1)
    expect(northOf(dot[13])).toBeGreaterThan(northOf(dot[12]))
  })
})

/** 往北走，从第 turnAt 个点起连续 5 个点每个转 18 度，转成往东，再一直往东 */
function gradualTurn(speed: number, turnAt: number, n: number): { points: Position[]; headings: number[] } {
  const points = [pos(0, 0, 0)]
  const headings = [0]
  let north = 0
  let east = 0
  for (let i = 1; i < n; i++) {
    const turned = Math.min(Math.max(i - turnAt + 1, 0), 5)
    const h = (turned * 18 * Math.PI) / 180
    north += Math.cos(h) * speed * INTERVAL_S
    east += Math.sin(h) * speed * INTERVAL_S
    points.push(pos(i * INTERVAL_S, north, east))
    headings.push(turned * 18)
  }
  return { points, headings }
}

/** 圆点在 [from, to) 里的行进方向（度）：首尾两帧连线 */
function headingBetween(samples: readonly Sample[], from: number, to: number): number {
  const s = samples.filter((x) => x.t >= from && x.t < to)
  const a = s[0].at
  const b = s[s.length - 1].at
  return ((Math.atan2(eastOf(b) - eastOf(a), northOf(b) - northOf(a)) * 180) / Math.PI + 360) % 360
}

describe('真拐弯要跟上', () => {
  it.each([1.4, 5, 12])('%s 米/秒连续 5 个点渐进转 90 度：一个点都不丢，圆点延迟不超过 2 个点就沿新方向走', (speed) => {
    const turnAt = 10
    const { points } = gradualTurn(speed, turnAt, 30)
    const { kept } = smoothTrack(points)
    expect(kept).toHaveLength(30)
    const samples = play(points)
    // 第 i 个点在 i+1 个点到的时候开始播（晚一个间隔），转完（第 turnAt+4 个点）以后再过 2 个点，圆点已经往正东走
    const done = turnAt + 4
    for (let i = done + 2; i < 28; i++) {
      const from = T0 + (i + 1) * INTERVAL_S * 1000 + DELAY_MS
      const h = headingBetween(samples, from, from + INTERVAL_S * 1000)
      expect(Math.abs(h - 90), `第 ${i} 段方向 ${h.toFixed(1)}°`).toBeLessThan(10)
    }
    // 修正后的位置离真实位置不远（滤波滞后有限）
    for (let i = 0; i < 30; i++) {
      const d = Math.hypot(northOf(kept[i]) - northOf(points[i]), eastOf(kept[i]) - eastOf(points[i]))
      expect(d, `第 ${i} 个点`).toBeLessThan(Math.max(10, speed * INTERVAL_S * 0.5))
    }
  })

  it('路口直角转弯：转弯后的点先存疑，下一个点接得上就接受，圆点不冲过路口太远', () => {
    // 往北 5 米/秒走 10 个点到路口，然后往东
    const points = Array.from({ length: 20 }, (_, i) => (i <= 9 ? pos(i * 10, i * 50, 0) : pos(i * 10, 450, (i - 9) * 50)))
    const { kept } = smoothTrack(points)
    expect(kept).toHaveLength(20)
    const samples = play(points)
    // 冲过路口（往北超过 450 米）不超过一个点的距离
    expect(Math.max(...samples.map((s) => northOf(s.at)))).toBeLessThan(450 + 1)
    // 转弯后 2 个点起沿正东走
    for (let i = 12; i < 19; i++) {
      const from = T0 + (i + 1) * 10_000 + DELAY_MS
      const h = headingBetween(samples, from, from + 10_000)
      expect(Math.abs(h - 90), `第 ${i} 段方向 ${h.toFixed(1)}°`).toBeLessThan(10)
    }
  })

  it('骑车遇红灯停下：圆点不冲过停车的地方再退回来', () => {
    // 5 米/秒往北，第 10 个点起停在 450 米处
    const points = Array.from({ length: 20 }, (_, i) => pos(i * 10, Math.min(i, 9) * 50, 0))
    const samples = play(points)
    expect(Math.max(...samples.map((s) => northOf(s.at)))).toBeLessThan(450 + 5)
    const back = samples.some((s, i) => i > 0 && northOf(s.at) < northOf(samples[i - 1].at) - 3)
    expect(back).toBe(false)
  })

  it('走路改成坐车（越来越快）：最多晚一个点就跟上', () => {
    const speeds = [1.4, 1.4, 1.4, 1.4, 1.4, 1.4, 6, 10, 14, 15, 15, 15, 15, 15, 15]
    let north = 0
    const points = speeds.map((v, i) => pos(i * 10, (north += i === 0 ? 0 : v * 10), 0))
    const { kept, dot } = smoothTrack(points)
    expect(kept).toHaveLength(points.length)
    expect(northOf(dot.at(-1) ?? points[0])).toBeCloseTo(north, -1)
  })
})

describe('别的情况', () => {
  it('出行方式的速度上限：走路 4、骑车 12、坐车 45 米/秒', () => {
    expect(speedLimit(1.4)).toBe(4)
    expect(speedLimit(5)).toBe(12)
    expect(speedLimit(15)).toBe(45)
  })

  it('隔了 1 分钟以上：不预测，从新点重新开始', () => {
    const points = [pos(0, 0, 0), pos(10, 14, 0), pos(10 + RESET_MS / 1000 + 1, 600, 300)]
    const { kept } = smoothTrack(points)
    expect(kept).toHaveLength(3)
    expect(northOf(kept[2])).toBeCloseTo(600, 3)
  })

  it('重新开始后的第一个点不当真：下一个点回来了，圆点跟着回来（P20 11-17 07:21 Wi-Fi 跳到 400 米外又回来）', () => {
    // 屋里几分钟一个点；跳到 400 米外（精度 100）以后 38 秒回到家（精度 59）：按精度加权，大部分听回来的那个
    const points = [pos(0, 0, 0, 59, 'wifi'), pos(200, -400, 38, 100, 'network'), pos(238, -2, -4, 59, 'wifi')]
    const { dot, kept } = smoothTrack(points)
    expect(kept).toHaveLength(3)
    expect(Math.abs(northOf(dot[2]))).toBeLessThan(50)
  })

  it('连着 4 个点都在别处、也不回来：整体换过去（不永远卡在旧的方向上）', () => {
    const points = [...northWalk(1.4, 10), ...[0, 1, 2, 3].map((k) => pos((10 + k) * 10, 500, 500 + k, 100, 'network'))]
    const { dot } = smoothTrack(points)
    expect(eastOf(dot.at(-1) ?? points[0])).toBeCloseTo(503, 0)
  })

  it('最新的是可疑点（还没看到后面的点）：圆点只沿原来的方向往前，不往旁边拐', () => {
    const points = [...northWalk(5, 10), pos(100, 500, 120, 30)]
    const { dot, kept } = smoothTrack(points)
    expect(kept).toHaveLength(10)
    const last = dot.at(-1) ?? points[0]
    expect(Math.abs(eastOf(last))).toBeLessThan(1)
    expect(northOf(last)).toBeGreaterThan(449)
    expect(last.accuracy).toBe(10)
  })

  it('网络估算当的位置（上报端 v2 屋里没卫星，确认过才报）：圆点直接到那里，不当可疑点等确认', () => {
    const estimate = { ...pos(100, 2800, 0, 86, 'network'), net: 'wifi', state: 'still' as const }
    const { dot, kept } = smoothTrack([...northWalk(1.4, 10), estimate])
    expect(kept).toHaveLength(11)
    expect(northOf(dot.at(-1) ?? estimate)).toBeCloseTo(2800, 0)
    // 旧版 App 的网络点（不带 net）照旧当可疑点：圆点不跳过去
    const legacy = { ...estimate, net: undefined }
    expect(smoothTrack([...northWalk(1.4, 10), legacy]).kept).toHaveLength(10)
  })

  it('没有点', () => {
    expect(smoothTrack([])).toEqual({ dot: [], kept: [] })
  })
})

/** 点 p 离折线 route（[北, 东] 米）最近多远 */
function offRoute(p: LatLon, route: readonly [number, number][]): number {
  const [y, x] = [northOf(p), eastOf(p)]
  let best = Infinity
  for (let i = 1; i < route.length; i++) {
    const [ay, ax] = route[i - 1]
    const [by, bx] = route[i]
    const len2 = (bx - ax) ** 2 + (by - ay) ** 2
    const k = len2 === 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * (bx - ax) + (y - ay) * (by - ay)) / len2))
    best = Math.min(best, Math.hypot(ax + k * (bx - ax) - x, ay + k * (by - ay) - y))
  }
  return best
}

/** 卫星点带上测速（km/h）和方向 */
const moving = (p: Position, speedKmh: number, course: number): Position => ({ ...p, speedKmh, course })

describe('拐弯：卫星测速的方向和这一步对得上就马上跟上', () => {
  // 模拟 bus-lights-tunnel 07:15：公交 9 米/秒往东，路口左转 45° 往东北，每 10 秒一个点、精度 7 米
  const SPEED = 9
  const turnAt = 10
  const corner: [number, number] = [0, turnAt * INTERVAL_S * SPEED]
  const route: [number, number][] = [[0, 0], corner, [corner[0] + 2000 * Math.SQRT1_2, corner[1] + 2000 * Math.SQRT1_2]]
  const bus = Array.from({ length: 20 }, (_, i) => {
    const s = i * INTERVAL_S
    if (i <= turnAt) return moving(pos(s, 0, s * SPEED, 7), SPEED * 3.6, 90)
    const d = (s - turnAt * INTERVAL_S) * SPEED * Math.SQRT1_2
    return moving(pos(s, corner[0] + d, corner[1] + d, 7), SPEED * 3.6, 45)
  })

  it('转弯后的点直接接受，圆点不冲过路口（以前沿原来的方向冲出去约 50 米）', () => {
    const worst = Math.max(...play(bus).map((s) => offRoute(s.at, route)))
    expect(worst).toBeLessThan(10)
  })
  it('侧跳点测速方向还是原来的方向：照旧当跳点，不把圆点拽过去', () => {
    const walk = northWalk(1.4, 25, new Map([[12, { east: 80, accuracy: 10 }]])).map((p) => moving(p, 5, 0))
    expect(maxAbsEast(play(walk).map((s) => s.at))).toBeLessThan(10)
    const { kept } = smoothTrack(walk)
    expect(kept.some((p) => p.id === 12 * INTERVAL_S)).toBe(false)
  })
})

describe('刹车停下（卫星测速接近 0、比预测短一截）：马上接受，速度清零', () => {
  // 模拟 P20 11-18 骑车等红灯：6 米/秒往北，最后一个点刹到 0.6 km/h，只比上一个点往前 24 米（预测 60 米）
  const ride = Array.from({ length: 10 }, (_, i) => moving(pos(i * INTERVAL_S, i * INTERVAL_S * 6, 0, 6), 21.6, 0))
  const last = ride[ride.length - 1]
  const braked = moving(pos(10 * INTERVAL_S, northOf(last) + 24, 1, 6), 0.6, 0)

  it('刹停的点不当跳点：圆点放在它上面，不再按之前的速度往前走', () => {
    const { dot, kept } = smoothTrack([...ride, braked])
    expect(kept.at(-1)?.id).toBe(braked.id)
    expect(northOf(dot[dot.length - 1])).toBeCloseTo(northOf(braked), 3)
  })
  it('紧跟着的停着（held）的点不把圆点拉回刹车前的点', () => {
    const held = { ...braked, id: 999, fixTime: new Date(braked.fixTime.getTime() + 10_000), speedKmh: 0, course: undefined, held: true as const }
    const { dot } = smoothTrack([...ride, braked, held])
    expect(northOf(dot[dot.length - 1])).toBeCloseTo(northOf(braked), 3)
  })
  it('没报测速（存成 0）、照常走到预测位置附近的点不算停下：照常平滑', () => {
    const unknown = { ...pos(10 * INTERVAL_S, northOf(last) + 60, 0, 6), speedKmh: 0 }
    const { dot } = smoothTrack([...ride, unknown, moving(pos(11 * INTERVAL_S, northOf(last) + 120, 0, 6), 21.6, 0)])
    expect(northOf(dot[dot.length - 1])).toBeGreaterThan(northOf(last) + 110)
  })
})

describe('人停着（held 的点）：圆点留在原处，速度清零', () => {
  // 1.4 米/秒往北走 10 个点，停下后手机还报移动中 2.5 分钟：fixQuality 把这些点换成圆点所在的点（held）
  const walk = northWalk(1.4, 10).map((p) => moving(p, 5, 0))
  const last = walk[walk.length - 1]
  const held = Array.from({ length: 15 }, (_, i) => ({ ...last, id: 1000 + i, fixTime: new Date(last.fixTime.getTime() + (i + 1) * 10_000), speedKmh: 0, course: undefined, held: true as const }))

  it('停下后圆点不按之前的速度往前冲，一直不动', () => {
    const samples = play([...walk, ...held])
    const stopped = last.fixTime.getTime() + INTERVAL_S * 1000 + DELAY_MS + 1000
    const after = samples.filter((s) => s.t >= stopped)
    const north = after.map((s) => northOf(s.at))
    expect(Math.max(...north) - Math.min(...north)).toBeLessThan(0.5)
    expect(Math.max(...north)).toBeLessThan(northOf(last) + 1)
  })
  it('停了以后再走：从新点重新开始，跟着点走', () => {
    const again = Array.from({ length: 4 }, (_, i) => {
      const s = held[held.length - 1].fixTime.getTime() / 1000 - T0 / 1000 + (i + 1) * INTERVAL_S
      return moving(pos(s, northOf(last) + (i + 1) * 14, 0), 5, 0)
    })
    const { dot } = smoothTrack([...walk, ...held, ...again])
    expect(northOf(dot[walk.length + held.length])).toBeCloseTo(northOf(last) + 14, 3)
  })
})
