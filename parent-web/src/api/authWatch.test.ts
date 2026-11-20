import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthError, ServerError } from './types'
import { AUTH_CHECK_MS, watchAuth } from './authWatch'

describe('WebSocket 通着时定时查登录状态', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.spyOn(console, 'warn').mockImplementation(() => {})
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('每 2 分钟查一次，没到 2 分钟不查', async () => {
    const check = vi.fn(async () => {})
    const stop = watchAuth(check, () => {})
    await vi.advanceTimersByTimeAsync(AUTH_CHECK_MS - 1)
    expect(check).toHaveBeenCalledTimes(0)
    await vi.advanceTimersByTimeAsync(1)
    expect(check).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(AUTH_CHECK_MS)
    expect(check).toHaveBeenCalledTimes(2)
    stop()
    await vi.advanceTimersByTimeAsync(AUTH_CHECK_MS * 3)
    expect(check).toHaveBeenCalledTimes(2)
  })

  it('回 401 就回登录页，且不再查', async () => {
    const check = vi.fn(async () => {
      throw new AuthError()
    })
    const onLost = vi.fn()
    watchAuth(check, onLost)
    await vi.advanceTimersByTimeAsync(AUTH_CHECK_MS)
    expect(onLost).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(AUTH_CHECK_MS * 3)
    expect(check).toHaveBeenCalledTimes(1)
    expect(onLost).toHaveBeenCalledTimes(1)
  })

  it('服务器 502、断网不算失效，下一轮接着查', async () => {
    const errors = [new ServerError(502), new TypeError('Failed to fetch')]
    const check = vi.fn(async () => {
      const err = errors.shift()
      if (err) throw err
    })
    const onLost = vi.fn()
    watchAuth(check, onLost)
    await vi.advanceTimersByTimeAsync(AUTH_CHECK_MS * 3)
    expect(check).toHaveBeenCalledTimes(3)
    expect(onLost).not.toHaveBeenCalled()
  })
})
