// 孩子现在怎么样：颜色档（地图圆点外圈、状态卡第一行）、在不在路上（圆点要不要匀速播放、外推、画箭头）、状态卡上的几行字。
// 都由最新一条上报（含不带坐标的呼吸点）算出来，纯函数。
//
// 新版 App 每个点带 state=moving|zone|still（方案 docs/specs/2026-11-16-three-states.md）：第一行只写状态，不写「X 分钟前」；
// - 超过 15 分钟（服务器 status.timeout=900）没有任何上报：红色「已失联 X 分钟」；
// - 在路上，位置是这次上报带来的、2 分钟内：绿色「实时 · 12 km/h · 向东北」；
// - 在路上，这次上报还没带新坐标（刚出门、卫星还没搜到），或刚开始走 3 分钟内：绿色「正在移动 · 定位中」；
// - 在路上走了一阵还是没有新位置（隧道、地铁）：黄色「移动中，位置停在 X 分钟前」。
// 没有 state 的旧点（旧版 App）照旧：≤10 分钟正常、10–30 分钟有点久、>30 分钟可能没网关机；速度 >1 km/h 算在动。
//
// 读上报的几个基本量都只在这里定义一次，别处只调用不自己算：
// - 联系时间 contactTime（服务器收到的时间）：失联判断、失联时长、「多久前」、补传都按它（旧版 App 原来按手机时间判失联、按服务器时间算时长，两边对不上）；
// - 时间轴 timelineTime：排先后、30 分钟窗口按它；
// - 这一轮 currentRun：从最新往前连着报同一状态的那串，「在路上从什么时候开始」「静止多久」「刚停下只看这一轮」都按它；
// - 带没带新坐标 hasNewFix、刚收到的补传 isFreshBackfill。
import type { Position, ReportState } from '../api/types'
import { withoutFix } from './details'
import { atRest } from './fixQuality'
import { clockLabel, isClockAhead, MINUTE } from './time'

/** 颜色档：绿（正常）、黄（有点久、位置旧了、手机时间不对）、红（失联） */
export type Freshness = 'fresh' | 'stale' | 'lost'

/** 在路上的几种：实时、定位中、位置旧了；null 是不在路上 */
export type Live = 'live' | 'locating' | 'movingOld' | null

export type Presence = { freshness: Freshness; live: Live }

/** 服务器 status.timeout=900（11-17 由 600 改：华为偶尔把 5 分钟呼吸推迟到约 10 分钟）：这么久没有任何上报算失联 */
export const LOST_MS = 15 * MINUTE
/** 在路上、最新位置在这么久以内，算实时 */
export const LIVE_MS = 2 * MINUTE
/** 上报比它带的位置晚这么多以上，说明这次上报没带新坐标（Traccar 沿用上一个坐标） */
const FIX_BEHIND_MS = MINUTE
/** 开始走后这么久还没有新位置，才说「位置停在 X 分钟前」；之前写「正在移动 · 定位中」 */
export const LOCATING_MS = 3 * MINUTE
/** 旧版 App：超过 1 km/h 算在动 */
export const MOVING_KMH = 1

type Times = Pick<Position, 'fixTime' | 'deviceTime' | 'serverTime'>
export type Report = Times & Pick<Position, 'speedKmh' | 'state'> & Partial<Pick<Position, 'unlocated'>>

/** 手机最后一次联系服务器（没坐标的呼吸也算）：按服务器收到的时间，孩子手机时间不准也不误报失联；没有时退回手机时间 */
export function contactTime(p: Times): Date {
  if (p.serverTime) return p.serverTime
  return p.deviceTime > p.fixTime ? p.deviceTime : p.fixTime
}

/**
 * 这个点在时间轴上的位置（毫秒）：一般是定位时间；手机时间比服务器快 5 分钟以上的点（定位时间比服务器收到时还晚）按服务器收到的时间，
 * 免得一个超前点压住手机校时以后的正常点（选「最新」、30 分钟窗口、轨迹线都按它排）。原始时间不改，「孩子手机时间不对」照样按定位时间判
 */
export function timelineTime(p: Times): number {
  if (p.serverTime && isClockAhead(p.fixTime, p.serverTime)) return p.serverTime.getTime()
  return p.fixTime.getTime()
}

/**
 * 两条上报谁新（>0 是 a 新）：先比时间轴（离线补传的旧点不算新），一样（没坐标的呼吸抄上一个点的定位时间）再比服务器收到的时间，最后比 id。
 * REST 快照和 WebSocket 推送谁先到都一样
 */
export function compareReports(a: Times & { id: number }, b: Times & { id: number }): number {
  return timelineTime(a) - timelineTime(b) || contactTime(a).getTime() - contactTime(b).getTime() || a.id - b.id
}

/**
 * 这一轮：最新一条上报报的状态，从什么时候开始连着报它（取这串里最早那条的联系时间）。
 * exact 为假是窗口里这串前面没有别的状态了，起点可能更早（滚出了 30 分钟窗口）。
 * 「在路上从什么时候开始」「静止多久」「刚停下只看这一轮的点」都按它，不各扫各的
 */
export type Run = { state: ReportState | undefined; since: Date; exact: boolean }

/** reports 按联系时间排好（track.ts）；没有上报返回 undefined */
export function currentRun(reports: readonly Report[]): Run | undefined {
  const last = reports.at(-1)
  if (!last) return undefined
  let since = contactTime(last)
  let i = reports.length - 1
  for (; i >= 0 && reports[i].state === last.state; i--) since = contactTime(reports[i])
  return { state: last.state, since, exact: i >= 0 }
}

/** 这一轮「在路上」从什么时候开始；最新一条不是 moving 返回 undefined */
export function movingSince(reports: readonly Report[]): Date | undefined {
  const run = currentRun(reports)
  return run?.state === 'moving' ? run.since : undefined
}

/** 这条上报带来了新坐标：上报时间和定位时间差不到 1 分钟。心跳（只报时间）的定位时间是 Traccar 沿用的上一个点的 */
export function hasNewFix(p: Pick<Position, 'fixTime' | 'deviceTime'>): boolean {
  return p.deviceTime.getTime() - p.fixTime.getTime() < FIX_BEHIND_MS
}

/** 服务器收到时比手机发出时晚这么多以上，是离线攒着、联网后补传的旧点 */
const BACKFILL_MS = 5 * MINUTE
/** 补传的点收到以后这么久内，状态句写「刚收到补传」 */
const BACKFILL_FRESH_MS = 5 * MINUTE

/** 刚收到补传的旧点（离线攒着、联网后才传上来）：服务器 5 分钟内刚收到，但手机是 5 分钟以前发的 */
export function isFreshBackfill(p: Times, now: Date): boolean {
  if (!p.serverTime) return false
  return p.serverTime.getTime() - p.deviceTime.getTime() >= BACKFILL_MS && now.getTime() - p.serverTime.getTime() <= BACKFILL_FRESH_MS
}

/** 带 state 的点（新版 App） */
function reportPresence(p: Report & { state: ReportState }, now: Date, since: Date | undefined): Presence {
  // 失联先判：手机时间不对的点之后关机，过了 15 分钟也要写「已失联」
  if (now.getTime() - contactTime(p).getTime() > LOST_MS) return { freshness: 'lost', live: null }
  if (isClockAhead(p.fixTime, now)) return { freshness: 'stale', live: null }
  if (p.state !== 'moving') return { freshness: 'fresh', live: null }
  // 从没定到过位置：没有「停在 X 前」的位置可说，就是还在定位
  if (p.unlocated) return { freshness: 'fresh', live: 'locating' }
  const fixFresh = now.getTime() - p.fixTime.getTime() <= LIVE_MS
  if (fixFresh && hasNewFix(p)) return { freshness: 'fresh', live: 'live' }
  const justStarted = since !== undefined && now.getTime() - since.getTime() <= LOCATING_MS
  if (fixFresh || justStarted) return { freshness: 'fresh', live: 'locating' }
  return { freshness: 'stale', live: 'movingOld' }
}

/**
 * 旧版 App 的颜色档：≤10 分钟正常；10–30 分钟有点久；>30 分钟可能没网、关机或 App 被停。
 * 负数（定位时间在将来）：5 分钟以内算正常，再多就是手机时间不对，看不出多久前，算黄色。
 */
export function freshness(ageMs: number): Freshness {
  if (ageMs < -5 * MINUTE) return 'stale'
  if (ageMs <= 10 * MINUTE) return 'fresh'
  if (ageMs <= 30 * MINUTE) return 'stale'
  return 'lost'
}

/**
 * 旧版 App 一个点的颜色档。「可能没网、关机或 App 被停」只看手机最后一次联系服务器（没坐标的心跳也算），
 * 和「已失联 X 分钟」用同一个联系时间（contactTime：服务器收到的时间）：刚补传上来的旧点不算失联；
 * 手机还在联系、但位置是 10 分钟以前的（心跳取不到新位置、补传的旧点），最多算「有点久」。
 */
export function positionFreshness(p: Times, now: Date): Freshness {
  const byContact = freshness(now.getTime() - contactTime(p).getTime())
  if (byContact !== 'fresh') return byContact
  return freshness(now.getTime() - p.fixTime.getTime()) === 'fresh' ? 'fresh' : 'stale'
}

/** 旧版 App：在动（>1 km/h）且位置 2 分钟内算实时，位置旧了算「移动中，位置停在…」；失联、手机时间不对不判 */
function legacyPresence(p: Report, now: Date): Presence {
  const f = positionFreshness(p, now)
  if (f === 'lost' || isClockAhead(p.fixTime, now) || p.speedKmh <= MOVING_KMH) return { freshness: f, live: null }
  if (now.getTime() - p.fixTime.getTime() <= LIVE_MS) return { freshness: f, live: 'live' }
  return { freshness: 'stale', live: 'movingOld' }
}

/** 最新一条上报 → 颜色档和在路上的几种。since 是这一轮在路上从什么时候开始（movingSince），不知道就不传 */
export function presenceOf(p: Report, now: Date, since?: Date): Presence {
  return p.state ? reportPresence({ ...p, state: p.state }, now, since) : legacyPresence(p, now)
}

/** 家长这边和服务器断了多久以后，不再按「现在」判孩子（推送断着时轮询每 30 秒一次，WebSocket 保活 55 秒一次） */
export const SYNC_STALE_MS = MINUTE

/** 页面和服务器的同步情况：WebSocket 通没通、最近一次请求的错误、最后一次成功同步（拉到数据或收到推送）的时刻 */
export type Sync = { live: boolean; error: string | null; syncedAt: Date | null }

/**
 * 家长这边连不上服务器（WebSocket 断着、请求也失败）且上次同步已经超过 1 分钟：返回最后一次同步的时刻，否则 null。
 * 这时页面手里的数据停在那一刻，本地时钟再往前走也说明不了孩子失联
 */
export function parentOfflineSince(sync: Sync, now: Date): Date | null {
  if (sync.live || sync.error === null || sync.syncedAt === null) return null
  return now.getTime() - sync.syncedAt.getTime() > SYNC_STALE_MS ? sync.syncedAt : null
}

/**
 * 家长这边断网时的颜色档：按最后一次同步那一刻判（那时已经失联的照旧红色），其余一律黄色、不算在路上（不外推、不画实时）
 */
export function offlinePresence(p: Report, syncedAt: Date, since?: Date): Presence {
  return { freshness: presenceOf(p, syncedAt, since).freshness === 'lost' ? 'lost' : 'stale', live: null }
}

/** 在路上（实时或定位中）：圆点按上报间隔匀速播放、点迟到时外推、画方向箭头 */
export function onTheMove(p: Presence): boolean {
  return p.live === 'live' || p.live === 'locating'
}

/**
 * 最新一条只报了时间（没带新坐标）、运动摘要说离上次上报一步没走也没颠簸：进楼、到站，卫星没了，人停在最后一个卫星点附近。
 * 这时还报着移动中，圆点也停在最后一个点上，不按上一段的速度往前推（上报端 v2 主坐标只放卫星点，网络估算只在 netLat/netLon）
 */
export function holdsDot(p: Pick<Position, 'outdated' | 'fixTime' | 'deviceTime' | 'state' | 'steps' | 'bumps'>): boolean {
  return withoutFix(p) && atRest(p)
}

/** 「已失联 23 分钟」「已失联 3 小时」；一天以上写最后联系的时间 */
export function lostLabel(contact: Date, now: Date): string {
  const ms = now.getTime() - contact.getTime()
  if (ms < 60 * MINUTE) return `已失联 ${Math.max(1, Math.floor(ms / MINUTE))} 分钟`
  if (ms < 24 * 60 * MINUTE) return `已失联 ${Math.floor(ms / (60 * MINUTE))} 小时`
  return `已失联，最后联系 ${clockLabel(contact, now)}`
}

const DIRECTIONS = ['北', '东北', '东', '东南', '南', '西南', '西', '西北']

/** 行进方向（度，正北 0 顺时针）→「向东北」 */
export function headingLabel(course: number): string {
  const i = Math.round((((course % 360) + 360) % 360) / 45) % 8
  return `向${DIRECTIONS[i]}`
}

// 一级行政区名：最多 8 个字，不含数字、路名门牌和别的行政区后缀，免得「区」一级吞到「某某小区」的「区」
const ADMIN = '[^\\d路街号省市区县旗镇乡]{1,8}?'
// 省 → 市（县级市「苏州市昆山市」有两个）→ 区县（东莞、中山不设区，可以没有；「小区」「社区」不算）→ 街道镇乡
const ADMIN_PREFIX = new RegExp(
  `^(?:${ADMIN}(?:省|自治区|特别行政区))?(?:${ADMIN}市){0,2}(?:${ADMIN}(?<![小社])(?:区|县|旗))?(?:${ADMIN}(?:街道|镇|乡))?(.+)$`,
)

/**
 * 「在 X 附近活动」里的地址去掉省市区街道前缀：「浙江省杭州市西湖区西溪街道教工路 18 号」→「教工路 18 号」，
 * 「江苏省苏州市昆山市玉山镇震川路 88 号某某小区 3 幢」→「震川路 88 号某某小区 3 幢」
 */
export function shortAddress(address: string | undefined): string | undefined {
  const a = address?.trim()
  if (!a) return undefined
  const m = a.match(ADMIN_PREFIX)
  const rest = m?.[1]?.trim()
  return rest || a
}
