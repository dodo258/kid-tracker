// 「这次定位的详情」二级页的各行（方案 docs/specs/2026-11-18-parent-web-info-sheet.md 第 2 节，2026-11-18-parent-web-polish 挪进二级页），
// 和没有地址时卡片上「在哪」那行的定位方式。都按最新一条上报的字段：有就显示那一行，没有就不显示；文案是家长看得懂的中文，不认识的取值原样写出来。
// 卡片上已经写了的（电量百分比、在路上时的速度方向）这里不重复。
import type { Position } from '../api/types'
import { accuracyText, isNetEstimate } from './fixQuality'
import { clockLabel, MINUTE } from './time'

export type DetailRow = { label: string; value: string }

/** App 的 attributes.provider → 收起时小字里的短说法；详细信息里卫星再补「（GPS）」 */
const PROVIDER: Record<string, string> = {
  gps: '卫星定位',
  wifi: 'Wi-Fi 定位',
  network: '基站定位',
  cell: '基站定位',
  // 上报端 11.0.16 起：屋里定不到卫星时，用连着的 Wi-Fi 以前卫星定到的位置顶上（policy/NetFallback）
  anchor: '按连着的 Wi-Fi 位置',
}

/** attributes.net（新字段），没有时按旧字段 wifiType */
const NET: Record<string, string> = { wifi: 'Wi-Fi', hotspot: '热点 / 随身 Wi-Fi', cellular: '移动网络' }
const WIFI_TYPE: Record<string, string> = { fixed: 'Wi-Fi', hotspot: '热点 / 随身 Wi-Fi', none: '移动网络' }

const STATE: Record<string, string> = { moving: '移动中', zone: '在附近活动', still: '静止' }

/** 上报晚到超过这么久算补传 */
const LATE_MS = MINUTE

const lookup = (table: Record<string, string>, key: string) => (Object.prototype.hasOwnProperty.call(table, key) ? table[key] : key)

/** 这次上报没带坐标（只报了在线）：Traccar 标 outdated，或位置比上报时间早 1 分钟以上（沿用了上一个点） */
export function withoutFix(p: Pick<Position, 'outdated' | 'fixTime' | 'deviceTime'>): boolean {
  return p.outdated === true || p.deviceTime.getTime() - p.fixTime.getTime() >= MINUTE
}

/**
 * 收起时小字里的定位方式和精度：「卫星定位 · 误差约 8 米」「Wi-Fi 定位 · 误差约 60 米」；
 * 旧版 App 没标来源时只写精度（「误差约 8 米」，按精度猜的大概位置写「大概位置（Wi-Fi/基站定位）· …」）
 */
export function fixSummary(p: Pick<Position, 'accuracy' | 'provider'>): string | null {
  if (p.provider === undefined) return accuracyText(p)
  const parts = [lookup(PROVIDER, p.provider)]
  if (p.accuracy > 0) parts.push(`误差约 ${Math.round(p.accuracy)} 米`)
  return parts.join(' · ')
}

function lateLabel(ms: number): string {
  return ms < 60 * MINUTE ? `${Math.floor(ms / MINUTE)} 分钟` : `${Math.floor(ms / (60 * MINUTE))} 小时`
}

/** 详情页的各行，按方案表格的顺序 */
export function detailRows(p: Position, now: Date): DetailRow[] {
  const rows: DetailRow[] = []
  const add = (label: string, value: string | null) => {
    if (value) rows.push({ label, value })
  }
  const noFix = withoutFix(p)

  if (p.unlocated) add('定位方式', '还没定到过位置（只报了在线）')
  else if (noFix) add('定位方式', '这次没有定位（只报了在线）')
  else if (isNetEstimate(p)) add('定位方式', '网络估算（屋里没有卫星，按 Wi-Fi/基站估的）')
  else if (p.provider !== undefined) add('定位方式', p.provider === 'gps' ? '卫星定位（GPS）' : lookup(PROVIDER, p.provider))
  if (!noFix && p.accuracy > 0) add('精度', `误差约 ${Math.round(p.accuracy)} 米`)

  if (p.net !== undefined) add('网络', lookup(NET, p.net))
  else if (p.wifiType !== undefined) add('网络', lookup(WIFI_TYPE, p.wifiType))

  const power: string[] = []
  if (p.charging) power.push('充电中')
  if (p.powerSave) power.push('省电模式开着')
  add('电源', power.join(' · '))

  if (p.steps !== undefined || p.bumps !== undefined) {
    const motion: string[] = []
    if (p.steps !== undefined && p.steps > 0) motion.push(`离上次上报走了 ${Math.round(p.steps)} 步`)
    if (p.bumps !== undefined && p.bumps > 0) motion.push('车上颠簸')
    add('运动', motion.length > 0 ? motion.join(' · ') : '离上次上报没走动')
  }

  if (p.state !== undefined) add('手机判断', lookup(STATE, p.state))

  // 补传按手机发出的时间（deviceTime）算：没坐标的心跳定位时间是旧的，但它是当场发的，不算补传
  // 0,0 占位的定位时间是 1980 年，不是真的定位时间
  const times = p.unlocated ? [] : [`定位于 ${clockLabel(p.fixTime, now, true)}`]
  if (p.serverTime) {
    times.push(`服务器收到 ${clockLabel(p.serverTime, now, true)}`)
    const late = p.serverTime.getTime() - p.deviceTime.getTime()
    if (late > LATE_MS) times.push(`补传，晚到 ${lateLabel(late)}`)
  }
  add('时间', times.join(' · '))
  return rows
}
