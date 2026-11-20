// 状态卡收起时的三行字（方案 docs/specs/2026-11-18-parent-web-polish.md「卡片」）：
// - 状态句（全页最大的字）：一句大白话。「在家 · 静止 12 分钟」「在路上 · 22 km/h 向东北」「刚停下 · 2 分钟」「已失联 18 分钟」「手机时间不对」「手机刚重启」；
// - 在哪：地图实际采信的那个定位点（圆点停在的点）的地址或常去地点名；被剔除的漂移点不换地址，只有心跳时写「上次位置」；
// - 多久前：最后一次联系，「刚刚」「3 分钟前」。
//
// 分两步：先判断现在是哪种情况（situationOf，按优先级，一种情况一个分支，带上措辞要用的时刻），再挑字和颜色（statusLine）。
// 判断只看这几样，每样只有一个来源：最新一条上报和它的联系时间（presence.contactTime）、颜色档和在不在路上（presence，由调用方算好传进来）、
// 这一轮（presence.currentRun，「刚停下」「静止多久」）、页面自己的同步情况（正在更新、家长断网）。纯函数。
import type { Position } from '../api/types'
import { fixSummary } from './details'
import { estimateText } from './fixQuality'
import { contactTime, hasNewFix, headingLabel, isFreshBackfill, LIVE_MS, lostLabel, MOVING_KMH, shortAddress, type Presence } from './presence'
import { durationText, stoppedSince, type StillRun } from './stopped'
import type { History } from './track'
import { agoLabel, clockLabel, isClockAhead, MINUTE } from './time'

/** 前缀小圆点和字的颜色：安（在线、在动）、平（灰，位置旧了、时间不对、家长这边断网、正在更新）、警（失联） */
export type Tone = 'an' | 'calm' | 'jing'

/** 状态句；pulse 是前缀圆点呼吸（实时在路上，和地图上的圆点一样） */
export type Status = { text: string; tone: Tone; pulse: boolean }

export const UPDATING = '正在更新…'
export const SLOW = '同步较慢'
export const PARENT_OFFLINE = '家长这边连不上服务器'
export const CLOCK_WRONG = '手机时间不对'
/** 手机重启后孩子还没解锁过：照样在报，但只用系统定位（屋里多半只报时间），状态也是刚开机时的 */
export const REBOOTED = '手机刚重启'
/** 这台手机还从没定到过位置 */
export const NO_POSITION = '还没有位置，手机还没定到'

/** 只看到一段静止时，看到的超过这么久才写「静止至少 X」 */
const PARTIAL_STILL_MIN_MS = 10 * MINUTE

type StatusInput = {
  position: Position
  presence: Presence
  /** 最近 30 分钟的点和这一轮静止的起点（「刚停下」「静止多久」要用） */
  history: Pick<History, 'reports' | 'fixes' | 'still'>
  /** 孩子现在所在的常去地点名 */
  placeName: string | undefined
  /** 家长这边连不上服务器时最后一次同步的时刻 */
  offlineSince: Date | null
  /** 刚回到前台、还在拉最新数据：先说「正在更新」，不按停在后台那会儿的数据闪一下「已失联」 */
  updating: boolean
  /** 回到前台 10 秒了刷新还没结果：说「同步较慢」，不按旧数据判失联 */
  slow?: boolean
  now: Date
}

/** 现在是哪种情况；时刻都是措辞要用的（多久前、几点） */
export type Situation =
  /** 页面自己的事：刚回到前台还在拉（slow：10 秒了还没结果）、家长这边连不上服务器 */
  | { kind: 'updating'; slow: boolean; contact: Date }
  | { kind: 'parentOffline' }
  /** 15 分钟没有任何上报 */
  | { kind: 'lost'; contact: Date }
  /** 手机重启后孩子还没解锁过（上报带 locked） */
  | { kind: 'rebooted' }
  /** 孩子手机时间快 5 分钟以上 */
  | { kind: 'clockWrong' }
  /** 离线攒着的旧点刚传上来 */
  | { kind: 'backfill'; fixTime: Date }
  /** 在路上：位置旧了、实时、刚停下、正在定位 */
  | { kind: 'movingOld'; fixTime: Date }
  | { kind: 'moving'; speedKmh: number; course: number | undefined }
  | { kind: 'stopped'; since: Date }
  | { kind: 'locating' }
  /** 不在路上：范围内活动、静止（这一轮起点，不知道是 undefined） */
  | { kind: 'zone' }
  | { kind: 'still'; run: StillRun | undefined }
  /** 旧版 App（没有 state）：位置旧了、没在动 */
  | { kind: 'legacyStale'; fixTime: Date }
  | { kind: 'legacyIdle' }

/** 第一步：现在是哪种情况。顺序就是优先级：页面自己没同步好的先说，失联压过一切，再看手机刚重启、手机时间、补传，最后看孩子在干什么 */
export function situationOf({ position: p, presence, history, offlineSince, updating, slow, now }: Omit<StatusInput, 'placeName'>): Situation {
  if (updating) return { kind: 'updating', slow: slow ?? false, contact: contactTime(p) }
  if (offlineSince) return { kind: 'parentOffline' }
  if (presence.freshness === 'lost') return { kind: 'lost', contact: contactTime(p) }
  if (p.locked) return { kind: 'rebooted' }
  if (isClockAhead(p.fixTime, now)) return { kind: 'clockWrong' }
  if (isFreshBackfill(p, now) && !p.unlocated) return { kind: 'backfill', fixTime: p.fixTime }
  switch (presence.live) {
    case 'movingOld':
      return { kind: 'movingOld', fixTime: p.fixTime }
    case 'live': {
      const since = stoppedSince(history, p)
      return since ? { kind: 'stopped', since } : { kind: 'moving', speedKmh: p.speedKmh, course: p.course }
    }
    case 'locating':
      return { kind: 'locating' }
    case null:
      break
  }
  if (p.state === 'zone') return { kind: 'zone' }
  if (p.state === 'still') return { kind: 'still', run: history.still }
  return presence.freshness === 'stale' && !p.unlocated ? { kind: 'legacyStale', fixTime: p.fixTime } : { kind: 'legacyIdle' }
}

/** 「在路上 · 22 km/h 向东北」 */
function onTheRoad(speedKmh: number, course: number | undefined): string {
  if (speedKmh <= MOVING_KMH) return '在路上'
  const speed = `${Math.round(speedKmh)} km/h`
  return `在路上 · ${course === undefined ? speed : `${speed} ${headingLabel(course)}`}`
}

/** 「静止 12 分钟」；只看到一段时「静止至少 25 分钟」，看到的不到 10 分钟只写「静止」 */
function stillText(run: StillRun | undefined, now: Date): string {
  if (!run) return '静止'
  const ms = now.getTime() - run.since.getTime()
  const d = run.exact || ms >= PARTIAL_STILL_MIN_MS ? durationText(ms) : ''
  if (!d) return '静止'
  return run.exact ? `静止 ${d}` : `静止至少 ${d}`
}

/** 第二步：挑字和颜色。在常去地点里时，范围内、静止、没在动前面加「在家」 */
export function wording(s: Situation, placeName: string | undefined, now: Date): Status {
  const calm = (text: string): Status => ({ text, tone: 'calm', pulse: false })
  const an = (text: string, pulse = false): Status => ({ text, tone: 'an', pulse })
  const at = placeName ? `在${placeName}` : null
  switch (s.kind) {
    case 'updating':
      return calm(s.slow ? `${SLOW} · 最后联系 ${clockLabel(s.contact, now)}` : UPDATING)
    case 'parentOffline':
      return calm(PARENT_OFFLINE)
    case 'lost':
      return { text: lostLabel(s.contact, now), tone: 'jing', pulse: false }
    case 'rebooted':
      return calm(REBOOTED)
    case 'clockWrong':
      return calm(CLOCK_WRONG)
    case 'backfill':
      return calm(`刚收到补传，位置在 ${agoLabel(s.fixTime, now)}`)
    case 'movingOld':
      return calm(`在路上 · 位置停在 ${agoLabel(s.fixTime, now)}`)
    case 'moving':
      return an(onTheRoad(s.speedKmh, s.course), true)
    case 'stopped': {
      const d = durationText(now.getTime() - s.since.getTime())
      return an(d ? `刚停下 · ${d}` : '刚停下')
    }
    case 'locating':
      return an('在路上 · 正在定位')
    case 'zone':
      return an(`${at ?? '在'}附近活动`)
    case 'still': {
      const still = stillText(s.run, now)
      return an(at ? `${at} · ${still}` : still)
    }
    case 'legacyStale':
      return calm(`${agoLabel(s.fixTime, now)}的位置`)
    case 'legacyIdle':
      return an(at ? `${at} · 没在动` : '没在动')
  }
}

/** 状态句写什么、什么颜色 */
export function statusLine(input: StatusInput): Status {
  return wording(situationOf(input), input.placeName, input.now)
}

/** 第三行：左边人在哪，右边最后一次联系是多久前；wrap 是这行带了「网络估算 · 误差约 X 米」，最多两行，误差不被截掉 */
export type WhereLine = { where: string; ago: string; wrap?: true }

/**
 * 第三行。shown 是地图实际采信的那个定位点（圆点停在的点，被剔除的漂移点不算），地址和定位方式按它；
 * 在常去地点里时状态句已经写了地点名，这里写地址（没有地址才写地点名）；没有地址时写这次定位的方式和误差，好歹知道准不准。
 * 失联时说「最后在 …」。最新一条只是心跳（没带新坐标）、采信的位置 2 分钟以前的：写「上次位置 … · X 前」，右边「刚刚在线」。
 * 右边按最新一条上报（呼吸点、被剔除的点也算）的联系时间。
 * shown 为 null 是这台手机还从没定到过位置（只报时间，Traccar 存成 0,0）：写「还没有位置，手机还没定到」。
 * 采信的点是网络估算当的位置（上报端 v2 屋里没卫星时）：地址后面写「网络估算 · 误差约 X 米」，家长知道这是个大概
 */
export function whereLine(latest: Position, shown: Position | null, placeName: string | undefined, lost: boolean, now: Date): WhereLine {
  const ago = agoLabel(contactTime(latest), now)
  if (!shown) return { where: NO_POSITION, ago }
  const address = shortAddress(shown.address) ?? placeName ?? null
  const estimate = estimateText(shown)
  const name = address && estimate ? `${address} · ${estimate}` : (estimate ?? address)
  const line = whereText(latest, shown, name, lost, now, ago)
  return estimate ? { ...line, wrap: true } : line
}

function whereText(latest: Position, shown: Position, name: string | null, lost: boolean, now: Date, ago: string): WhereLine {
  if (lost) return { where: name ? `最后在 ${name}` : (fixSummary(shown) ?? '还没有地址'), ago }
  if (!hasNewFix(latest) && now.getTime() - shown.fixTime.getTime() > LIVE_MS) {
    const where = name ?? fixSummary(shown)
    return { where: `上次位置${where ? ` ${where}` : ''} · ${agoLabel(shown.fixTime, now)}`, ago: `${ago}在线` }
  }
  return { where: name ?? fixSummary(shown) ?? '还没有地址', ago }
}
