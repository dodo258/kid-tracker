// 地图上一台设备的 DOM：名字首字的圆点（外圈颜色 = 颜色档，正常时呼吸）、外圈上的方向小三角、下面的名字小标签；
// 选中的那台另有一个精度圈（半透明圆，半径按缩放级别换成像素）。位置由 useKids 逐帧设，这里只管样子。
import { Marker, type Map as MlMap } from 'maplibre-gl'
import type { Freshness } from '../domain/presence'
import type { LngLat } from './geometry'

const DOT = 36
const SELECTED_DOT = 48

export type KidLook = { name: string; freshness: Freshness; selected: boolean }

export type KidView = {
  /** 换名字、颜色档、选中 */
  setLook(look: KidLook): void
  moveTo(at: LngLat): void
  /** 箭头在屏幕上转多少度；null 不画 */
  setArrow(screenDeg: number | null): void
  remove(): void
}

/** 点击落在孩子的圆点或名字上 */
export function isOnKid(target: EventTarget | null): boolean {
  return target instanceof Element && target.closest('.kid') !== null
}

export function createKidView(map: MlMap, look: KidLook, at: LngLat, onClick: () => void): KidView {
  const wrap = document.createElement('div')
  wrap.className = 'kid-icon'
  wrap.style.width = '120px'
  const box = document.createElement('div')
  const dot = document.createElement('div')
  dot.className = 'kid-dot'
  const initial = document.createElement('span')
  dot.append(initial)
  // 行进方向：圆点外圈上的小三角，转向由 useKids 逐帧设 transform
  const arrow = document.createElement('div')
  arrow.className = 'kid-heading'
  arrow.hidden = true
  dot.append(arrow)
  const label = document.createElement('div')
  label.className = 'kid-label'
  box.append(dot, label)
  wrap.append(box)
  // 不让点圆点冒泡成点地图（地图上的点击会打开圆点下面的地点卡片）
  const select = (e: MouseEvent) => {
    e.stopPropagation()
    onClick()
  }
  dot.addEventListener('click', select)
  label.addEventListener('click', select)

  const marker = new Marker({ element: wrap, anchor: 'top' }).setLngLat(at).addTo(map)
  let shown = ''
  const setLook = (l: KidLook) => {
    const key = `${l.name}|${l.freshness}|${l.selected}`
    if (key === shown) return
    shown = key
    const size = l.selected ? SELECTED_DOT : DOT
    wrap.style.height = `${size + 26}px`
    wrap.style.zIndex = l.selected ? '2' : '1'
    box.className = `kid kid-${l.freshness}${l.selected ? ' kid-selected' : ''}`
    box.title = l.name
    // 名字用 textContent 填，不拼 HTML
    initial.textContent = Array.from(l.name)[0] ?? ''
    label.textContent = l.name
    marker.setOffset([0, -size / 2])
  }
  setLook(look)
  let arrowDeg: number | null = null
  return {
    setLook,
    moveTo: (p) => marker.setLngLat(p),
    setArrow(deg) {
      if (deg === arrowDeg) return
      arrowDeg = deg
      arrow.hidden = deg === null
      if (deg !== null) arrow.style.transform = `rotate(${deg}deg)`
    },
    remove: () => marker.remove(),
  }
}

export type AccuracyView = {
  /** 圆心、半径（像素）、颜色档；半径 0 不画 */
  update(at: LngLat, radiusPx: number, freshness: Freshness): void
  remove(): void
}

/** 选中那台的精度圈：压在所有圆点下面 */
export function createAccuracyView(map: MlMap, at: LngLat): AccuracyView {
  const el = document.createElement('div')
  el.className = 'kid-accuracy'
  el.style.zIndex = '0'
  const marker = new Marker({ element: el, anchor: 'center' }).setLngLat(at).addTo(map)
  let shown = ''
  return {
    update(p, radiusPx, freshness) {
      marker.setLngLat(p)
      const size = Math.round(radiusPx * 2)
      const key = `${size}|${freshness}`
      if (key === shown) return
      shown = key
      el.hidden = size < 2
      el.style.width = el.style.height = `${size}px`
      // 只换颜色档的类：MapLibre 自己在这个元素上加了定位用的类，不能整个覆盖
      el.classList.remove('kid-fresh', 'kid-stale', 'kid-lost')
      el.classList.add(`kid-${freshness}`)
    },
    remove: () => marker.remove(),
  }
}
