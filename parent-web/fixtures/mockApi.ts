// 截图用的假后端：`npm run dev:fixtures` 后打开 /m/?state=<场景>。
// JSON 是按 Traccar 6.17 接口原样的返回格式写的，走和正式环境同一套 parse 校验。
// 时钟固定在 2026-11-16 16:42（北京时间），这样「几分钟前」每次截图都一样；圆点动画用的时钟从这一刻起照常走。
// state=replay 是回放 P20 真实上报（fixtures/p20.json，见 replay.ts）。
import { amapLocation, parseInputTips, parsePlaceText, parseRegeo } from '../src/api/amap'
import { parseDevices, parseEvents, parseGeofence, parseGeofences, parsePositions, parseSocketMessage } from '../src/api/parse'
import { saveDeviceId } from '../src/api/storage'
import { ServerError, type Api, type Geofence, type Position } from '../src/api/types'
import type { InitialUi } from '../src/ui/App'
import amapInputTipsJson from './amap-inputtips.json'
import amapPlaceJson from './amap-place.json'
import amapRegeoJson from './amap-regeo.json'
import devicesJson from './devices.json'
import eventPositionsJson from './event-positions.json'
import eventsJson from './events.json'
import geofencesJson from './geofences.json'
import positionsJson from './positions.json'
import { replayBackend } from './replay'
import routeJson from './route.json'

const NOW = new Date('2026-11-16T08:42:00Z')

export type FixtureBackend = { api: Api; token: string | null; ui?: InitialUi; clock: () => number }

/**
 * devices：账号下几台设备（stale、lost、lowbattery 故意只有一台，看单设备时的样子）；data：用哪份位置和告警，默认同名；
 * places：有没有常去地点（geofences.json，按设备号分组）。名字以 -dark 结尾的，截图时按深色渲染
 */
type Scenario = {
  devices: number
  ui?: InitialUi
  loggedOut?: boolean
  data?: string
  places?: boolean
  /** 换设备名单（顺序 = 服务器返回的顺序）；select 是打开时选中哪台 */
  roster?: { id: number; name: string }[]
  select?: number
  /** 学校同时关联给大宝（删除时提醒「也用在大宝身上」） */
  sharedSchool?: boolean
  /** 最近 24 小时和常去地点一直不回来（看加载中） */
  pending?: boolean
  /**
   * 最近 30 分钟的点不用 route.json，换成：approach 是往行进方向开过来的一串卫星点（移动中画轨迹线），
   * wifiJitter 是屋里漂的几个 Wi-Fi 点（都在精度圈里，圆点不挪），stopped 是开过来以后停了 2 分多钟的卫星点（最新一个测速也是 0）
   */
  recent?: 'approach' | 'wifiJitter' | 'stopped'
  /** 截图前推来一批离线补传的点（window.fixtureBackfill()） */
  backfill?: boolean
  /**
   * 家长这边断网：打开时（时钟比 NOW 早 90 秒）拉到数据，WebSocket 一直没有首帧；截图前 window.fixtureGoOffline() 把时钟拨到 NOW、
   * 之后的请求都失败（服务器 502）
   */
  offline?: boolean
  /** 退出登录时服务器那一步失败（断网） */
  signOutFails?: boolean
  /** 查告警一直失败（服务器 502）：告警页写没查到、可以重试 */
  eventsFail?: boolean
  /** 选中的孩子（设备 1）手机在充电 */
  charging?: boolean
  /** 选中的孩子最新一条带了网络估算（netLat/netLon），在圆点东北约 3 公里外：卡片写「位置可能没跟上」，地图画估算圈 */
  netFar?: boolean
}

/** 名字长、孩子多：选中的「欧阳小宝贝的红米手机」排在最后，打开时切换器要把它滚到中间 */
const LONG_ROSTER = [
  { id: 3, name: '二宝的平板电脑' },
  { id: 4, name: '三宝' },
  { id: 2, name: '大宝的华为手机' },
  { id: 1, name: '欧阳小宝贝的红米手机' },
]

/**
 * 方案第 5 节的 8 种状态（深色模式用 dark 场景，截图时让浏览器按深色渲染），加两台设备同屏的 multi；
 * 常去地点方案：place（在学校，地图上有圈）、places（展开看到达离开和地点列表）、addplace（设为常去地点）、
 * pickplace（在地图上选位置），各有深色版；addplace-wide 看 1000 米范围时地图缩小
 */
export const SCENARIOS: Record<string, Scenario> = {
  normal: { devices: 2 },
  stale: { devices: 1 },
  lost: { devices: 1 },
  lowbattery: { devices: 1 },
  // 心跳取不到新位置：位置 25 分钟前，手机 1 分钟前还发过心跳
  heartbeat: { devices: 1 },
  track: { devices: 2, ui: { track: true } },
  alerts: { devices: 2, ui: { page: 'alerts' } },
  login: { devices: 1, loggedOut: true },
  dark: { devices: 2 },
  multi: { devices: 2, ui: { fitAll: true }, data: 'normal' },
  place: { devices: 2, places: true },
  'place-dark': { devices: 2, places: true, data: 'place' },
  places: { devices: 2, places: true, ui: { page: 'alerts' }, data: 'place' },
  'places-dark': { devices: 2, places: true, ui: { page: 'alerts' }, data: 'place' },
  // 常去地点二级页
  placelist: { devices: 2, places: true, ui: { page: 'places' }, data: 'place' },
  'placelist-dark': { devices: 2, places: true, ui: { page: 'places' }, data: 'place' },
  // 点圈打开的地点卡片（学校）；改范围预览（选了 500 米）
  placecard: { devices: 2, places: true, ui: { card: 12 }, data: 'place' },
  'placecard-dark': { devices: 2, places: true, ui: { card: 12 }, data: 'place' },
  placeradius: { devices: 2, places: true, ui: { card: 12, cardRadius: 500 }, data: 'place' },
  'placeradius-dark': { devices: 2, places: true, ui: { card: 12, cardRadius: 500 }, data: 'place' },
  addplace: { devices: 2, ui: { addPlace: true }, data: 'normal' },
  'addplace-dark': { devices: 2, ui: { addPlace: true }, data: 'normal' },
  // 选的范围大（1000 米）：地图自动缩小到能看全整个圈
  'addplace-wide': { devices: 2, ui: { addPlace: true, placeRadius: 1000 }, data: 'normal' },
  // 在地图上选位置：中间的图钉和跟着它的圈
  // 地图上有家和学校两个已存的圈（淡色），图钉下面有地址
  pickplace: { devices: 2, places: true, ui: { addPlace: true, picking: true }, data: 'normal' },
  'pickplace-dark': { devices: 2, places: true, ui: { addPlace: true, picking: true }, data: 'normal' },
  // 选点时搜「文三」，下面出 3 条提示
  picksearch: { devices: 2, places: true, ui: { addPlace: true, picking: true, search: '文三' }, data: 'normal' },
  'picksearch-dark': { devices: 2, places: true, ui: { addPlace: true, picking: true, search: '文三' }, data: 'normal' },
  // 压测修复（2026-11-16）：名字长、孩子多，在学校；孩子手机时间往后错了 20 分钟；切换后数据还没回来；学校两个孩子共用，删除时提醒
  longname: { devices: 4, roster: LONG_ROSTER, select: 1, places: true, data: 'place' },
  'longname-dark': { devices: 4, roster: LONG_ROSTER, select: 1, places: true, data: 'place' },
  future: { devices: 1 },
  loading: { devices: 2, pending: true, data: 'normal' },
  placeshared: { devices: 2, places: true, sharedSchool: true, ui: { card: 12 }, data: 'place' },
  // 移动中（12 km/h）：位置 20 秒前，状态卡写「实时」；位置 6 分钟前，写黄色「移动中，位置停在 6 分钟前」
  live: { devices: 2 },
  'live-dark': { devices: 2, data: 'live' },
  movingold: { devices: 2 },
  // 三种状态（新版 App 每个点带 state）：移动中实时带方向箭头、范围内活动、静止在线（位置 1 小时 20 分钟前没变）、10 分钟没上报的失联
  moving: { devices: 2 },
  'moving-dark': { devices: 2, data: 'moving' },
  zone: { devices: 2 },
  'zone-dark': { devices: 2, data: 'zone' },
  // 移动 Wi-Fi 被当成固定 Wi-Fi（方案 2026-11-19-moving-wifi 第 5 节）：还报在附近活动，网络估算已经在 3 公里外
  zonefar: { devices: 2, data: 'zone', netFar: true },
  'zonefar-dark': { devices: 2, data: 'zone', netFar: true },
  // 没有位置和网络估算当位置（2026-11-19）：三星新设备只报时间（Traccar 存成 0,0、1980），有估算画淡色圈、没估算只写还没有位置；
  // 静止在屋里、最新一条没带估算但 8 分钟前那条带了 2.8 公里外的；上报端新版把网络估算当主坐标报
  nofix: { devices: 2 },
  'nofix-dark': { devices: 2, data: 'nofix' },
  nofixbare: { devices: 2 },
  stillfar: { devices: 2 },
  'stillfar-dark': { devices: 2, data: 'stillfar' },
  netmain: { devices: 2 },
  'netmain-dark': { devices: 2, data: 'netmain' },
  still: { devices: 2 },
  'still-dark': { devices: 2, data: 'still' },
  stilllost: { devices: 1 },
  // 重写（2026-11-17）：移动中带最近 30 分钟轨迹线；屋里网络定位画精度圈、写大概位置；失联深色；离线补传快放中
  'moving-trail': { devices: 2, data: 'moving', recent: 'approach' },
  'moving-trail-dark': { devices: 2, data: 'moving', recent: 'approach' },
  stillwifi: { devices: 2, recent: 'wifiJitter' },
  'stillwifi-dark': { devices: 2, data: 'stillwifi', recent: 'wifiJitter' },
  'stilllost-dark': { devices: 1, data: 'stilllost' },
  backfill: { devices: 2, recent: 'approach', backfill: true },
  // 审计修复（2026-11-18）：家长这边断网（不显示孩子失联）、退出时服务器没退成、账号下没有设备（也能退出）
  parentoffline: { devices: 2, data: 'still', offline: true },
  'parentoffline-dark': { devices: 2, data: 'still', offline: true },
  signoutfailed: { devices: 2, data: 'still', signOutFails: true, ui: { page: 'settings' } },
  nodevice: { devices: 0, data: 'none' },
  'backfill-dark': { devices: 2, data: 'backfill', recent: 'approach', backfill: true },
  // 打磨（方案 2026-11-18-parent-web-polish）：手机还报移动中、卫星点停了 2 分多钟，网页自己说「刚停下」；
  // 详情页、设置页（告警页是 alerts，常去地点页是 placelist），各有深色版
  stopped: { devices: 2, data: 'moving', recent: 'stopped' },
  'stopped-dark': { devices: 2, data: 'moving', recent: 'stopped' },
  details: { devices: 2, data: 'moving', recent: 'approach', ui: { page: 'details' } },
  'details-dark': { devices: 2, data: 'moving', recent: 'approach', ui: { page: 'details' } },
  settings: { devices: 2, data: 'still', ui: { page: 'settings' } },
  'settings-dark': { devices: 2, data: 'still', ui: { page: 'settings' } },
  'alerts-dark': { devices: 2, data: 'alerts', ui: { page: 'alerts' } },
  // 审计修复（Codex 11-18 晚审 #1）：告警查不到时不说「24 小时里没有事」
  'alerts-failed': { devices: 2, data: 'alerts', eventsFail: true, ui: { page: 'alerts' } },
  'alerts-failed-dark': { devices: 2, data: 'alerts', eventsFail: true, ui: { page: 'alerts' } },
  // 电量和圆点的颜色（2026-11-19）：正常、充电、低电、失联，收起卡浅色深色（dark 场景的数据本身在充电，深色正常用 normal-dark）
  'normal-dark': { devices: 2, data: 'normal' },
  charging: { devices: 2, data: 'normal', charging: true },
  'charging-dark': { devices: 2, data: 'normal', charging: true },
  'lowbattery-dark': { devices: 1, data: 'lowbattery' },
  'lost-dark': { devices: 1, data: 'lost' },
  // 状态卡两档：移动中（卫星定位、移动网络、车上颠簸）和静止（只报了在线、连着 Wi-Fi），收起 / 展开，各有深色版
  ...sheetScenarios(),
}

function sheetScenarios(): Record<string, Scenario> {
  const out: Record<string, Scenario> = {}
  const kinds = { moving: { data: 'moving', recent: 'approach' }, still: { data: 'still' } } as const
  for (const [kind, base] of Object.entries(kinds)) {
    for (const snap of ['peek', 'open'] as const) {
      for (const dark of ['', '-dark']) out[`sheet-${kind}-${snap}${dark}`] = { devices: 2, ...base, ui: { snap } }
    }
  }
  return out
}

const M_PER_DEG = 111_320

/** 从 p 朝 headingDeg 走 meters 米 */
function walk(p: { latitude: number; longitude: number }, headingDeg: number, meters: number) {
  const h = (headingDeg * Math.PI) / 180
  return {
    latitude: p.latitude + (meters * Math.cos(h)) / M_PER_DEG,
    longitude: p.longitude + (meters * Math.sin(h)) / (M_PER_DEG * Math.cos((p.latitude * Math.PI) / 180)),
  }
}

/** Traccar 原样格式的一个点（速度是节） */
function raw(id: number, at: { latitude: number; longitude: number }, fixTime: Date, serverTime: Date, extra: { speedKmh: number; course: number; accuracy: number; state: string; provider: string }) {
  return {
    id,
    deviceId: 1,
    fixTime: fixTime.toISOString(),
    deviceTime: fixTime.toISOString(),
    serverTime: serverTime.toISOString(),
    latitude: at.latitude,
    longitude: at.longitude,
    speed: extra.speedKmh / 1.852,
    course: extra.course,
    accuracy: extra.accuracy,
    attributes: { batteryLevel: 64, state: extra.state, provider: extra.provider },
  }
}

/**
 * 往行进方向开过来的一串点：从 latest 往回倒推，每 10 秒一个、22 km/h，方向从 15° 慢慢转到 latest 的方向，
 * 最后一个就在 latest 前 10 秒
 */
function approachTrail(latest: Position, count = 16, before = 0): Position[] {
  const course = latest.course ?? 45
  const step = (22 / 3.6) * 10
  const out = []
  let at = { latitude: latest.latitude, longitude: latest.longitude }
  for (let i = 1; i <= count; i++) {
    const heading = course - Math.min(35, i * 3)
    at = walk(at, heading + 180, step)
    const t = new Date(latest.fixTime.getTime() - before - i * 10_000)
    out.push(raw(5000 + i, at, t, new Date(t.getTime() + 1500), { speedKmh: 22, course: heading, accuracy: 8, state: 'moving', provider: 'gps' }))
  }
  return parsePositions(out.reverse())
}

/** 开过来、在 latest 那里停了 2 分 20 秒：前面一串 22 km/h 的点，后面每 10 秒一个停着的卫星点（测速 0–0.5 km/h、来回差 1–3 米） */
function stoppedTrail(latest: Position): Position[] {
  const stop = 140_000
  const still = []
  for (let s = stop; s >= 10_000; s -= 10_000) {
    const t = new Date(latest.fixTime.getTime() - s)
    still.push(raw(5200 + s / 10_000, walk(latest, (s / 10_000) * 70, 1 + ((s / 10_000) % 3)), t, new Date(t.getTime() + 1500), { speedKmh: (s / 10_000) % 2 ? 0.5 : 0, course: latest.course ?? 45, accuracy: 5, state: 'moving', provider: 'gps' }))
  }
  return [...approachTrail(latest, 16, stop), ...parsePositions(still)]
}

/** 屋里漂的 Wi-Fi 点：都在 latest 的精度圈里（20–30 米），圆点不挪 */
function wifiJitter(latest: Position): Position[] {
  const drift = [[15, 25], [200, 20], [300, 28]]
  const out = drift.map(([heading, meters], i) => {
    const t = new Date(latest.fixTime.getTime() - (i + 1) * 5 * 60_000)
    return raw(5100 + i, walk(latest, heading, meters), t, new Date(t.getTime() + 1500), { speedKmh: 0, course: 0, accuracy: 70, state: 'still', provider: 'wifi' })
  })
  return parsePositions(out.reverse())
}

/** 离线 20 分钟里记下的点，网恢复后一起传上来（服务器收到时间都是现在）：往东北走约 1.6 公里，最后一个是 10 秒前 */
function backfillBatch(latest: Position, receivedAt: Date): unknown[] {
  const out = []
  let at = { latitude: latest.latitude, longitude: latest.longitude }
  for (let i = 1; i <= 40; i++) {
    const heading = 40 + Math.sin(i / 6) * 30
    at = walk(at, heading, 40)
    const t = new Date(latest.fixTime.getTime() + i * 30_000 - 10_000)
    out.push(raw(6000 + i, at, t, receivedAt, { speedKmh: 5, course: heading, accuracy: 8, state: 'moving', provider: 'gps' }))
  }
  return out
}

function pick(table: Record<string, unknown>, key: string): unknown {
  return Object.prototype.hasOwnProperty.call(table, key) ? table[key] : []
}

export function fixtureBackend(state: string): FixtureBackend {
  if (state === 'replay') return replayBackend(new URLSearchParams(location.search))
  // 圆点动画用的时钟：从 NOW 起照常走（「几分钟前」那些字仍按固定的 NOW 算）
  const t0 = performance.now()
  const clock = () => NOW.getTime() + (performance.now() - t0)
  const scenario = SCENARIOS[state] ?? SCENARIOS.normal
  const name = scenario.data ?? (state in SCENARIOS ? state : 'normal')
  const devices = scenario.roster ?? parseDevices(devicesJson).slice(0, scenario.devices)
  if (scenario.select) saveDeviceId(scenario.select)
  // 停下了：最新一个点测速也是 0；充电中；带了很远的网络估算
  const positions = parsePositions(pick(positionsJson, name)).map((p) => {
    if (p.deviceId !== 1) return p
    if (scenario.recent === 'stopped') return { ...p, speedKmh: 0 }
    if (scenario.netFar) {
      const est = walk(p, 40, 3000)
      return { ...p, netLat: est.latitude, netLon: est.longitude, netAcc: 600 }
    }
    return scenario.charging ? { ...p, charging: true } : p
  })
  const events = parseEvents(pick(eventsJson, name))
  const eventPositions = parsePositions(eventPositionsJson)
  const kid = positions.find((p) => p.deviceId === 1)
  const trails = { approach: approachTrail, wifiJitter, stopped: stoppedTrail }
  const recent = kid && scenario.recent ? trails[scenario.recent](kid) : null
  const route = recent ?? parsePositions(routeJson)
  // 常去地点：存在内存里，添加、改、删除在这次打开的页面里有效
  const places = new Map<number, Geofence[]>()
  if (scenario.places) {
    for (const [deviceId, list] of Object.entries(geofencesJson)) places.set(Number(deviceId), parseGeofences(list))
  }
  if (scenario.sharedSchool) {
    const school = places.get(1)?.find((p) => p.id === 12)
    if (school) places.set(2, [...(places.get(2) ?? []), school])
  }
  const never = <T,>() => new Promise<T>(() => {})
  let nextPlaceId = 100
  let shift = scenario.offline ? 90_000 : 0
  let down = false
  const net = async <T,>(value: T): Promise<T> => {
    if (down) throw new ServerError(502)
    return value
  }
  if (scenario.offline) {
    Object.assign(window, {
      fixtureGoOffline: () => {
        shift = 0
        down = true
      },
    })
  }

  const api: Api = {
    login: async () => 'ok',
    createToken: async () => 'fixture-token',
    openSession: () => net(undefined),
    endSession: async () => {
      if (scenario.signOutFails) throw new TypeError('Failed to fetch')
    },
    revokeToken: async () => {},
    checkAuth: async () => {},
    devices: () => net(devices),
    latestPositions: () => net(positions),
    positionsById: async (_t, ids) => eventPositions.filter((p) => ids.includes(p.id)),
    // 和真服务器一样：轨迹里没有比这台设备最新位置还新的点（route.json 各场景共用，有的场景最新位置更早）
    route: async (_t, deviceId, from, to) => {
      const latest = positions.find((p) => p.deviceId === deviceId)?.fixTime ?? to
      return route.filter((p) => p.deviceId === deviceId && p.fixTime >= from && p.fixTime <= to && p.fixTime <= latest)
    },
    events: async (_t, deviceId, from, to) =>
      scenario.pending ? never() : scenario.eventsFail ? Promise.reject(new ServerError(502)) : net(events.filter((e) => e.deviceId === deviceId && e.eventTime >= from && e.eventTime <= to)),
    geofences: async (_t, deviceId) => (scenario.pending ? never() : [...(places.get(deviceId) ?? [])]),
    addGeofence: async (_t, deviceId, name, area) => {
      const place = { id: nextPlaceId++, name, area }
      // scripts/gestures.mjs 从控制台读存进去的 CIRCLE，核对坐标转换
      console.info('fixture:addGeofence', area)
      places.set(deviceId, [...(places.get(deviceId) ?? []), place])
      return place
    },
    updateGeofence: async (_t, id, name, area) => {
      // 按 Traccar 的样子：整份 PUT 回来，原样返回
      const place = parseGeofence({ id, name, area })
      if (!place) throw new Error('格式不对')
      console.info('fixture:updateGeofence', JSON.stringify({ id, name, area }))
      for (const [deviceId, list] of places) places.set(deviceId, list.map((p) => (p.id === id ? place : p)))
      return place
    },
    deleteGeofence: async (_t, id) => {
      console.info('fixture:deleteGeofence', String(id))
      for (const [deviceId, list] of places) places.set(deviceId, list.filter((p) => p.id !== id))
    },
    unlinkGeofence: async (_t, deviceId, geofenceId) => {
      console.info('fixture:unlinkGeofence', JSON.stringify({ deviceId, geofenceId }))
      places.set(deviceId, (places.get(deviceId) ?? []).filter((p) => p.id !== geofenceId))
    },
    // 高德：不管搜什么都回同样的 3 条提示、同一个地址；regeo 收到的坐标打在控制台，手势测试核对是不是 GCJ-02
    amapTips: async () => parseInputTips(amapInputTipsJson),
    amapSearch: async () => parsePlaceText(amapPlaceJson),
    amapRegeo: async (_t, gcj) => {
      console.info('fixture:amapRegeo', amapLocation(gcj))
      return parseRegeo(amapRegeoJson)
    },
    // 手势测试从控制台推一条 WebSocket 消息：window.fixturePush({ positions: [...] })（Traccar 原样格式）；
    // backfill 场景截图前调 window.fixtureBackfill() 推来一批离线补传的点
    subscribe: (onMessage) => {
      Object.assign(window, {
        fixturePush: (v: unknown) => onMessage(parseSocketMessage(v)),
        fixtureBackfill: () => kid && onMessage(parseSocketMessage({ positions: backfillBatch(kid, NOW) })),
      })
      // 和 Traccar 一样，连上先推一帧最新位置（收到首帧页面才算实时）；家长断网的场景一直收不到
      const first = scenario.offline ? undefined : setTimeout(() => onMessage({ devices: [], positions, events: [] }), 0)
      return () => {
        clearTimeout(first)
        Object.assign(window, { fixturePush: undefined, fixtureBackfill: undefined })
      }
    },
    now: () => new Date(NOW.getTime() - shift),
  }
  return { api, token: scenario.loggedOut ? null : 'fixture-token', ui: scenario.ui, clock }
}
