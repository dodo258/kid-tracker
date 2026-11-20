// 设备列表、各设备最新一条上报、各设备最近 30 分钟的点（domain/track）。拉取成功或 WebSocket 推来设备/位置，都说明和服务器通了：
// 置 loaded、清 error。首拉失败后只靠 WebSocket 恢复时，设备列表可能还是空的，由 hasUnknownDevices 告诉调用方补拉一次设备。
import type { Device, Position, SocketMessage } from '../api/types'
import { compareReports } from '../domain/presence'
import { addPositions, EMPTY_HISTORY, type History } from '../domain/track'

export type LatestState = {
  loaded: boolean
  devices: Device[]
  /** 各设备最新一条上报（含不带坐标的呼吸、状态点：Traccar 给它们沿用上一个坐标和定位时间） */
  positions: Map<number, Position>
  /** 各设备最近 30 分钟的上报和定位点 */
  history: Map<number, History>
  error: string | null
}

export type LatestAction =
  | { type: 'fetched'; devices: Device[]; positions: Position[] }
  | { type: 'socket'; message: SocketMessage }
  /** 补拉的一台设备最近 30 分钟的点：进 history，比最新位置新的（Traccar 没推的校时后的点、断线期间的点）也换掉最新位置 */
  | { type: 'route'; deviceId: number; positions: Position[] }
  | { type: 'failed'; error: string }

export const initialLatest: LatestState = { loaded: false, devices: [], positions: new Map(), history: new Map(), error: null }

/**
 * 新的上报换掉旧的（compareReports）：离线补传的旧点不当成最新；手机时间快的超前点按服务器收到的时间排，校时以后的正常点能换掉它
 *（Traccar 自己只按定位时间换最新位置、也不推，这些点靠补拉的 30 分钟轨迹进来）
 */
function mergePositions(prev: Map<number, Position>, incoming: Position[]): Map<number, Position> {
  if (incoming.length === 0) return prev
  let next = prev
  for (const p of incoming) {
    const old = next.get(p.deviceId)
    if (old && compareReports(p, old) <= 0) continue
    if (next === prev) next = new Map(prev)
    next.set(p.deviceId, p)
  }
  return next
}

function mergeHistory(prev: Map<number, History>, incoming: Position[]): Map<number, History> {
  if (incoming.length === 0) return prev
  const byDevice = new Map<number, Position[]>()
  for (const p of incoming) byDevice.set(p.deviceId, [...(byDevice.get(p.deviceId) ?? []), p])
  let next = prev
  for (const [deviceId, list] of byDevice) {
    const old = prev.get(deviceId) ?? EMPTY_HISTORY
    const h = addPositions(old, list)
    if (h === old) continue
    if (next === prev) next = new Map(prev)
    next.set(deviceId, h)
  }
  return next
}

function mergeDevices(prev: Device[], incoming: Device[]): Device[] {
  if (incoming.length === 0) return prev
  const updated = prev.map((d) => incoming.find((n) => n.id === d.id) ?? d)
  const added = incoming.filter((n) => !prev.some((d) => d.id === n.id))
  return [...updated, ...added]
}

export function latestReducer(state: LatestState, action: LatestAction): LatestState {
  switch (action.type) {
    case 'fetched':
      return {
        loaded: true,
        devices: action.devices,
        positions: mergePositions(state.positions, action.positions),
        history: mergeHistory(state.history, action.positions),
        error: null,
      }
    case 'socket': {
      const { devices, positions } = action.message
      if (devices.length === 0 && positions.length === 0) return state
      return {
        loaded: true,
        devices: mergeDevices(state.devices, devices),
        positions: mergePositions(state.positions, positions),
        history: mergeHistory(state.history, positions),
        error: null,
      }
    }
    case 'route': {
      const mine = action.positions.filter((p) => p.deviceId === action.deviceId)
      const history = mergeHistory(state.history, mine)
      const positions = mergePositions(state.positions, mine)
      return history === state.history && positions === state.positions ? state : { ...state, history, positions }
    }
    case 'failed':
      return { ...state, error: action.error }
  }
}

/** 有位置但设备列表里没有这台（名字不知道），要补拉一次 /api/devices */
export function hasUnknownDevices(state: LatestState): boolean {
  for (const id of state.positions.keys()) {
    if (!state.devices.some((d) => d.id === id)) return true
  }
  return false
}
