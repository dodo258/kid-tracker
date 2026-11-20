// 时间文字：「刚刚」「3 分钟前」「昨天 22:10」，和「孩子手机时间往后错了」的判断。

export const MINUTE = 60_000
/** 定位时间比现在晚这么多以上，就是孩子手机的时间往后错了（几分钟内的差当成两边时钟不准，算「刚刚」） */
const CLOCK_AHEAD_MS = 5 * MINUTE
export const CLOCK_AHEAD_LABEL = '孩子手机时间不对'

/** 孩子手机的时间比现在快 5 分钟以上 */
export function isClockAhead(time: Date, now: Date): boolean {
  return time.getTime() - now.getTime() > CLOCK_AHEAD_MS
}

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

function startOfDay(d: Date): number {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
}

/** 告警列表里的时间：今天只写「22:10」，昨天写「昨天 22:10」，更早写「11月14日 22:10」。seconds 时带秒「22:10:03」（详细信息） */
export function clockLabel(time: Date, now: Date, seconds = false): string {
  const hm = `${pad(time.getHours())}:${pad(time.getMinutes())}${seconds ? `:${pad(time.getSeconds())}` : ''}`
  const days = Math.round((startOfDay(now) - startOfDay(time)) / 86_400_000)
  if (days <= 0) return hm
  if (days === 1) return `昨天 ${hm}`
  return `${time.getMonth() + 1}月${time.getDate()}日 ${hm}`
}

/** 「刚刚」「3 分钟前」「2 小时前」；超过一天写具体时间；比现在晚 5 分钟以上写「孩子手机时间不对」。 */
export function agoLabel(time: Date, now: Date): string {
  if (isClockAhead(time, now)) return CLOCK_AHEAD_LABEL
  const ageMs = now.getTime() - time.getTime()
  if (ageMs < MINUTE) return '刚刚'
  if (ageMs < 60 * MINUTE) return `${Math.floor(ageMs / MINUTE)} 分钟前`
  if (ageMs < 24 * 60 * MINUTE) return `${Math.floor(ageMs / (60 * MINUTE))} 小时前`
  return clockLabel(time, now)
}
