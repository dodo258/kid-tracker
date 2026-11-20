// 出错时给家长看的一句话：服务器 5xx、超时、断网都是「暂时连不上服务器」，不显示接口路径和英文原文。
import { ServerError } from './types'

export const UNREACHABLE = '暂时连不上服务器'

export function errorText(err: unknown): string {
  if (err instanceof ServerError) return err.message
  // fetch 超时（AbortSignal.timeout）是 TimeoutError，断网、DNS、被拦是 TypeError
  if (err instanceof DOMException && (err.name === 'TimeoutError' || err.name === 'AbortError')) return UNREACHABLE
  if (err instanceof TypeError) return UNREACHABLE
  return err instanceof Error ? err.message : String(err)
}
