import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthError, ServerError, type SocketMessage } from './types'
import { RETRY_MS, runSocketLoop } from './socketLoop'

const EMPTY: SocketMessage = { devices: [], positions: [], events: [] }

/** 假的 subscribe：记下每条连接，测试里手动推消息、断开 */
function fakeServer(sessionErrors: unknown[] = []) {
  const sockets: { push: (m: SocketMessage) => void; drop: () => void; closed: boolean }[] = []
  const openSession = vi.fn(async () => {
    const err = sessionErrors.shift()
    if (err) throw err
  })
  const subscribe = vi.fn((onMessage: (m: SocketMessage) => void, onClose: () => void) => {
    const s = { push: onMessage, drop: onClose, closed: false }
    sockets.push(s)
    return () => {
      s.closed = true
    }
  })
  const onLive = vi.fn()
  const onAuthLost = vi.fn()
  const onResync = vi.fn()
  const deps = { openSession, subscribe, onMessage: vi.fn(), onLive, onResync, onAuthLost, onError: vi.fn() }
  return { deps, sockets, openSession, subscribe, onLive, onResync, onAuthLost }
}

describe('WebSocket 断线重连', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('连着收到过消息后断开：立刻重连，不等 30 秒', async () => {
    const s = fakeServer()
    runSocketLoop(s.deps)
    await vi.advanceTimersByTimeAsync(0)
    expect(s.subscribe).toHaveBeenCalledTimes(1)
    s.sockets[0].push(EMPTY)
    expect(s.onLive).toHaveBeenLastCalledWith(true)
    s.sockets[0].drop()
    expect(s.onLive).toHaveBeenLastCalledWith(false)
    await vi.advanceTimersByTimeAsync(0)
    expect(s.subscribe).toHaveBeenCalledTimes(2)
    s.sockets[1].push(EMPTY)
    expect(s.onLive).toHaveBeenLastCalledWith(true)
  })

  it('握手期间（连上了还没收到首帧）不算实时，上层照常轮询', async () => {
    const s = fakeServer()
    runSocketLoop(s.deps)
    await vi.advanceTimersByTimeAsync(0)
    expect(s.subscribe).toHaveBeenCalledTimes(1)
    expect(s.onLive).not.toHaveBeenCalledWith(true)
    s.sockets[0].push(EMPTY)
    expect(s.onLive).toHaveBeenLastCalledWith(true)
  })

  it('断线重连收到首帧后补拉一次；第一次连上不补拉', async () => {
    const s = fakeServer()
    runSocketLoop(s.deps)
    await vi.advanceTimersByTimeAsync(0)
    s.sockets[0].push(EMPTY)
    s.sockets[0].push(EMPTY)
    expect(s.onResync).not.toHaveBeenCalled()
    s.sockets[0].drop()
    await vi.advanceTimersByTimeAsync(0)
    // 新连接还在握手：先不补拉，免得补拉完首帧前又漏
    expect(s.onResync).not.toHaveBeenCalled()
    s.sockets[1].push(EMPTY)
    expect(s.onResync).toHaveBeenCalledTimes(1)
    s.sockets[1].push(EMPTY)
    expect(s.onResync).toHaveBeenCalledTimes(1)
  })

  it('第一次换会话就失败（一直在轮询），之后连上也补拉', async () => {
    const s = fakeServer([new ServerError(502)])
    runSocketLoop(s.deps)
    await vi.advanceTimersByTimeAsync(RETRY_MS)
    s.sockets[0].push(EMPTY)
    expect(s.onResync).toHaveBeenCalledTimes(1)
  })

  it('立刻重连后没收到消息又断：按 30 秒退避', async () => {
    const s = fakeServer()
    runSocketLoop(s.deps)
    await vi.advanceTimersByTimeAsync(0)
    s.sockets[0].push(EMPTY)
    s.sockets[0].drop()
    await vi.advanceTimersByTimeAsync(0)
    s.sockets[1].drop()
    await vi.advanceTimersByTimeAsync(RETRY_MS - 1)
    expect(s.subscribe).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(1)
    expect(s.subscribe).toHaveBeenCalledTimes(3)
    // 这次连通了，下次断开又是立刻重连
    s.sockets[2].push(EMPTY)
    s.sockets[2].drop()
    await vi.advanceTimersByTimeAsync(0)
    expect(s.subscribe).toHaveBeenCalledTimes(4)
  })

  it('立刻重连时换会话失败（还没网）：30 秒后再试', async () => {
    const s = fakeServer()
    runSocketLoop(s.deps)
    await vi.advanceTimersByTimeAsync(0)
    s.sockets[0].push(EMPTY)
    s.openSession.mockRejectedValueOnce(new ServerError(502))
    s.sockets[0].drop()
    await vi.advanceTimersByTimeAsync(0)
    expect(s.openSession).toHaveBeenCalledTimes(2)
    expect(s.subscribe).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(RETRY_MS)
    expect(s.openSession).toHaveBeenCalledTimes(3)
    expect(s.subscribe).toHaveBeenCalledTimes(2)
  })

  it('令牌失效：回登录页，不再重试', async () => {
    const s = fakeServer([new AuthError()])
    runSocketLoop(s.deps)
    await vi.advanceTimersByTimeAsync(RETRY_MS * 3)
    expect(s.onAuthLost).toHaveBeenCalledTimes(1)
    expect(s.openSession).toHaveBeenCalledTimes(1)
  })

  it('停止后关掉连接，断开也不再重连', async () => {
    const s = fakeServer()
    const stop = runSocketLoop(s.deps)
    await vi.advanceTimersByTimeAsync(0)
    s.sockets[0].push(EMPTY)
    stop()
    expect(s.sockets[0].closed).toBe(true)
    s.sockets[0].drop()
    await vi.advanceTimersByTimeAsync(RETRY_MS * 3)
    expect(s.subscribe).toHaveBeenCalledTimes(1)
  })
})
