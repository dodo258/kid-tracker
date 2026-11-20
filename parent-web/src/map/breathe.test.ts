// @vitest-environment happy-dom
// 回到前台重起呼吸动画（iPhone 主屏幕网页放后台再回来，呼吸变成一闪一闪）
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import css from '../index.css?raw'
import { BREATHE, onPageResume, restartBreathing, watchBreathing } from './breathe'

type Fake = { animationName?: string; log: string[]; cancel(): void; play(): void }

function fakeAnimation(name: string | undefined): Fake {
  const log: string[] = []
  const a: Fake = { log, cancel: () => log.push('cancel'), play: () => log.push('play') }
  if (name !== undefined) a.animationName = name
  return a
}

let visibility: DocumentVisibilityState = 'visible'
let frames: FrameRequestCallback[] = []
let animations: Fake[] = []

function setVisibility(v: DocumentVisibilityState) {
  visibility = v
  document.dispatchEvent(new Event('visibilitychange'))
}

function pageshow(persisted: boolean) {
  const e = new Event('pageshow')
  Object.defineProperty(e, 'persisted', { value: persisted })
  window.dispatchEvent(e)
}

function runFrames() {
  const due = frames
  frames = []
  for (const f of due) f(performance.now())
}

beforeEach(() => {
  visibility = 'visible'
  frames = []
  animations = [fakeAnimation(BREATHE), fakeAnimation('sub-page-in'), fakeAnimation(undefined), fakeAnimation(BREATHE)]
  vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visibility)
  vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => frames.push(cb))
  vi.stubGlobal('cancelAnimationFrame', () => {})
  Object.defineProperty(document, 'getAnimations', { configurable: true, value: () => animations })
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  Reflect.deleteProperty(document, 'getAnimations')
})

const restarted = () => animations.map((a) => a.log.join(','))

describe('restartBreathing', () => {
  it('只把呼吸动画取消再播放，别的动画不碰', () => {
    restartBreathing()
    expect(restarted()).toEqual(['cancel,play', '', '', 'cancel,play'])
  })
  it('浏览器没有 getAnimations 时什么都不做', () => {
    Reflect.deleteProperty(document, 'getAnimations')
    expect(() => restartBreathing()).not.toThrow()
  })
})

describe('onPageResume', () => {
  it('切回前台、从往返缓存恢复时调；切到后台、第一次打开不调；取消订阅后不再调', () => {
    const cb = vi.fn()
    const stop = onPageResume(cb)
    setVisibility('hidden')
    pageshow(false)
    expect(cb).not.toHaveBeenCalled()
    setVisibility('visible')
    pageshow(true)
    expect(cb).toHaveBeenCalledTimes(2)
    stop()
    setVisibility('hidden')
    setVisibility('visible')
    expect(cb).toHaveBeenCalledTimes(2)
  })
})

describe('watchBreathing', () => {
  it('放后台 30 秒再回来：等页面开始画的那一帧把呼吸重起一遍', () => {
    const stop = watchBreathing()
    setVisibility('hidden')
    runFrames()
    expect(restarted()).toEqual(['', '', '', ''])
    setVisibility('visible')
    // 切回来的那一刻还没重起，下一帧才重起
    expect(restarted()).toEqual(['', '', '', ''])
    runFrames()
    expect(restarted()).toEqual(['cancel,play', '', '', 'cancel,play'])
    stop()
  })
  it('从往返缓存恢复（pageshow persisted）也重起', () => {
    const stop = watchBreathing()
    pageshow(true)
    runFrames()
    expect(restarted()).toEqual(['cancel,play', '', '', 'cancel,play'])
    stop()
  })
})

describe('呼吸关键帧', () => {
  const block = css.match(/@keyframes kid-breathe \{([\s\S]*?)\n\}/)?.[1] ?? ''
  const opacities = [...block.matchAll(/opacity: ([\d.]+)/g)].map((m) => Number(m[1]))
  it('每圈开头透明度是 0（光晕从 0 淡入），不会从看不见一下跳到半透明', () => {
    expect(block).not.toBe('')
    expect(opacities[0]).toBe(0)
    expect(opacities.at(-1)).toBe(0)
    expect(Math.max(...opacities)).toBeGreaterThan(0.3)
  })
})
