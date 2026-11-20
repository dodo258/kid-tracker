// 深浅色：跟随系统时 html 上不带 data-theme，index.css 按系统；固定浅色、深色时写上，CSS 和地图（map/baseMap.isDark）都看它
import type { ThemePref } from '../api/types'

export function applyTheme(theme: ThemePref): void {
  const root = document.documentElement
  if (theme === 'system') delete root.dataset.theme
  else root.dataset.theme = theme
}
