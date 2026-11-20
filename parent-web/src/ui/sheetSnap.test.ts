import { describe, expect, it } from 'vitest'
import { settleSnap, stepSnap, tapSnap, type SnapHeights } from './sheetSnap'

const H: SnapHeights = { peek: 130, open: 420 }

describe('两档抽屉：松手停在哪一档', () => {
  it('慢慢拖：停在离得最近的一档', () => {
    expect(settleSnap(H, 160, 0, 'peek')).toBe('peek')
    expect(settleSnap(H, 300, 0, 'peek')).toBe('open')
    expect(settleSnap(H, 250, 0, 'open')).toBe('peek')
    expect(settleSnap(H, 380, 0, 'open')).toBe('open')
  })
  it('按速度往前投：轻轻往上甩就展开、往下甩就收起', () => {
    expect(settleSnap(H, 200, 1.2, 'peek')).toBe('open')
    expect(settleSnap(H, 360, -1.2, 'open')).toBe('peek')
  })
  it('甩得快但没离开原档：往甩的方向挪一档；慢的不挪', () => {
    expect(settleSnap(H, 135, 0.8, 'peek')).toBe('open')
    expect(settleSnap(H, 415, -0.8, 'open')).toBe('peek')
    expect(settleSnap(H, 135, 0.2, 'peek')).toBe('peek')
  })
  it('到头了不再挪', () => {
    expect(settleSnap(H, 420, 3, 'open')).toBe('open')
    expect(settleSnap(H, 130, -3, 'peek')).toBe('peek')
  })
  it('内容很少、两档一样高：停在收起', () => {
    const flat = { peek: 130, open: 130 }
    expect(settleSnap(flat, 130, 0, 'peek')).toBe('peek')
    expect(settleSnap(flat, 135, 1, 'peek')).toBe('peek')
  })
})

describe('点把手、滚轮', () => {
  it('点把手在收起和展开之间切', () => {
    expect(tapSnap('peek')).toBe('open')
    expect(tapSnap('open')).toBe('peek')
  })
  it('一次挪一档，到头不动', () => {
    expect(stepSnap('peek', 1)).toBe('open')
    expect(stepSnap('open', 1)).toBe('open')
    expect(stepSnap('open', -1)).toBe('peek')
    expect(stepSnap('peek', -1)).toBe('peek')
  })
})
