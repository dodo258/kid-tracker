// 顶部切换器：把选中的那个滚到中间。自己算 scrollLeft，不用 scrollIntoView（它会连带滚动外层 overflow:hidden 的页面）。

/** 横向滚动容器要滚到多少，选中项才在正中（浏览器会把超出两头的值夹到能滚的范围） */
export function centeredScrollLeft(scrollLeft: number, boxLeft: number, boxWidth: number, itemLeft: number, itemWidth: number): number {
  return Math.max(0, scrollLeft + (itemLeft - boxLeft) - (boxWidth - itemWidth) / 2)
}
