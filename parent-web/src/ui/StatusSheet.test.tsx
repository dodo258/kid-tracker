import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import type { Geofence, Position, Snap, TraccarEvent } from '../api/types'
import type { Presence } from '../domain/presence'
import type { History } from '../domain/track'
import { StatusSheet } from './StatusSheet'

const NOW = new Date('2026-11-16T08:42:00Z')
const ago = (m: number) => new Date(NOW.getTime() - m * 60_000)
const position = (patch: Partial<Position> = {}): Position => ({
  id: 1,
  deviceId: 1,
  fixTime: ago(0.3),
  deviceTime: ago(0.3),
  serverTime: ago(0.3),
  latitude: 30.27,
  longitude: 120.14,
  speedKmh: 0,
  accuracy: 8,
  provider: 'gps',
  address: '浙江省杭州市西湖区西溪街道教工路 18 号',
  batteryLevel: 64,
  geofenceIds: [],
  ...patch,
})
const SCHOOL: Geofence = { id: 12, name: '学校', area: 'CIRCLE (30.27 120.14, 200)' }
const event = (patch: Partial<TraccarEvent>): TraccarEvent => ({ id: 1, type: 'alarm', eventTime: ago(20), deviceId: 1, positionId: 0, geofenceId: 0, ...patch })

type Opts = {
  p?: Position | null
  presence?: Presence
  history?: Pick<History, 'reports' | 'fixes'>
  events?: TraccarEvent[] | null
  places?: Geofence[] | null
  place?: Geofence
  name?: string
  snap?: Snap
  offlineSince?: Date | null
  updating?: boolean
  error?: string | null
  lagging?: string | null
}
const render = ({
  p = position({ state: 'still' }),
  presence = { freshness: 'fresh', live: null },
  history = { reports: [], fixes: [] },
  events = [],
  places = [],
  place,
  name = '小宝',
  snap = 'peek',
  offlineSince = null,
  updating = false,
  error = null,
  lagging = null,
}: Opts = {}) =>
  renderToStaticMarkup(
    <StatusSheet
      name={name}
      position={p}
      fix={p}
      lagging={lagging}
      place={place}
      places={places}
      presence={presence}
      history={history}
      offlineSince={offlineSince}
      updating={updating}
      now={NOW}
      events={events}
      eventsAsOf={NOW}
      eventPositions={new Map()}
      error={error}
      trackOn={false}
      snap={snap}
      onSnap={() => {}}
      onToggleTrack={() => {}}
      onAddPlace={() => {}}
      onOpen={() => {}}
      onReconnect={() => {}}
    />,
  )

/** 状态句那一段（卡片里第一个 <p>，模拟器回放也按它读） */
const statusOf = (html: string) => /<p[^>]*>(.*?)<\/p>/.exec(html)?.[1].replace(/<[^>]+>/g, '') ?? ''

describe('收起时三行：名字和电量、状态句、在哪和多久前', () => {
  it('静止在常去地点：「在学校 · 静止」，下一行地址和「刚刚」', () => {
    const html = render({ place: SCHOOL })
    expect(statusOf(html)).toBe('在学校 · 静止')
    expect(html).toContain('教工路 18 号')
    expect(html).toContain('刚刚')
    expect(html).toContain('64%')
  })
  it('状态句是全页最大的字（状态 22 级），前缀小圆点安色', () => {
    const html = render()
    expect(html).toMatch(/<p class="[^"]*text-status[^"]*">/)
    expect(html).toContain('bg-an')
  })
  it('实时在路上：速度方向，前缀圆点呼吸', () => {
    const html = render({ p: position({ state: 'moving', speedKmh: 22, course: 45 }), presence: { freshness: 'fresh', live: 'live' } })
    expect(statusOf(html)).toBe('在路上 · 22 km/h 向东北')
    expect(html).toContain('live-dot')
  })
  it('手机还报移动中、卫星点停了 2 分钟：网页自己写「刚停下 · 2 分钟」', () => {
    const fixes = [0, 30, 60, 90, 130].map((s, i) => position({ id: i, state: 'moving', speedKmh: 0, fixTime: new Date(ago(2.5).getTime() + s * 1000) }))
    const html = render({ p: fixes[4], presence: { freshness: 'fresh', live: 'live' }, history: { reports: fixes, fixes } })
    expect(statusOf(html)).toBe('刚停下 · 2 分钟')
    expect(html).not.toContain('live-dot')
  })
  it('失联：状态句警色，在哪写「最后在 …」，告警入口写最近一次告警', () => {
    const html = render({
      p: position({ state: 'still', deviceTime: ago(18), serverTime: ago(18), fixTime: ago(40) }),
      presence: { freshness: 'lost', live: null },
      events: [event({ alarm: 'powerOff' })],
    })
    expect(statusOf(html)).toBe('已失联 18 分钟')
    expect(html).toMatch(/<p class="[^"]*text-jing/)
    expect(html).toContain('最后在 教工路 18 号')
    expect(html).toMatch(/text-jing">16:22 /)
  })
  it('还没有电量就不显示电池', () => {
    expect(render({ p: position({ state: 'still', batteryLevel: undefined }) })).not.toContain('data-battery')
  })
  it('名字太长只截名字，不和状态挤在一行', () => {
    const html = render({ name: '欧阳小宝贝的红米手机', place: SCHOOL })
    expect(html).toMatch(/<h1 class="[^"]*truncate[^"]*">欧阳小宝贝的红米手机<\/h1>/)
  })
  it('还没有位置', () => {
    expect(render({ p: null })).toContain('还没有收到位置')
  })
  it('刚回到前台还在更新：先写「正在更新…」，不写多久前', () => {
    const html = render({ p: position({ state: 'still', deviceTime: ago(40), serverTime: ago(40) }), presence: { freshness: 'lost', live: null }, updating: true })
    expect(statusOf(html)).toBe('正在更新…')
    expect(html).not.toContain('分钟前')
  })
  it('家长这边断网：说家长这边连不上，下面「最后看到 16:40 · 点这里重连」', () => {
    const html = render({ offlineSince: ago(2), error: '暂时连不上服务器' })
    expect(statusOf(html)).toBe('家长这边连不上服务器')
    expect(html).toContain('最后看到 16:40 · 点这里重连')
  })
  it('位置可能没跟上：第三行换成提示（最多两行，不截掉地址），状态句照旧', () => {
    const hint = '位置可能没跟上，大概在 文三路 152 号浙江科贸大楼附近'
    const html = render({ p: position({ state: 'zone' }), lagging: hint })
    expect(statusOf(html)).toBe('在附近活动')
    expect(html).toContain(hint)
    expect(html).toContain('line-clamp-2')
    expect(html).not.toContain('教工路 18 号')
  })
})

describe('展开：两个文字按钮、四个入口，卡片里不再有导航、刷新、退出登录', () => {
  it('两个按钮和四个入口行', () => {
    const html = render({ snap: 'open', events: [event({}), event({ id: 2, type: 'geofenceEnter' })], places: [SCHOOL] })
    for (const text of ['今天的轨迹', '设为常去地点', '告警', '24 小时 2 条', '常去地点', '1 个', '这次定位的详情', '设置']) expect(html).toContain(text)
    for (const text of ['导航', '定位到孩子', '退出登录', '详细信息']) expect(html).not.toContain(text)
  })
  it('收起时展开那块不能点（inert），展开后可以', () => {
    expect(render()).toContain('inert=""')
    expect(render({ snap: 'open' })).not.toContain('inert=""')
  })
  it('切换孩子后数据还没回来：写正在加载，不先显示 0', () => {
    const html = render({ snap: 'open', events: null, places: null })
    expect(html).toContain('正在加载…')
    expect(html).not.toContain('24 小时没有')
    expect(html).not.toContain('0 个')
  })
  it('没有位置时没有「这次定位的详情」，「设为常去地点」点不了', () => {
    const html = render({ p: null, snap: 'open' })
    expect(html).not.toContain('这次定位的详情')
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*>设为常去地点/)
  })
})

/** 右上角电量那一段：颜色档、类名、电池里填了多宽、数字 */
const batteryOf = (html: string) => {
  const m = /<span data-battery="(\w+)" class="([^"]*)">(.*?)<\/span>/.exec(html)
  if (!m) return null
  return { tone: m[1], cls: m[2], fill: Number(/data-fill="[^"]*"[^>]*width="([\d.]+)"/.exec(m[3])?.[1]), text: m[3].replace(/<[^>]+>/g, ''), bolt: m[3].includes('aria-label="充电中"') }
}

describe('电量：电池图标加数字，平时灰、充电绿、≤20% 橙加粗', () => {
  it('平时：图标和数字都灰，电池按电量填充', () => {
    const b = batteryOf(render({ p: position({ state: 'still', batteryLevel: 64 }) }))
    expect(b).toMatchObject({ tone: 'calm', text: '64%', bolt: false })
    expect(b?.cls).toContain('text-mute')
    expect(b?.cls).not.toContain('font-semibold')
    expect(b?.fill).toBeCloseTo(16 * 0.64)
  })
  it('充电：电池和闪电变安绿，不加粗', () => {
    const b = batteryOf(render({ p: position({ state: 'still', batteryLevel: 64, charging: true }) }))
    expect(b).toMatchObject({ tone: 'an', text: '64%', bolt: true })
    expect(b?.cls).toContain('text-an')
    expect(b?.cls).not.toContain('font-semibold')
  })
  it('低电：20% 就变警橙加粗，21% 还是灰', () => {
    const low = batteryOf(render({ p: position({ state: 'still', batteryLevel: 20 }) }))
    expect(low).toMatchObject({ tone: 'jing', text: '20%' })
    expect(low?.cls).toContain('text-jing')
    expect(low?.cls).toContain('font-semibold')
    expect(batteryOf(render({ p: position({ state: 'still', batteryLevel: 21 }) }))?.cls).toContain('text-mute')
  })
  it('低电又在充电：优先绿', () => {
    const b = batteryOf(render({ p: position({ state: 'still', batteryLevel: 12, charging: true }) }))
    expect(b?.tone).toBe('an')
    expect(b?.cls).toContain('text-an')
  })
})

/** 状态句前的小圆点的类名 */
const dotOf = (html: string) => /<p[^>]*><span class="([^"]*rounded-full[^"]*)"/.exec(html)?.[1] ?? ''

describe('状态句前的小圆点：在路上实时安绿呼吸，刚停下和静止安绿不呼吸，失联警橙，手机时间不对灰', () => {
  it('在路上、实时：安绿、呼吸', () => {
    const dot = dotOf(render({ p: position({ state: 'moving', speedKmh: 22, course: 45 }), presence: { freshness: 'fresh', live: 'live' } }))
    expect(dot).toContain('bg-an')
    expect(dot).toContain('live-dot')
  })
  it('刚停下：安绿、不呼吸', () => {
    const fixes = [0, 30, 60, 90, 130].map((s, i) => position({ id: i, state: 'moving', speedKmh: 0, fixTime: new Date(ago(2.5).getTime() + s * 1000) }))
    const html = render({ p: fixes[4], presence: { freshness: 'fresh', live: 'live' }, history: { reports: fixes, fixes } })
    expect(statusOf(html)).toBe('刚停下 · 2 分钟')
    expect(dotOf(html)).toContain('bg-an')
    expect(dotOf(html)).not.toContain('live-dot')
  })
  it('静止：安绿、不呼吸', () => {
    const dot = dotOf(render({ place: SCHOOL }))
    expect(dot).toContain('bg-an')
    expect(dot).not.toContain('live-dot')
  })
  it('失联：警橙', () => {
    const html = render({ p: position({ state: 'still', deviceTime: ago(18), serverTime: ago(18), fixTime: ago(40) }), presence: { freshness: 'lost', live: null } })
    expect(dotOf(html)).toContain('bg-jing')
    expect(dotOf(html)).not.toContain('live-dot')
  })
  it('手机时间不对：灰', () => {
    const html = render({ p: position({ state: 'still', fixTime: new Date(NOW.getTime() + 20 * 60_000) }) })
    expect(statusOf(html)).toBe('手机时间不对')
    expect(dotOf(html)).toContain('bg-mute')
  })
})
