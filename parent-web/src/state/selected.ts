// 选中设备的「最近 24 小时」事件和今天的轨迹。
// - 每份数据记着是哪台设备的，读的时候核对设备号：换孩子、自动换到别的孩子、请求晚回来都不串，点已选中的孩子也不用清空；
// - 拉回来的和 WebSocket 推来的按 id 合并：重连补拉、刷新时，请求在途中推来的不丢；
// - 推来的事件只收 REST 查的那几类（告警、失联、到达/离开），别的（上线、离线、移动…）不算告警；
// - 读的时候按「现在」滚动：事件只留最近 24 小时，轨迹只留今天（页面开过午夜也对）；
// - 记着告警最后一次查成功是什么时候、后来有没有查失败（eventsAsOf）：推送不通又没查到时，告警页不能说「24 小时里没有事」。
import { LISTED_EVENTS, type Position, type SocketMessage, type TraccarEvent } from '../api/types'
import { timelineTime } from '../domain/presence'

export const DAY_MS = 86_400_000

/** 一台设备的一份数据；loaded 为 false 是还没拉回来、先收着推来的 */
type Tagged<T> = { deviceId: number; items: readonly T[]; loaded: boolean }

/** 一台设备的告警查询：最后一次查成功时查到哪一刻（null 是还没成功过）、之后那次查询的错误（成功了是 null） */
export type EventsSync = { deviceId: number; fetchedAt: Date | null; error: string | null }

export type SelectedState = { events: Tagged<TraccarEvent> | null; track: Tagged<Position> | null; eventsSync: EventsSync | null }

export const initialSelected: SelectedState = { events: null, track: null, eventsSync: null }

/** 告警这么久没核实（推送不通、轮询 30 秒一次加请求最多 15 秒，再留点余量）就说「只更新到…」 */
export const EVENTS_STALE_MS = 90_000

export type SelectedAction =
  /** 拉回来的一台设备最近 24 小时的事件；at 是查到哪一刻 */
  | { type: 'events'; deviceId: number; events: readonly TraccarEvent[]; at: Date }
  /** 查这台设备的事件失败了 */
  | { type: 'eventsFailed'; deviceId: number; error: string }
  /** 拉回来的一台设备今天的轨迹 */
  | { type: 'track'; deviceId: number; positions: readonly Position[] }
  /** WebSocket 推来的；selectedId 是现在选中的设备，trackOn 是轨迹开着没有 */
  | { type: 'socket'; selectedId: number | null; trackOn: boolean; message: SocketMessage }
  /** 关掉轨迹 */
  | { type: 'trackOff' }

const newestEventFirst = (a: TraccarEvent, b: TraccarEvent) => b.eventTime.getTime() - a.eventTime.getTime() || b.id - a.id
const oldestFixFirst = (a: Position, b: Position) => timelineTime(a) - timelineTime(b) || a.id - b.id

/** 按 id 合并：同一个 id 用新来的那份 */
function mergeById<T extends { id: number }>(old: readonly T[], incoming: readonly T[], order: (a: T, b: T) => number): T[] {
  const byId = new Map(old.map((x) => [x.id, x]))
  for (const x of incoming) byId.set(x.id, x)
  return [...byId.values()].sort(order)
}

/** 这台设备的一份数据合进来；原来是别的设备的就换掉 */
function put<T extends { id: number }>(
  prev: Tagged<T> | null,
  deviceId: number,
  incoming: readonly T[],
  loaded: boolean,
  order: (a: T, b: T) => number,
): Tagged<T> {
  const old = prev?.deviceId === deviceId ? prev : null
  return { deviceId, items: mergeById(old?.items ?? [], incoming, order), loaded: loaded || (old?.loaded ?? false) }
}

export function isListedEvent(e: Pick<TraccarEvent, 'type'>): boolean {
  return LISTED_EVENTS.includes(e.type)
}

export function selectedReducer(state: SelectedState, action: SelectedAction): SelectedState {
  switch (action.type) {
    case 'events':
      return {
        ...state,
        events: put(state.events, action.deviceId, action.events.filter(isListedEvent), true, newestEventFirst),
        eventsSync: { deviceId: action.deviceId, fetchedAt: action.at, error: null },
      }
    case 'eventsFailed': {
      const old = state.eventsSync?.deviceId === action.deviceId ? state.eventsSync.fetchedAt : null
      return { ...state, eventsSync: { deviceId: action.deviceId, fetchedAt: old, error: action.error } }
    }
    case 'track':
      return { ...state, track: put(state.track, action.deviceId, action.positions, true, oldestFixFirst) }
    case 'trackOff':
      return state.track === null ? state : { ...state, track: null }
    case 'socket': {
      const id = action.selectedId
      if (id === null) return state
      const events = action.message.events.filter((e) => e.deviceId === id && isListedEvent(e))
      const fixes = action.trackOn ? action.message.positions.filter((p) => p.deviceId === id) : []
      if (events.length === 0 && fixes.length === 0) return state
      return {
        ...state,
        events: events.length > 0 ? put(state.events, id, events, false, newestEventFirst) : state.events,
        track: fixes.length > 0 ? put(state.track, id, fixes, false, oldestFixFirst) : state.track,
      }
    }
  }
}

function startOfDay(now: Date): number {
  return new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
}

/** 这台设备最近 24 小时的事件，新的在前；还没拉回来（刚打开、刚换孩子）是 null */
export function eventsOf(state: SelectedState, deviceId: number | null, now: Date): TraccarEvent[] | null {
  const e = state.events
  if (!e || e.deviceId !== deviceId || !e.loaded) return null
  const from = now.getTime() - DAY_MS
  return e.items.filter((x) => x.eventTime.getTime() >= from)
}

/** 这台设备今天的轨迹（手机还没定到位置时的 0,0 占位不画）；没打开、还没拉回来是 null */
export function trackOf(state: SelectedState, deviceId: number | null, now: Date): Position[] | null {
  const t = state.track
  if (!t || t.deviceId !== deviceId || !t.loaded) return null
  const from = startOfDay(now)
  return t.items.filter((p) => !p.unlocated && timelineTime(p) >= from)
}

/** 推送连着没有（live），上次断开是什么时候（liveUntil）：推送通着的那段时间告警是推来的，不用查 */
export type Push = { live: boolean; liveUntil: Date | null }

/**
 * 这台设备的告警核实到哪一刻；还没查成功过是 null。最近一次查询失败：只到上次查成功那一刻（断线期间的告警可能没补上）；
 * 推送通着：就是现在；推送断了：查成功那一刻和推送断开那一刻里晚的那个
 */
export function eventsAsOf(state: SelectedState, deviceId: number | null, push: Push, now: Date): Date | null {
  const sync = state.eventsSync
  if (!sync || sync.deviceId !== deviceId || sync.fetchedAt === null) return null
  if (sync.error !== null) return sync.fetchedAt
  if (push.live) return now
  return push.liveUntil && push.liveUntil > sync.fetchedAt ? push.liveUntil : sync.fetchedAt
}

/** 这台设备最近一次查告警的错误；查成功了是 null */
export function eventsErrorOf(state: SelectedState, deviceId: number | null): string | null {
  return state.eventsSync?.deviceId === deviceId ? state.eventsSync.error : null
}

/** 告警核实得不够新：告警页和入口要写「只更新到…」，不能说没有事 */
export function eventsStale(asOf: Date | null, now: Date): boolean {
  return asOf === null || now.getTime() - asOf.getTime() > EVENTS_STALE_MS
}
