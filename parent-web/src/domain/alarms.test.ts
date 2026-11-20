import { describe, expect, it } from 'vitest'
import { alarmText, eventText, isPlaceEvent, isUrgent } from './alarms'

describe('告警中文文案', () => {
  it('App 会报的六种告警', () => {
    expect(alarmText('lowBattery')).toBe('电量低')
    expect(alarmText('powerOff')).toBe('关机')
    expect(alarmText('general')).toBe('定位被关掉或权限被收回')
    expect(alarmText('sos')).toBe('发出求救')
    expect(alarmText('tampering')).toBe('有人想卸载或停用定位')
    expect(alarmText('trackingOff')).toBe('追踪被关闭')
  })
  it('同一个点多个告警用顿号连起来', () => {
    expect(alarmText('lowBattery,powerOff')).toBe('电量低、关机')
  })
  it('不认识的告警保留原值', () => {
    expect(alarmText('jamming')).toBe('告警（jamming）')
  })
  it('事件带上当时的电量', () => {
    expect(eventText('alarm', 'powerOff', 3)).toBe('关机，电量 3%')
    expect(eventText('alarm', 'lowBattery', 9.6)).toBe('电量低，电量 10%')
    expect(eventText('deviceUnknown', undefined, undefined)).toBe('失联，服务器收不到上报')
    expect(eventText('alarm', undefined, undefined)).toBe('告警')
  })
  it('求救和防护被触发标为紧急', () => {
    expect(isUrgent('alarm', 'sos')).toBe(true)
    expect(isUrgent('alarm', 'lowBattery,tampering')).toBe(true)
    expect(isUrgent('alarm', 'powerOff')).toBe(false)
    expect(isUrgent('deviceUnknown', undefined)).toBe(false)
  })
  it('到达、离开常去地点', () => {
    expect(eventText('geofenceEnter', undefined, 80, { placeName: '学校' })).toBe('到达 学校')
    expect(eventText('geofenceExit', undefined, undefined, { placeName: '家', address: '教工路 18 号' })).toBe('离开 家')
    expect(eventText('geofenceEnter', undefined, undefined)).toBe('到达 常去地点')
    expect(isPlaceEvent('geofenceEnter')).toBe(true)
    expect(isPlaceEvent('geofenceExit')).toBe(true)
    expect(isPlaceEvent('alarm')).toBe(false)
    expect(isUrgent('geofenceExit', undefined)).toBe(false)
  })
  it('告警带上事件位置的地址', () => {
    expect(eventText('alarm', 'general', undefined, { address: '教工路 18 号' })).toBe('定位被关掉或权限被收回，在 教工路 18 号附近')
    expect(eventText('alarm', 'powerOff', 3, { address: '教工路 18 号' })).toBe('关机，电量 3%，在 教工路 18 号附近')
    expect(eventText('alarm', 'powerOff', 3, { address: '  ' })).toBe('关机，电量 3%')
  })
})
