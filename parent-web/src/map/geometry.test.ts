import { describe, expect, it } from 'vitest'
import { CONTINENT_DEG, minZoomFor } from './geometry'

/** 这一级、这么宽的地图看得到多少个经度 */
const spanDeg = (widthPx: number, zoom: number) => (widthPx / (512 * 2 ** zoom)) * 360

describe('地图最小缩放：最多缩到看一个洲', () => {
  it('iPhone 17 Pro（402 宽）、iPhone Air（420 宽）、电脑（1280 宽）缩到最小正好看 60 个经度，看不到整个地球', () => {
    for (const w of [402, 420, 1280]) {
      const z = minZoomFor(w)
      expect(spanDeg(w, z)).toBeCloseTo(CONTINENT_DEG, 6)
      expect(spanDeg(w, z)).toBeLessThan(360)
    }
    expect(minZoomFor(402)).toBeCloseTo(2.24, 2)
    expect(minZoomFor(1280)).toBeCloseTo(3.91, 2)
  })
  it('特别窄、特别宽的窗口有上下限', () => {
    expect(minZoomFor(100)).toBe(2)
    expect(minZoomFor(0)).toBe(2)
    expect(minZoomFor(8000)).toBe(5)
  })
})
