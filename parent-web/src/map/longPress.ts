// 手机上「长按地图」的判定：一根手指按住、基本不动，够时间才算。中途抬起、挪远（在拖地图）、放上第二根手指（在缩放）都取消。
// 电脑上用右键，不走这里。

/** 按住多久算长按（毫秒），和安卓系统长按差不多 */
export const LONG_PRESS_MS = 500
/** 手指抖动容许挪多远（像素），超过就当成拖地图 */
export const LONG_PRESS_SLOP = 10

export type LongPress = {
  /** 手指按下：touches 是屏幕上现在有几根手指 */
  start: (touches: number, x: number, y: number) => void
  move: (x: number, y: number) => void
  /** 抬起、取消、地图开始拖动 */
  cancel: () => void
}

export function longPress(onFire: (x: number, y: number) => void, delay = LONG_PRESS_MS, slop = LONG_PRESS_SLOP): LongPress {
  let timer: ReturnType<typeof setTimeout> | undefined
  let startX = 0
  let startY = 0
  const cancel = () => {
    clearTimeout(timer)
    timer = undefined
  }
  return {
    start(touches, x, y) {
      cancel()
      if (touches !== 1) return
      startX = x
      startY = y
      timer = setTimeout(() => {
        timer = undefined
        onFire(startX, startY)
      }, delay)
    },
    move(x, y) {
      if (timer !== undefined && Math.hypot(x - startX, y - startY) > slop) cancel()
    },
    cancel,
  }
}
