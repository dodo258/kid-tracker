// 回到前台后的收拾：iPhone 上从主屏幕打开的全屏网页放到后台再回来，WebKit 有时把合成层上的 transform 动画丢了，
// 只剩透明度在变，圆点的呼吸（kid-breathe：光晕放大、变淡）就成了一闪一闪，大退重开才好。回到前台时把呼吸动画全部重起一遍。

/** 地图上孩子圆点和状态句小圆点共用的呼吸动画（index.css 的 @keyframes） */
export const BREATHE = 'kid-breathe'

/**
 * 页面回到前台时调 cb：切回来（visibilitychange 变成 visible），或者从往返缓存里恢复（pageshow 带 persisted）。
 * 第一次打开页面的 pageshow 不算。返回取消订阅的函数
 */
export function onPageResume(cb: () => void): () => void {
  const onVisible = () => {
    if (document.visibilityState === 'visible') cb()
  }
  const onShow = (e: PageTransitionEvent) => {
    if (e.persisted) cb()
  }
  document.addEventListener('visibilitychange', onVisible)
  window.addEventListener('pageshow', onShow)
  return () => {
    document.removeEventListener('visibilitychange', onVisible)
    window.removeEventListener('pageshow', onShow)
  }
}

/** 页面上所有呼吸动画从头重起（取消再播放，重新交给合成层）；老 Safari 没有 getAnimations 就不管 */
export function restartBreathing(): void {
  if (typeof document.getAnimations !== 'function') return
  for (const a of document.getAnimations()) {
    if (!('animationName' in a) || a.animationName !== BREATHE) continue
    a.cancel()
    a.play()
  }
}

/**
 * 回到前台就重起呼吸。等页面真的开始画（下一帧）再重起：切回来的那一刻 WebKit 可能还没把图层恢复好。
 * 整个页面装一次（main.tsx），返回取消的函数
 */
export function watchBreathing(): () => void {
  let frame = 0
  const stop = onPageResume(() => {
    cancelAnimationFrame(frame)
    frame = requestAnimationFrame(() => {
      frame = 0
      restartBreathing()
    })
  })
  return () => {
    cancelAnimationFrame(frame)
    stop()
  }
}
