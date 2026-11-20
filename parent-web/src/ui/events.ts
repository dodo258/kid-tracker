// 一条事件的文字：「低电量（还剩 12%）」「到达 学校」，事件位置有地址的带上地址（domain/alarms 的 eventText）
import type { Geofence, Position, TraccarEvent } from '../api/types'
import { eventText } from '../domain/alarms'

export function describeEvent(e: TraccarEvent, positions: Map<number, Position>, places: Geofence[], withAddress = true): string {
  const p = positions.get(e.positionId)
  return eventText(e.type, e.alarm, p?.batteryLevel, {
    address: withAddress ? p?.address : undefined,
    placeName: places.find((g) => g.id === e.geofenceId)?.name,
  })
}
