// 二级页（方案 2026-11-18-parent-web-polish「卡片：底部抽屉两档 + 二级页」）：告警、常去地点、这次定位的详情、设置。
// 点卡片上的入口整页滑入，左上角返回。地址栏记在 #/alerts/12 这样的 hash 里（12 是设备号）：Safari 的返回手势、刷新、前进都认得，
// 打开时 pushState 一条历史，返回就是 history.back()；直接带着 hash 打开的（刷新、截图）返回时把 hash 去掉。
// 告警、常去地点、详情是某个孩子的：历史里带着设备号，恢复时换回那个孩子；那台设备已经看不到了就回地图（选孩子）。
import { useCallback, useEffect, useState } from 'react'

export type Page = 'alerts' | 'places' | 'details' | 'settings'

export const PAGES: readonly Page[] = ['alerts', 'places', 'details', 'settings']

export const PAGE_TITLE: Record<Page, string> = { alerts: '最近 24 小时', places: '常去地点', details: '这次定位的详情', settings: '设置' }

/** 打开的二级页；deviceId 是这页属于哪个孩子（设置、旧地址里没写的是 null） */
export type Route = { page: Page; deviceId: number | null }

/** 告警、常去地点、详情是某个孩子的 */
export function isDevicePage(page: Page): boolean {
  return page !== 'settings'
}

/** '#/alerts/12' → 告警页、设备 12；'#/alerts' 没写设备；不认识的、空的是 null（停在地图上） */
export function routeOf(hash: string): Route | null {
  const [name, id] = hash.replace(/^#\/?/, '').split('/')
  const page = PAGES.find((p) => p === name)
  if (!page) return null
  const deviceId = isDevicePage(page) && id !== undefined && /^\d+$/.test(id) ? Number(id) : null
  return { page, deviceId }
}

/** 告警页、设备 12 → '#/alerts/12' */
export function hashOf(route: Route): string {
  return route.deviceId === null ? `#/${route.page}` : `#/${route.page}/${route.deviceId}`
}

/** 现在能看到哪一页：设置任何时候都能进（连不上服务器、没有设备时也要能退出）；告警、常去地点要选中了孩子；详情还要有位置 */
export function visiblePage(page: Page | null, ctx: { device: boolean; position: boolean }): Page | null {
  if (page === 'settings') return page
  if (page === 'details') return ctx.position ? page : null
  return ctx.device ? page : null
}

/**
 * 历史里恢复的页属于哪个孩子，现在该怎么办：show 照常显示；select 先换到那个孩子（换好之前不显示，免得显示别的孩子的告警）；
 * leave 那台设备已经看不到了，回地图选孩子；wait 设备名单还没拉到
 */
export type RouteTarget = { kind: 'show' } | { kind: 'select'; deviceId: number } | { kind: 'leave' } | { kind: 'wait' }

export function routeTarget(route: Route, ctx: { loaded: boolean; deviceIds: readonly number[]; selectedId: number | null }): RouteTarget {
  if (route.deviceId === null) return { kind: 'show' }
  if (!ctx.loaded) return { kind: 'wait' }
  if (!ctx.deviceIds.includes(route.deviceId)) return { kind: 'leave' }
  if (route.deviceId !== ctx.selectedId) return { kind: 'select', deviceId: route.deviceId }
  return { kind: 'show' }
}

type PageControl = {
  route: Route | null
  open: (page: Page, deviceId: number | null) => void
  /** 返回（和 Safari 返回手势一样） */
  close: () => void
  /** 不留历史地回到地图（恢复的页属于看不到的设备） */
  leave: () => void
}

/** 当前二级页和开关：initial 是截图用的（地址里没有 hash 时按它打开） */
export function usePage(initial?: Page): PageControl {
  const [route, setRoute] = useState<Route | null>(() => {
    const fromHash = routeOf(location.hash)
    if (fromHash || !initial) return fromHash
    const r = { page: initial, deviceId: null }
    history.replaceState(null, '', hashOf(r))
    return r
  })
  useEffect(() => {
    const sync = () => setRoute(routeOf(location.hash))
    window.addEventListener('popstate', sync)
    window.addEventListener('hashchange', sync)
    return () => {
      window.removeEventListener('popstate', sync)
      window.removeEventListener('hashchange', sync)
    }
  }, [])
  const open = useCallback((page: Page, deviceId: number | null) => {
    const r = { page, deviceId: isDevicePage(page) ? deviceId : null }
    history.pushState({ page }, '', hashOf(r))
    setRoute(r)
  }, [])
  const leave = useCallback(() => {
    history.replaceState(null, '', location.pathname + location.search)
    setRoute(null)
  }, [])
  const close = useCallback(() => {
    // 是本页打开时推进去的那条：退回去（和 Safari 返回手势一样）；直接带 hash 打开的：只把 hash 去掉
    const pushed: unknown = history.state
    if (typeof pushed === 'object' && pushed !== null && 'page' in pushed) {
      history.back()
      return
    }
    leave()
  }, [leave])
  return { route, open, close, leave }
}
