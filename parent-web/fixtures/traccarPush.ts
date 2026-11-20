// Traccar 6.17 存下一个点以后推不推、换不换设备的最新位置（PostProcessHandler）：定位时间不早于当前最新位置的才换、才推；
// 晚到的旧点（乱序补传）只进历史库，WebSocket 不推、/api/positions 也不给。回放按它模拟服务器（和 tools/simulator/traccar.py 的 pushed 一致）。
import type { Position } from '../src/api/types'

export type Feed = {
  /** 会推给网页的点，按服务器收到的先后 */
  pushed: Position[]
  /** 到 t（毫秒）为止设备的最新位置（/api/positions 给的那条）；还没有是 undefined */
  latestAt: (t: number) => Position | undefined
}

export const received = (p: Position) => (p.serverTime ?? p.deviceTime).getTime()

/** all 是服务器存下的点（任意顺序） */
export function traccarFeed(all: readonly Position[]): Feed {
  const sorted = [...all].sort((a, b) => received(a) - received(b) || a.id - b.id)
  const pushed: Position[] = []
  let latest: Position | undefined
  for (const p of sorted) {
    if (latest && p.fixTime.getTime() < latest.fixTime.getTime()) continue
    latest = p
    pushed.push(p)
  }
  return {
    pushed,
    latestAt: (t) => pushed.filter((p) => received(p) <= t).at(-1),
  }
}
