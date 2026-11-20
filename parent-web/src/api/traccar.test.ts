import { afterEach, describe, expect, it, vi } from 'vitest'
import { errorText } from './errorText'
import { SOCKET_IDLE_MS, traccarApi } from './traccar'
import { AuthError, ServerError } from './types'

function stubFetch(status: number) {
  const fetch = vi.fn(async (_url: string, _init?: RequestInit) => new Response(status === 200 ? '[]' : '', { status }))
  vi.stubGlobal('fetch', fetch)
  return fetch
}

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('checkAuth：定时查登录状态', () => {
  it('带 Bearer 令牌查要登录的接口，不靠会话 cookie', async () => {
    const fetch = stubFetch(200)
    await traccarApi.checkAuth('tok')
    const [url, init] = fetch.mock.calls[0]
    expect(url).toBe('/api/devices')
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer tok')
  })
  it('令牌吊销、账号停用或删除回 401：抛 AuthError', async () => {
    stubFetch(401)
    await expect(traccarApi.checkAuth('tok')).rejects.toBeInstanceOf(AuthError)
  })
  it('服务器重启回 502：不算失效', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    stubFetch(502)
    await expect(traccarApi.checkAuth('tok')).rejects.toBeInstanceOf(ServerError)
  })
})

describe('出错文案', () => {
  it('5xx 只说暂时连不上，接口路径只进 console', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    stubFetch(502)
    const err = await traccarApi.devices('tok').catch((e: unknown) => e)
    expect(errorText(err)).toBe('暂时连不上服务器')
    expect(errorText(err)).not.toContain('/api')
    expect(warn.mock.calls.flat().join(' ')).toContain('/api/devices')
  })
  it('超时、断网也是暂时连不上', () => {
    expect(errorText(new DOMException('signal timed out', 'TimeoutError'))).toBe('暂时连不上服务器')
    expect(errorText(new TypeError('Failed to fetch'))).toBe('暂时连不上服务器')
  })
  it('其他状态码写状态码，不写路径', () => {
    expect(errorText(new ServerError(404))).toBe('服务器返回 404')
  })
})

describe('positionsById：按编号取告警位置', () => {
  const pos = (id: number) => ({ id, deviceId: 3, fixTime: '2026-11-20T00:33:51Z', latitude: 30, longitude: 120 })
  it('有一个点已从库里删掉、整批回 400：逐个取，删掉的跳过，别的照常', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    const fetch = vi.fn(async (url: string) => {
      const ids = new URL(url, 'http://x').searchParams.getAll('id').map(Number)
      if (ids.includes(1615)) return new Response('', { status: 400 })
      return new Response(JSON.stringify(ids.map(pos)), { status: 200 })
    })
    vi.stubGlobal('fetch', fetch)
    const ps = await traccarApi.positionsById('tok', [1615, 1881, 1983])
    expect(ps.map((p) => p.id)).toEqual([1881, 1983])
  })
  it('5xx 照旧报错，不逐个重试', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    const fetch = stubFetch(502)
    await expect(traccarApi.positionsById('tok', [1, 2])).rejects.toBeInstanceOf(ServerError)
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})

class FakeSocket {
  static last: FakeSocket
  onmessage: ((e: { data: unknown }) => void) | null = null
  onclose: (() => void) | null = null
  closed = 0
  readonly url: string
  constructor(url: string) {
    this.url = url
    FakeSocket.last = this
  }
  close() {
    this.closed++
  }
}

function openSocket() {
  vi.useFakeTimers()
  vi.stubGlobal('WebSocket', FakeSocket)
  vi.stubGlobal('location', { protocol: 'https:', host: 'gps.example' })
  vi.spyOn(console, 'warn').mockImplementation(() => {})
  const onMessage = vi.fn()
  const onClose = vi.fn()
  const unsubscribe = traccarApi.subscribe(onMessage, onClose)
  return { socket: FakeSocket.last, onMessage, onClose, unsubscribe }
}

describe('WebSocket 假死', () => {
  it('120 秒什么都没收到：自己关掉，按断线通知一次', () => {
    const { socket, onClose } = openSocket()
    expect(socket.url).toBe('wss://gps.example/api/socket')
    vi.advanceTimersByTime(SOCKET_IDLE_MS - 1)
    expect(onClose).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)
    expect(socket.closed).toBe(1)
    expect(onClose).toHaveBeenCalledTimes(1)
    // 浏览器之后补发的 close 事件不再重复通知
    socket.onclose?.()
    expect(onClose).toHaveBeenCalledTimes(1)
  })
  it('每 55 秒一次的保活 {} 让连接一直算活着', () => {
    const { socket, onMessage, onClose } = openSocket()
    for (let i = 0; i < 5; i++) {
      vi.advanceTimersByTime(55_000)
      socket.onmessage?.({ data: '{}' })
    }
    expect(onClose).not.toHaveBeenCalled()
    expect(onMessage).toHaveBeenCalledTimes(5)
    vi.advanceTimersByTime(SOCKET_IDLE_MS)
    expect(onClose).toHaveBeenCalledTimes(1)
  })
  it('服务器断开：通知一次，不再计时', () => {
    const { socket, onClose } = openSocket()
    socket.onclose?.()
    vi.advanceTimersByTime(SOCKET_IDLE_MS * 2)
    expect(onClose).toHaveBeenCalledTimes(1)
  })
  it('页面自己取消订阅：不算断线', () => {
    const { socket, onClose, unsubscribe } = openSocket()
    unsubscribe()
    socket.onclose?.()
    vi.advanceTimersByTime(SOCKET_IDLE_MS * 2)
    expect(socket.closed).toBe(1)
    expect(onClose).not.toHaveBeenCalled()
  })
})
