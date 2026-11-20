// 退出登录的服务器那一半：退出会话（WebSocket 换来的会话 cookie）、吊销长期令牌，两步分开做，一步失败另一步照做。
// 401 说明这一步在服务器上已经不算数了（会话没了、令牌已失效），当作做成。本机的令牌由调用方先清掉（立刻看不到位置）。
import { AuthError, type Api } from '../api/types'

/** 两步各自做成没有；重试时只做没成的 */
export type SignOutSteps = { session: boolean; token: boolean }

export const NOT_SIGNED_OUT: SignOutSteps = { session: false, token: false }

async function step(run: () => Promise<void>, what: string): Promise<boolean> {
  try {
    await run()
    return true
  } catch (err) {
    if (err instanceof AuthError) return true
    console.warn(`${what}失败`, err)
    return false
  }
}

export async function signOut(api: Pick<Api, 'endSession' | 'revokeToken'>, token: string, done: SignOutSteps = NOT_SIGNED_OUT): Promise<SignOutSteps> {
  // 先退会话再吊销：退会话也要用这个令牌认证
  const session = done.session || (await step(() => api.endSession(token), '退出服务器会话'))
  const revoked = done.token || (await step(() => api.revokeToken(token), '吊销令牌'))
  return { session, token: revoked }
}

export function signedOut(s: SignOutSteps): boolean {
  return s.session && s.token
}
