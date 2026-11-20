// WebSocket 通着时页面不发普通请求，账号被停用、删除或令牌被吊销后 Traccar 也不断开已有的 WebSocket，
// 所以每 2 分钟带令牌查一次：回 401（AuthError）就停下并通知回登录页；断网、5xx 不算，下一轮再查。
import { AuthError } from './types'

export const AUTH_CHECK_MS = 120_000

/** 开始定时检查，返回停止函数 */
export function watchAuth(check: () => Promise<void>, onLost: () => void, intervalMs = AUTH_CHECK_MS): () => void {
  let stopped = false
  const stop = () => {
    stopped = true
    clearInterval(id)
  }
  const id = setInterval(() => {
    check().catch((err: unknown) => {
      if (stopped) return
      if (err instanceof AuthError) {
        stop()
        onLost()
      } else console.warn('登录状态检查失败，下一轮再查', err)
    })
  }, intervalMs)
  return stop
}
