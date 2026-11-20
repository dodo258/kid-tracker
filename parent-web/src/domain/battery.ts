// 状态卡右上角的电量（方案 docs/specs/2026-11-18-parent-web-polish.md「设计语言」）：颜色只表达信号。
// 平时灰；充电中安绿（优先，低电也是绿）；不充电且显示出来的数字 ≤20% 时警橙加粗。纯函数。
import type { Tone } from './status'

/** 显示的百分比到这里及以下算低电 */
export const LOW_BATTERY = 20

/** 卡片上显示的整数百分比（0–100） */
export function batteryPercent(level: number): number {
  return Math.min(100, Math.max(0, Math.round(level)))
}

/** 电量图标和数字的颜色档：calm 灰、an 充电、jing 低电 */
export function batteryTone(level: number, charging: boolean | undefined): Tone {
  if (charging) return 'an'
  return batteryPercent(level) <= LOW_BATTERY ? 'jing' : 'calm'
}
