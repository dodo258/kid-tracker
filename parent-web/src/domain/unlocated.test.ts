// 没有位置的点（手机从没定到过位置，Traccar 存成 0,0、1980）和网络估算当的位置（上报端 v2 屋里没卫星时）。
// 2026-11-19 实测：三星新设备只报时间，圆点画到了非洲；P20 静止在屋里，只显示 12 小时前的位置。
import { describe, expect, it } from 'vitest'
import positionsJson from '../../fixtures/positions.json'
import { parsePosition, parsePositions } from '../api/parse'
import type { Position } from '../api/types'
import { detailRows } from './details'
import { estimateText, isNetEstimate, movesDot, steadyFixes } from './fixQuality'
import { laggingOf, recentEstimate } from './lagging'
import { placeOf } from './places'
import { compareReports, presenceOf } from './presence'
import { NO_POSITION, situationOf, whereLine } from './status'
import { addPositions, EMPTY_HISTORY } from './track'

const NOW = new Date('2026-11-19T08:42:00Z')
const iso = (minAgo: number) => new Date(NOW.getTime() - minAgo * 60_000).toISOString()

/** Traccar REST 原样的一条只报时间、从没定到过位置的上报（outdated 不进数据库，拉回来没有） */
function unlocatedRaw(id: number, minAgo: number, attributes: Record<string, unknown> = {}) {
  return {
    id,
    deviceId: 1,
    fixTime: '1980-01-06T00:00:00.000+00:00',
    deviceTime: iso(minAgo),
    serverTime: iso(minAgo),
    latitude: 0,
    longitude: 0,
    accuracy: 0,
    attributes: { batteryLevel: 92, state: 'still', net: 'wifi', ...attributes },
  }
}

function parsed(raw: unknown): Position {
  const p = parsePosition(raw)
  if (!p) throw new Error('解析失败')
  return p
}

describe('没有位置的点：不进轨迹、不算距离', () => {
  const a = parsed(unlocatedRaw(1, 6))
  const b = parsed(unlocatedRaw(2, 1))

  it('进上报（联系时间、这一轮状态要用），不进定位点和圆点那串', () => {
    const h = addPositions(EMPTY_HISTORY, [a, b])
    expect(h.reports.map((p) => p.id)).toEqual([1, 2])
    expect(h.fixes).toEqual([])
    expect(h.steady).toEqual([])
  })
  it('后来定到了真位置：只有真位置进定位点', () => {
    const real = parsed({ ...unlocatedRaw(3, 0), fixTime: iso(0), latitude: 30.27, longitude: 120.14, accuracy: 12, attributes: { provider: 'gps' } })
    const h = addPositions(addPositions(EMPTY_HISTORY, [a, b]), [real])
    expect(h.fixes.map((p) => p.id)).toEqual([3])
  })
  it('不算在不在常去地点里（0,0 不在任何圈里，服务器给的围栏也不信）', () => {
    expect(placeOf({ ...b, geofenceIds: [12] }, [{ id: 12, name: '家', area: 'CIRCLE (0 0, 500)' }])).toBeUndefined()
  })
  it('卡片第三行：还没有位置，手机还没定到；右边照常写最后联系', () => {
    expect(whereLine(b, null, undefined, false, NOW)).toEqual({ where: NO_POSITION, ago: '1 分钟前' })
    expect(NO_POSITION).toBe('还没有位置，手机还没定到')
  })
  it('报着移动中也不写「位置停在 X 年前」：就是还在定位', () => {
    const moving = parsed(unlocatedRaw(4, 1, { state: 'moving' }))
    const presence = presenceOf(moving, NOW)
    expect(presence.live).toBe('locating')
    expect(situationOf({ position: moving, presence, history: { reports: [moving], fixes: [] }, offlineSince: null, updating: false, now: NOW }).kind).toBe('locating')
  })
  it('旧版 App（没有 state）也不写「X 年前的位置」', () => {
    const legacy = parsed({ ...unlocatedRaw(5, 1), attributes: {} })
    const presence = presenceOf(legacy, NOW)
    expect(situationOf({ position: legacy, presence, history: { reports: [legacy], fixes: [] }, offlineSince: null, updating: false, now: NOW }).kind).toBe('legacyIdle')
  })
  it('补传上来的也不写「位置在 X 前」', () => {
    const late = parsed({ ...unlocatedRaw(6, 0), deviceTime: iso(20) })
    const presence = presenceOf(late, NOW)
    expect(situationOf({ position: late, presence, history: { reports: [late], fixes: [] }, offlineSince: null, updating: false, now: NOW }).kind).not.toBe('backfill')
  })
  it('详情页：不写 1980 年的定位时间', () => {
    const rows = detailRows(b, NOW)
    expect(rows.find((r) => r.label === '定位方式')?.value).toBe('还没定到过位置（只报了在线）')
    expect(rows.find((r) => r.label === '时间')?.value).not.toContain('定位于')
  })
})

describe('网络估算当的位置（上报端 v2：来源 network、accuracy 是误差、每条带 net）', () => {
  const HOME = { latitude: 30.27439, longitude: 120.140581 }
  const sat = parsed({ id: 10, deviceId: 1, fixTime: iso(600), latitude: HOME.latitude, longitude: HOME.longitude, accuracy: 12, attributes: { provider: 'gps', state: 'still', net: 'wifi' } })
  // 屋里没卫星，确认过的网络估算在 2.8 公里外
  const est = parsed({
    id: 11,
    deviceId: 1,
    fixTime: iso(1),
    latitude: HOME.latitude + 2800 / 111_320,
    longitude: HOME.longitude,
    accuracy: 86,
    address: '浙江省杭州市西湖区北山街道文三路 152 号浙江科贸大楼',
    attributes: { provider: 'network', state: 'still', net: 'wifi' },
  })

  it('认得出：来源 network 且带 net；旧版 App 的网络点（不带 net）、Wi-Fi 点不算', () => {
    expect(isNetEstimate(est)).toBe(true)
    expect(isNetEstimate({ ...est, net: undefined })).toBe(false)
    expect(isNetEstimate({ ...est, provider: 'wifi' })).toBe(false)
  })
  it('静止时也挪圆点（不当 Wi-Fi 慢漂）；旧版 App 的网络点静止时照旧不挪', () => {
    expect(movesDot(sat, est)).toBe(true)
    expect(movesDot(sat, { ...est, net: undefined })).toBe(false)
    expect(steadyFixes([sat, est]).at(-1)?.held).toBeUndefined()
  })
  it('估算还在原来的精度圈里（没挪地方）：不挪', () => {
    const near = { ...est, latitude: HOME.latitude + 20 / 111_320 }
    expect(movesDot(sat, near)).toBe(false)
  })
  it('卡片第三行：地址后面写「网络估算 · 误差约 86 米」；没有地址只写这句', () => {
    expect(estimateText(est)).toBe('网络估算 · 误差约 86 米')
    expect(whereLine(est, est, undefined, false, NOW).where).toBe('文三路 152 号浙江科贸大楼 · 网络估算 · 误差约 86 米')
    expect(whereLine(est, { ...est, address: undefined }, undefined, false, NOW).where).toBe('网络估算 · 误差约 86 米')
    expect(estimateText(sat)).toBeNull()
    // 最多两行，误差不被截掉；别的点照旧一行
    expect(whereLine(est, est, undefined, false, NOW).wrap).toBe(true)
    expect(whereLine(sat, sat, undefined, false, NOW).wrap).toBeUndefined()
  })
  it('详情页的定位方式写网络估算', () => {
    expect(detailRows(est, NOW).find((r) => r.label === '定位方式')?.value).toContain('网络估算')
  })
})

describe('截图假数据（fixtures/positions.json 的 nofix、nofixbare、stillfar、netmain，时钟 2026-11-16 08:42Z）', () => {
  const FIXTURE_NOW = new Date('2026-11-16T08:42:00Z')
  const kid = (name: keyof typeof positionsJson) => parsePositions(positionsJson[name]).filter((p) => p.deviceId === 1)
  const latestOf = (ps: Position[]) => ps.reduce((a, b) => (compareReports(b, a) > 0 ? b : a))

  it('nofix：没有位置，有网络估算（画淡色估算圈）；nofixbare 没有估算', () => {
    const [p] = kid('nofix')
    expect(p.unlocated).toBe(true)
    expect(recentEstimate(addPositions(EMPTY_HISTORY, [p]).reports, FIXTURE_NOW)?.netLat).toBeDefined()
    const [bare] = kid('nofixbare')
    expect(bare.unlocated).toBe(true)
    expect(recentEstimate([bare], FIXTURE_NOW)).toBeNull()
  })
  it('stillfar：最新一条没带估算，8 分钟前那条带了 2.8 公里外的，静止也提示没跟上', () => {
    const ps = kid('stillfar')
    const h = addPositions(EMPTY_HISTORY, ps)
    const latest = latestOf(ps)
    expect(latest.netLat).toBeUndefined()
    const est = recentEstimate(h.reports, FIXTURE_NOW)
    expect(est).not.toBeNull()
    const presence = presenceOf(latest, FIXTURE_NOW)
    const situation = situationOf({ position: latest, presence, history: h, offlineSince: null, updating: false, now: FIXTURE_NOW })
    expect(situation.kind).toBe('still')
    const lag = est && laggingOf(situation, est, latest)
    expect(lag?.distance).toBeGreaterThan(2700)
  })
  it('netmain：网络估算当主坐标，卡片写网络估算和误差', () => {
    const latest = latestOf(kid('netmain'))
    expect(isNetEstimate(latest)).toBe(true)
    expect(whereLine(latest, latest, undefined, false, FIXTURE_NOW).where).toContain('网络估算 · 误差约 86 米')
  })
})
