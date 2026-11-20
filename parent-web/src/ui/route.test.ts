import { describe, expect, it } from 'vitest'
import { hashOf, PAGES, routeOf, routeTarget, visiblePage } from './route'

const pageOf = (hash: string) => routeOf(hash)?.page ?? null

describe('二级页路由', () => {
  it('hash 和页面互相换得回来', () => {
    for (const p of PAGES) expect(pageOf(hashOf({ page: p, deviceId: null }))).toBe(p)
  })
  it('没有 hash、不认识的 hash 停在地图上', () => {
    expect(pageOf('')).toBeNull()
    expect(pageOf('#')).toBeNull()
    expect(pageOf('#/admin')).toBeNull()
    expect(pageOf('#alerts')).toBe('alerts')
  })
  it('设置任何时候都能进：加载中、没有设备、连不上服务器也能退出', () => {
    expect(visiblePage('settings', { device: false, position: false })).toBe('settings')
  })
  it('告警、常去地点要选中了孩子；详情还要有位置', () => {
    expect(visiblePage('alerts', { device: false, position: false })).toBeNull()
    expect(visiblePage('places', { device: true, position: false })).toBe('places')
    expect(visiblePage('details', { device: true, position: false })).toBeNull()
    expect(visiblePage('details', { device: true, position: true })).toBe('details')
    expect(visiblePage(null, { device: true, position: true })).toBeNull()
  })
})

describe('二级页历史带设备号（审计 11-18 #7）', () => {
  it('告警、常去地点、详情的地址带设备号，设置不带', () => {
    expect(hashOf({ page: 'alerts', deviceId: 12 })).toBe('#/alerts/12')
    expect(routeOf('#/alerts/12')).toEqual({ page: 'alerts', deviceId: 12 })
    expect(routeOf('#/places/3')).toEqual({ page: 'places', deviceId: 3 })
    expect(routeOf('#/settings/3')).toEqual({ page: 'settings', deviceId: null })
    expect(routeOf('#/alerts')).toEqual({ page: 'alerts', deviceId: null })
    expect(routeOf('#/alerts/abc')).toEqual({ page: 'alerts', deviceId: null })
  })
  it('前进回甲的告警页、但现在选的是乙：先换回甲，换好之前不显示', () => {
    const ctx = { loaded: true, deviceIds: [1, 2], selectedId: 2 }
    expect(routeTarget({ page: 'alerts', deviceId: 1 }, ctx)).toEqual({ kind: 'select', deviceId: 1 })
    expect(routeTarget({ page: 'alerts', deviceId: 1 }, { ...ctx, selectedId: 1 })).toEqual({ kind: 'show' })
  })
  it('那台设备已经看不到了：回地图选孩子', () => {
    expect(routeTarget({ page: 'places', deviceId: 7 }, { loaded: true, deviceIds: [1, 2], selectedId: 1 })).toEqual({ kind: 'leave' })
  })
  it('设备名单还没拉到：先等；设置、没写设备的照常显示', () => {
    expect(routeTarget({ page: 'alerts', deviceId: 1 }, { loaded: false, deviceIds: [], selectedId: null })).toEqual({ kind: 'wait' })
    expect(routeTarget({ page: 'settings', deviceId: null }, { loaded: false, deviceIds: [], selectedId: null })).toEqual({ kind: 'show' })
  })
})
