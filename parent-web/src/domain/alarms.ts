// 告警、失联和到达/离开常去地点事件的中文说明。alarm 值是手机 App 报的（见 docs/server-setup.md「告警」一节），
// Traccar 同一个点有多个告警时用逗号连起来（Position.addAlarm）。
import { nearText } from './places'

const ALARM_TEXT: Record<string, string> = {
  lowBattery: '电量低',
  powerOff: '关机',
  general: '定位被关掉或权限被收回',
  sos: '发出求救',
  tampering: '有人想卸载或停用定位',
  trackingOff: '追踪被关闭',
}

const EVENT_TEXT: Record<string, string> = {
  deviceUnknown: '失联，服务器收不到上报',
  deviceOffline: '离线',
}

export function alarmText(alarm: string): string {
  return alarm
    .split(',')
    .map((a) => a.trim())
    .filter((a) => a.length > 0)
    .map((a) => ALARM_TEXT[a] ?? `告警（${a}）`)
    .join('、')
}

const PLACE_VERB: Record<string, string> = { geofenceEnter: '到达', geofenceExit: '离开' }

/** 到达、离开常去地点的事件；其余（告警、失联）算告警 */
export function isPlaceEvent(type: string): boolean {
  return type in PLACE_VERB
}

/** 事件对应位置的附加信息：地址、到达/离开的地点名字（地点已删掉时没有） */
export type EventExtra = { address?: string; placeName?: string }

/**
 * 一条事件的说明，如「关机，电量 3%，在 西溪路附近」「到达 学校」。
 * batteryLevel、address 是事件那一刻位置上的，没有就不写；到达/离开只写地点，不带电量和地址。
 */
export function eventText(type: string, alarm: string | undefined, batteryLevel: number | undefined, extra: EventExtra = {}): string {
  const verb = PLACE_VERB[type]
  if (verb) return `${verb} ${extra.placeName ?? '常去地点'}`
  let text: string
  if (type === 'alarm') {
    text = alarm ? alarmText(alarm) : '告警'
  } else {
    text = EVENT_TEXT[type] ?? type
  }
  if (batteryLevel !== undefined) text += `，电量 ${Math.round(batteryLevel)}%`
  const near = nearText(extra.address)
  if (near) text += `，${near}`
  return text
}

/** sos 和 tampering 要家长立刻看到，列表里标红。 */
export function isUrgent(type: string, alarm: string | undefined): boolean {
  if (type !== 'alarm' || !alarm) return false
  return alarm.split(',').some((a) => a.trim() === 'sos' || a.trim() === 'tampering')
}
