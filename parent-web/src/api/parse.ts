// 把接口返回的 JSON（unknown）校验成本页的类型；字段不对的条目丢掉，不让整页崩。
//
// 一条位置分三块读，每块只管一件事（2026-11-19 重写，原因见 DECISIONS.md 同日「parse 重写」）：
// - 骨架 readCore：编号、设备、三个时间、坐标、速度精度方向、地址围栏。缺编号、设备、定位时间、坐标的整条丢掉。
// - 坐标靠不靠得住 readCoordinateStatus：Traccar 在手机没给坐标时会自己填坐标，这里统一认出来，别处不再各猜各的：
//   · 手机这次只报了时间、Traccar 有上一个点：沿用上一个点的坐标和定位时间，推送时标 outdated（这个字段不进数据库，REST 拉回来没有），
//     页面另按「上报比定位晚 1 分钟以上」认（domain/details.withoutFix）；
//   · 手机从没定到过位置、Traccar 没有上一个点：坐标填 0,0、定位时间填 1980-01-06（BaseProtocolDecoder.getLastLocation 的 GPS 纪元），
//     REST 和推送都一样，所以只看坐标和时间认（纬度经度都是 0，或定位时间早于 2000 年）→ unlocated。
// - App 附加字段 readAppAttributes：App 上报时写进 attributes 的那些（和 app-v2 HttpUploader 的参数名一一对应），类型不对的当没有。
import type { Device, Geofence, Position, ReportState, SocketMessage, TraccarEvent } from './types'

type Obj = Record<string, unknown>

const KNOTS_TO_KMH = 1.852
/** 定位时间早于这一刻的不是真定位：Traccar 给从没定到过位置的上报填 1980-01-06（GPS 纪元） */
const REAL_FIX_SINCE = Date.UTC(2000, 0, 1)

// —— 读单个值：类型不对一律 undefined ——

function isObj(v: unknown): v is Obj {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}

function num(v: unknown): number | undefined {
  return typeof v === 'number' && Number.isFinite(v) ? v : undefined
}

function str(v: unknown): string | undefined {
  return typeof v === 'string' ? v : undefined
}

function bool(v: unknown): boolean | undefined {
  return typeof v === 'boolean' ? v : undefined
}

/** 只认 true：没有、false、别的类型都当没标 */
function flag(v: unknown): true | undefined {
  return v === true ? true : undefined
}

function date(v: unknown): Date | undefined {
  if (typeof v !== 'string') return undefined
  const d = new Date(v)
  return Number.isNaN(d.getTime()) ? undefined : d
}

function reportState(v: unknown): ReportState | undefined {
  return v === 'moving' || v === 'zone' || v === 'still' ? v : undefined
}

function list<T>(v: unknown, one: (x: unknown) => T | undefined): T[] {
  if (!Array.isArray(v)) return []
  const out: T[] = []
  for (const x of v) {
    const r = one(x)
    if (r !== undefined) out.push(r)
  }
  return out
}

// —— 位置的三块 ——

type Core = Pick<
  Position,
  'id' | 'deviceId' | 'fixTime' | 'deviceTime' | 'serverTime' | 'latitude' | 'longitude' | 'speedKmh' | 'accuracy' | 'course' | 'address' | 'geofenceIds'
>

/** 骨架：缺编号、设备、定位时间、坐标返回 undefined；手机时间没有时按定位时间，速度从节换成 km/h，地址去掉首尾空白、空的当没有 */
function readCore(v: Obj): Core | undefined {
  const id = num(v.id)
  const deviceId = num(v.deviceId)
  const fixTime = date(v.fixTime)
  const latitude = num(v.latitude)
  const longitude = num(v.longitude)
  if (id === undefined || deviceId === undefined || !fixTime || latitude === undefined || longitude === undefined) return undefined
  return {
    id,
    deviceId,
    fixTime,
    deviceTime: date(v.deviceTime) ?? fixTime,
    serverTime: date(v.serverTime),
    latitude,
    longitude,
    speedKmh: (num(v.speed) ?? 0) * KNOTS_TO_KMH,
    accuracy: num(v.accuracy) ?? 0,
    course: num(v.course),
    address: str(v.address)?.trim() || undefined,
    geofenceIds: list(v.geofenceIds, num),
  }
}

/** 坐标靠不靠得住（见文件开头）：outdated 照推送原样带上；0,0 或 2000 年以前的定位时间是没有位置 */
function readCoordinateStatus(v: Obj, core: Core): Pick<Position, 'outdated' | 'unlocated'> {
  const placeholder = (core.latitude === 0 && core.longitude === 0) || core.fixTime.getTime() < REAL_FIX_SINCE
  return { outdated: flag(v.outdated), unlocated: placeholder ? true : undefined }
}

type AppAttributes = Pick<
  Position,
  | 'batteryLevel'
  | 'charging'
  | 'alarm'
  | 'state'
  | 'provider'
  | 'steps'
  | 'bumps'
  | 'net'
  | 'wifiType'
  | 'powerSave'
  | 'locked'
  | 'netLat'
  | 'netLon'
  | 'netAcc'
>

/** App 写进 attributes 的字段（Traccar OsmAnd 协议把不认识的参数原样存进 attributes）；键名是上报参数名，charge → charging */
function readAppAttributes(a: Obj): AppAttributes {
  return {
    batteryLevel: num(a.batteryLevel),
    charging: bool(a.charge),
    alarm: str(a.alarm),
    state: reportState(a.state),
    provider: str(a.provider),
    steps: num(a.steps),
    bumps: num(a.bumps),
    net: str(a.net),
    wifiType: str(a.wifiType),
    powerSave: bool(a.powerSave),
    locked: flag(a.locked),
    netLat: num(a.netLat),
    netLon: num(a.netLon),
    netAcc: num(a.netAcc),
  }
}

export function parsePosition(v: unknown): Position | undefined {
  if (!isObj(v)) return undefined
  const core = readCore(v)
  if (!core) return undefined
  return { ...core, ...readCoordinateStatus(v, core), ...readAppAttributes(isObj(v.attributes) ? v.attributes : {}) }
}

// —— 设备、事件、常去地点 ——

export function parseDevice(v: unknown): Device | undefined {
  if (!isObj(v)) return undefined
  const id = num(v.id)
  const name = str(v.name)
  if (id === undefined || name === undefined) return undefined
  return { id, name }
}

export function parseEvent(v: unknown): TraccarEvent | undefined {
  if (!isObj(v)) return undefined
  const id = num(v.id)
  const type = str(v.type)
  const eventTime = date(v.eventTime)
  const deviceId = num(v.deviceId)
  if (id === undefined || type === undefined || !eventTime || deviceId === undefined) return undefined
  const attrs = isObj(v.attributes) ? v.attributes : {}
  return {
    id,
    type,
    eventTime,
    deviceId,
    positionId: num(v.positionId) ?? 0,
    geofenceId: num(v.geofenceId) ?? 0,
    alarm: str(attrs.alarm),
  }
}

export function parseGeofence(v: unknown): Geofence | undefined {
  if (!isObj(v)) return undefined
  const id = num(v.id)
  const name = str(v.name)
  const area = str(v.area)
  if (id === undefined || name === undefined || area === undefined) return undefined
  return { id, name, area }
}

export const parseDevices = (v: unknown) => list(v, parseDevice)
export const parsePositions = (v: unknown) => list(v, parsePosition)
export const parseEvents = (v: unknown) => list(v, parseEvent)
export const parseGeofences = (v: unknown) => list(v, parseGeofence)

/** /api/socket 推来的消息：{devices}、{positions}、{events} 之一，也可能是空对象（心跳） */
export function parseSocketMessage(v: unknown): SocketMessage {
  const o = isObj(v) ? v : {}
  return { devices: parseDevices(o.devices), positions: parsePositions(o.positions), events: parseEvents(o.events) }
}
