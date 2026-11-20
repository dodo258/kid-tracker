// 状态卡两档（方案 2026-11-18-parent-web-polish「卡片」）：收起（只露状态三行）、展开（加两个按钮和四个入口）。
// 原来全开里的东西都挪进了二级页，卡片不需要第三档。这里只有纯函数：拖动松手停在哪一档、点把手切到哪一档、滚轮往哪挪一档。
// 高度由界面量好传进来。

import type { Snap } from '../api/types'

export type { Snap }

export const SNAPS: readonly Snap[] = ['peek', 'open']

/** 各档露出的高度（像素） */
export type SnapHeights = Record<Snap, number>

/** 松手时按现在的速度再滑这么久（毫秒）的位置选最近的一档：轻轻一甩也能到另一档 */
const PROJECT_MS = 180
/** 甩得比这快（像素/毫秒）至少往甩的方向挪一档 */
const FLING = 0.6

/** 往上（dir=1）或往下（dir=-1）挪一档，到头不动 */
export function stepSnap(from: Snap, dir: 1 | -1): Snap {
  const i = SNAPS.indexOf(from) + dir
  return SNAPS[Math.min(SNAPS.length - 1, Math.max(0, i))]
}

/** 点把手：收起 ↔ 展开 */
export function tapSnap(from: Snap): Snap {
  return from === 'peek' ? 'open' : 'peek'
}

/**
 * 拖动松手：height 是松手时露出的高度，velocity 是松手前的速度（像素/毫秒，往上拉为正），from 是开始拖时的档。
 * 按投出去的位置选最近的一档；甩得快但最近的一档还是原来那档时，往甩的方向挪一档。
 * 两档一样高（内容很少）时取收起
 */
export function settleSnap(heights: SnapHeights, height: number, velocity: number, from: Snap): Snap {
  const projected = height + velocity * PROJECT_MS
  let best: Snap = 'peek'
  for (const s of SNAPS) {
    if (Math.abs(heights[s] - projected) < Math.abs(heights[best] - projected)) best = s
  }
  if (best === from && Math.abs(velocity) > FLING) {
    const next = stepSnap(from, velocity > 0 ? 1 : -1)
    if (heights[next] !== heights[from]) return next
  }
  return best
}
