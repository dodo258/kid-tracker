import { describe, expect, it } from 'vitest'
import { centeredScrollLeft } from './scroll'

describe('切换器把选中的滚到中间', () => {
  it('选中的在右边外面：滚到它在正中', () => {
    // 容器 x=12 宽 300，选中项现在在 x=400 宽 100：中心应落在 12+150=162
    const left = centeredScrollLeft(0, 12, 300, 400, 100)
    expect(400 - left + 50).toBe(162)
  })
  it('已经滚过一段时在原来的基础上算', () => {
    expect(centeredScrollLeft(80, 0, 300, 100, 100)).toBe(80 + 100 - 100)
  })
  it('选中第一个时不滚成负数', () => {
    expect(centeredScrollLeft(0, 0, 300, 4, 60)).toBe(0)
  })
})
