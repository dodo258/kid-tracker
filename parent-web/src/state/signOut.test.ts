import { describe, expect, it, vi } from 'vitest'
import { AuthError, ServerError } from '../api/types'
import { signOut, signedOut } from './signOut'

function api(endSession: () => Promise<void>, revokeToken: () => Promise<void>) {
  const a = { endSession: vi.fn(endSession), revokeToken: vi.fn(revokeToken) }
  return { a, api: a }
}

describe('退出登录（审计 #9）', () => {
  it('退出会话失败（断网）：吊销令牌照做，结果看得出没退干净', async () => {
    const { a, api: x } = api(() => Promise.reject(new TypeError('Failed to fetch')), async () => {})
    const r = await signOut(x, 't')
    expect(a.revokeToken).toHaveBeenCalledTimes(1)
    expect(r).toEqual({ session: false, token: true })
    expect(signedOut(r)).toBe(false)
  })

  it('重试只做没成的那一步', async () => {
    const { a, api: x } = api(async () => {}, async () => {})
    const r = await signOut(x, 't', { session: false, token: true })
    expect(a.endSession).toHaveBeenCalledTimes(1)
    expect(a.revokeToken).not.toHaveBeenCalled()
    expect(signedOut(r)).toBe(true)
  })

  it('服务器报错算没成；401 是已经失效，算成', async () => {
    const { api: x } = api(
      () => Promise.reject(new AuthError()),
      () => Promise.reject(new ServerError(502)),
    )
    expect(await signOut(x, 't')).toEqual({ session: true, token: false })
  })
})
