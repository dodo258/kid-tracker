// 只存在家长手机浏览器里的几样东西：长期令牌、上次看的设备、状态卡上次停在哪一档、设置里选的底图和深浅色。
import type { BasePref, Snap, ThemePref } from './types'

const TOKEN_KEY = 'parent-web.token'
const DEVICE_KEY = 'parent-web.device'
const SNAP_KEY = 'parent-web.sheet'
const BASE_KEY = 'parent-web.base'
const THEME_KEY = 'parent-web.theme'

function read(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null // Safari 无痕模式等禁用 localStorage 的情况：当作没存
  }
}

function write(key: string, value: string | null): void {
  try {
    if (value === null) localStorage.removeItem(key)
    else localStorage.setItem(key, value)
  } catch (err) {
    console.warn('localStorage 写入失败', err)
  }
}

export function loadToken(): string | null {
  const t = read(TOKEN_KEY)
  return t && /^[A-Za-z0-9_-]+$/.test(t) ? t : null
}

export const saveToken = (token: string | null) => write(TOKEN_KEY, token)

export function loadDeviceId(): number | null {
  const n = Number(read(DEVICE_KEY))
  return Number.isInteger(n) && n > 0 ? n : null
}

export const saveDeviceId = (id: number) => write(DEVICE_KEY, String(id))

/** 状态卡上次停在哪一档；没存过、读不到或不认识都是收起（地图优先）。三档时存的半开、全开都算展开 */
export function loadSnap(): Snap {
  const s = read(SNAP_KEY)
  return s === 'open' || s === 'half' || s === 'full' ? 'open' : 'peek'
}

export const saveSnap = (snap: Snap) => write(SNAP_KEY, snap)

/** 底图；没存过或不认识是自动 */
export function loadBasePref(): BasePref {
  const s = read(BASE_KEY)
  return s === 'amap' || s === 'tencent' || s === 'osm' ? s : 'auto'
}

export const saveBasePref = (base: BasePref) => write(BASE_KEY, base === 'auto' ? null : base)

/** 深浅色；没存过或不认识是跟随系统 */
export function loadThemePref(): ThemePref {
  const s = read(THEME_KEY)
  return s === 'light' || s === 'dark' ? s : 'system'
}

export const saveThemePref = (theme: ThemePref) => write(THEME_KEY, theme === 'system' ? null : theme)
