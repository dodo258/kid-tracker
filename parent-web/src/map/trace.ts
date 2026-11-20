// 截图回放（fixtures 模式）记下选中圆点每一帧在哪：scripts/replay.mjs 先放一个空数组 window.__dotTrace，再读出来查有没有瞬移。
// 正式构建里 import.meta.env.MODE 不是 fixtures，这段被整个删掉。
import type { LatLon } from '../api/types'
import type { Live } from '../domain/presence'

declare global {
  interface Window {
    /** [动画时钟毫秒, 纬度, 经度, 在路上的几种] */
    __dotTrace?: [number, number, number, Live][]
  }
}

export function traceFrame(t: number, at: LatLon, live: Live): void {
  if (import.meta.env.MODE !== 'fixtures') return
  window.__dotTrace?.push([t, at.latitude, at.longitude, live])
}
