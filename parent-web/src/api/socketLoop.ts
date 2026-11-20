// WebSocket 连接循环：先用令牌换会话再连 /api/socket。
// 连着收到过消息后断开（偶然掉线、假死被关掉）立刻重连；重连失败（换会话失败、连上没收到消息就断）再按 30 秒一次重试，
// 免得服务器连上就断时一直狂连。401 不重试，交给上层回登录页。
// 收到这条连接的第一帧（Traccar 新连接的首帧是最新位置）才算连上：握手期间上层照常轮询。
// 断过（掉线、换会话失败）以后重新连上，收到首帧时通知上层补拉断线期间的事件和轨迹（WebSocket 只推之后的）。
import { AuthError, type SocketMessage } from './types'

export const RETRY_MS = 30_000

export type SocketLoopDeps = {
  openSession: () => Promise<void>
  subscribe: (onMessage: (m: SocketMessage) => void, onClose: () => void) => () => void
  onMessage: (m: SocketMessage) => void
  /** 收到首帧 true，断开 false（false 时上层轮询兜底） */
  onLive: (live: boolean) => void
  /** 断过以后重新连上、收到首帧：补拉断线期间漏掉的 */
  onResync: () => void
  onAuthLost: () => void
  onError: (err: unknown) => void
}

/** 开始连接，返回停止函数 */
export function runSocketLoop(d: SocketLoopDeps, retryMs = RETRY_MS): () => void {
  let stopped = false
  let unsubscribe: (() => void) | null = null
  let retry: ReturnType<typeof setTimeout> | undefined
  /** 上次连上以后断过、连不上过 */
  let broken = false
  const schedule = (ms: number) => {
    if (!stopped) retry = setTimeout(() => void connect().catch(d.onError), ms)
  }
  const connect = async () => {
    try {
      await d.openSession()
    } catch (err) {
      if (err instanceof AuthError) {
        d.onAuthLost()
        return
      }
      broken = true
      schedule(retryMs)
      return
    }
    if (stopped) return
    // 这条连接收到过消息才算连通过，断开时立刻重连
    let received = false
    unsubscribe = d.subscribe(
      (m) => {
        d.onMessage(m)
        if (received) return
        received = true
        d.onLive(true)
        if (broken) {
          broken = false
          d.onResync()
        }
      },
      () => {
        unsubscribe = null
        broken = true
        d.onLive(false)
        schedule(received ? 0 : retryMs)
      },
    )
  }
  connect().catch(d.onError)
  return () => {
    stopped = true
    unsubscribe?.()
    clearTimeout(retry)
  }
}
