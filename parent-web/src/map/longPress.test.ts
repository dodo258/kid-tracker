import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LONG_PRESS_MS, longPress } from './longPress'

describe('长按判定', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('按住不动够时间才算，报按下的位置', () => {
    const fire = vi.fn()
    const lp = longPress(fire)
    lp.start(1, 120, 300)
    vi.advanceTimersByTime(LONG_PRESS_MS - 1)
    expect(fire).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)
    expect(fire).toHaveBeenCalledExactlyOnceWith(120, 300)
  })

  it('手指小抖动（10 像素内）不取消', () => {
    const fire = vi.fn()
    const lp = longPress(fire)
    lp.start(1, 100, 100)
    lp.move(106, 108)
    vi.advanceTimersByTime(LONG_PRESS_MS)
    expect(fire).toHaveBeenCalledExactlyOnceWith(100, 100)
  })

  it('点一下就抬起不算', () => {
    const fire = vi.fn()
    const lp = longPress(fire)
    lp.start(1, 100, 100)
    vi.advanceTimersByTime(150)
    lp.cancel()
    vi.advanceTimersByTime(LONG_PRESS_MS)
    expect(fire).not.toHaveBeenCalled()
  })

  it('按住拖地图（挪出 10 像素）不算', () => {
    const fire = vi.fn()
    const lp = longPress(fire)
    lp.start(1, 100, 100)
    lp.move(100, 111)
    vi.advanceTimersByTime(LONG_PRESS_MS)
    expect(fire).not.toHaveBeenCalled()
  })

  it('两根手指（缩放）不算，第二根放上来也取消', () => {
    const fire = vi.fn()
    const lp = longPress(fire)
    lp.start(2, 100, 100)
    vi.advanceTimersByTime(LONG_PRESS_MS)
    lp.start(1, 100, 100)
    lp.start(2, 100, 100)
    vi.advanceTimersByTime(LONG_PRESS_MS)
    expect(fire).not.toHaveBeenCalled()
  })

  it('长按触发后再挪动、抬起不会再报', () => {
    const fire = vi.fn()
    const lp = longPress(fire)
    lp.start(1, 100, 100)
    vi.advanceTimersByTime(LONG_PRESS_MS)
    lp.move(200, 200)
    lp.cancel()
    vi.advanceTimersByTime(LONG_PRESS_MS)
    expect(fire).toHaveBeenCalledTimes(1)
  })
})
