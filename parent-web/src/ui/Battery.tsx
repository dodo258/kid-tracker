// 状态卡右上角的电量：充电闪电、小电池（描边，里面按电量填充）、百分比。颜色按 domain/battery：平时灰、充电绿、低电橙加粗
import { Zap } from 'lucide-react'
import { batteryPercent, batteryTone } from '../domain/battery'
import type { Tone } from '../domain/status'

// Tailwind 只认完整类名，不能拼字符串
const COLOR: Record<Tone, string> = { calm: 'text-mute', an: 'text-an', jing: 'font-semibold text-jing' }
/** 电池里面能填的宽度（viewBox 单位） */
const INNER_W = 16
/** 有电时最少填这么宽：3%、9% 也要看得见一小截 */
const MIN_FILL = 2.5

export function Battery({ level, charging }: { level: number; charging: boolean | undefined }) {
  const percent = batteryPercent(level)
  const tone = batteryTone(level, charging)
  const fill = percent === 0 ? 0 : Math.max(MIN_FILL, (INNER_W * percent) / 100)
  return (
    <span data-battery={tone} className={`flex shrink-0 items-center gap-1 text-body tabular-nums ${COLOR[tone]}`}>
      {charging && <Zap size={13} strokeWidth={2.5} className="-mr-0.5" aria-label="充电中" />}
      <svg width="23" height="12" viewBox="0 0 23 12" fill="currentColor" aria-hidden>
        <rect x="0.75" y="0.75" width="19.5" height="10.5" rx="3" fill="none" stroke="currentColor" strokeWidth="1.5" />
        <rect x="21.25" y="4" width="1.75" height="4" rx="0.75" />
        <rect data-fill x="2.5" y="2.5" width={fill} height="7" rx="1.25" />
      </svg>
      {percent}%
    </span>
  )
}
