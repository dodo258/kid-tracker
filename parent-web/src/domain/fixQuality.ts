// 一个定位点有多准、要不要把地图上的圆点挪过去（方案 docs/specs/2026-11-17-parent-web-rewrite.md「不动时稳」）。
// 卫星点（App 标 gps，或没标来源但精度 ≤50 米）一般都挪。网络点（高德/系统的 Wi-Fi、基站定位）和精度 >50 米的点是「大概位置」：
// 落在现在圆点的精度圈里（半径取两个点精度里大的那个：两个圈互相罩得住，说明是同一个地方）就不挪，
// 屋里 Wi-Fi 定位来回漂几十米，圆点不跟着跳；明显更准（精度不到现在的一半）的点照样挪过去。
// P20 11-17 07:05–07:10：每 10 秒一个 Wi-Fi/网络点，精度 45–60 米，来回差 7–18 米，人在家没动。
//
// 手机说人没在赶路（范围内活动、静止，或报着移动中但运动摘要一步没走、也没颠簸）时，这段时间的网络点整体只说明「人还在这附近」，
// 一个都不挪圆点：Wi-Fi 定位会一点点漂走（模拟 home-wifi-drift：8 分钟漂到 700 米外），每个点离上一个都不远，
// 只比相邻两点或「在不在圈里」挡不住。这时只有落在圈里、明显更准的点能把圆点挪准一点；卫星点照常挪。
// 上报端 v2 把确认过的网络估算当主坐标报的点（isNetEstimate）不算慢漂，出了圈就挪。
// 卫星点报的速度≈0、离圆点不超过两个点的精度加起来（两个精度圈挨着，可能是同一处）：人停着，是卫星定位的抖动
// （停下后手机还报「移动中」的那 2–3 分钟，两个点之间常差到 1–1.5 倍精度），不挪。
//
// 不挪的点不丢：换成圆点所在的那个点（坐标、精度、来源都用它，速度 0），标 held，表示「这时人还在原处」，
// 播放器拿到它就知道圆点该停住，不会按上一段的速度往外推。
import type { LatLon, Position } from '../api/types'
import { distanceMeters } from './places'

/** 精度比这个差（米）就是大概位置 */
export const ROUGH_ACCURACY_M = 50

export type FixSource = 'satellite' | 'network'

/** App 的 attributes.provider：gps 是卫星；wifi、network 是高德 Wi-Fi/基站定位或系统网络定位 */
const NETWORK_PROVIDERS = new Set(['wifi', 'network', 'cell'])

type Fix = LatLon & Pick<Position, 'accuracy' | 'provider'> & Partial<Pick<Position, 'speedKmh' | 'course' | 'state' | 'steps' | 'bumps' | 'net'>>

/** 圆点该在哪的一串里的一个点：held 是没挪圆点的点（坐标已换成圆点所在的点） */
export type Steady<T> = T & { held?: true }

/** 卫星点报的速度低于这个（km/h）算停着：手机静止时卫星测速一般在 1 km/h 以内，小孩慢慢走也有 3 km/h */
export const STOP_KMH = 2

/**
 * 卫星点自己测的速度（多普勒测速，不受位置跳的影响）低于这个（km/h，1 米/秒）：人几乎停着——等红灯、刹车停下、站着。
 * 这时圆点不能再按停下前的速度往前推（P20 11-18 骑车等红灯，测速 0–2.3 km/h，圆点冲出停车点 44 米再拉回）
 */
export const CRAWL_KMH = 3.6

/** 卫星点还是网络点：App 标了来源就按它，没标（旧版 App）按精度猜 */
export function fixSource(p: Pick<Position, 'accuracy' | 'provider'>): FixSource {
  if (p.provider === 'gps') return 'satellite'
  if (p.provider !== undefined && NETWORK_PROVIDERS.has(p.provider)) return 'network'
  return p.accuracy > ROUGH_ACCURACY_M ? 'network' : 'satellite'
}

/**
 * 网络估算当的位置：上报端 v2（每条都带 net，旧版 App 不带）主坐标只放卫星点，屋里一直没卫星时才把确认过的网络估算放进主坐标
 *（provider 是 network，accuracy 是估算误差；上报端已经核对过离上一个位置够远、连着两次一致）。
 * 它不是 Wi-Fi 慢漂，人没在赶路时也照样挪圆点
 */
export function isNetEstimate(p: Pick<Position, 'provider'> & Partial<Pick<Position, 'net'>>): boolean {
  return p.provider === 'network' && p.net !== undefined
}

/** 网络估算当的位置，卡片上写「网络估算 · 误差约 86 米」；别的点 null */
export function estimateText(p: Pick<Position, 'provider' | 'accuracy' | 'net'>): string | null {
  if (!isNetEstimate(p)) return null
  return p.accuracy > 0 ? `网络估算 · 误差约 ${Math.round(p.accuracy)} 米` : '网络估算'
}

/** 大概位置：网络定位，或精度差于 50 米（精度 0 是没报，不算） */
export function isRough(p: Pick<Position, 'accuracy' | 'provider'>): boolean {
  return fixSource(p) === 'network' || p.accuracy > ROUGH_ACCURACY_M
}

/** 这个点自己说人几乎停着：卫星点、测速低于 1 米/秒。网络点（Wi-Fi、基站）一般不报速度（报 0），不算 */
export function nearlyStopped(p: Pick<Position, 'accuracy' | 'provider' | 'speedKmh'>): boolean {
  return !isRough(p) && p.speedKmh < CRAWL_KMH
}

/**
 * 手机说人没在赶路：状态是范围内活动、静止；或者报着移动中，但运动摘要里离上次上报一步没走、也没颠簸（误判成移动中）。
 * 没带运动摘要的移动中不算
 */
export function atRest(p: Pick<Position, 'state' | 'steps' | 'bumps'>): boolean {
  if (p.state === 'zone' || p.state === 'still') return true
  return p.state === 'moving' && p.steps === 0 && p.bumps === 0
}

/** 圆点现在在 anchor（上一个挪过圆点的点），来了 next：要不要挪过去 */
export function movesDot(anchor: Fix | undefined, next: Fix): boolean {
  if (!anchor) return true
  const d = distanceMeters(anchor.latitude, anchor.longitude, next.latitude, next.longitude)
  const inCircle = d <= Math.max(anchor.accuracy, next.accuracy)
  const sharper = next.accuracy > 0 && next.accuracy * 2 <= anchor.accuracy
  if (!isRough(next)) return sharper || d > anchor.accuracy + next.accuracy || (next.speedKmh ?? 0) >= STOP_KMH
  if (atRest(next) && !isNetEstimate(next)) return sharper && inCircle
  return sharper || !inCircle
}

/**
 * 按定位时间排好的点 → 圆点该在哪的一串：挪圆点的点原样留着（第一个总算），不挪的换成圆点所在的点、标 held。
 * 地图圆点和轨迹线都按这一串
 */
export function steadyFixes<T extends Fix>(fixes: readonly T[]): Steady<T>[] {
  const out: Steady<T>[] = []
  let anchor: T | undefined
  for (const f of fixes) {
    if (movesDot(anchor, f)) {
      anchor = f
      out.push(f)
    } else if (anchor) {
      const { latitude, longitude, accuracy, provider } = anchor
      out.push({ ...f, latitude, longitude, accuracy, provider, speedKmh: 0, course: undefined, held: true })
    }
  }
  return out
}

/** 状态卡上精度那一段：「误差约 8 米」；网络定位写「大概位置（Wi-Fi/基站定位）· 误差约 100 米」；精度 0（没报）不写 */
export function accuracyText(p: Pick<Position, 'accuracy' | 'provider'>): string | null {
  if (p.accuracy <= 0) return null
  const meters = `误差约 ${Math.round(p.accuracy)} 米`
  if (fixSource(p) === 'network') return `大概位置（Wi-Fi/基站定位）· ${meters}`
  return p.accuracy > ROUGH_ACCURACY_M ? `大概位置 · ${meters}` : meters
}
