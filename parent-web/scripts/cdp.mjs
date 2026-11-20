// 起一个无头 Chrome，只用 Node 自带的 WebSocket 走 Chrome DevTools 协议，不装 puppeteer。shots.mjs、gestures.mjs 共用。
import { spawn } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

export const CHROME = process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function pageSocketUrl(port) {
  for (let i = 0; i < 60; i++) {
    try {
      const res = await fetch(`http://127.0.0.1:${port}/json/list`, { signal: AbortSignal.timeout(1000) })
      const page = (await res.json()).find((t) => t.type === 'page')
      if (page) return page.webSocketDebuggerUrl
    } catch {
      // Chrome 还没起来
    }
    await sleep(250)
  }
  throw new Error('Chrome 调试端口没起来')
}

function connect(wsUrl) {
  const ws = new WebSocket(wsUrl)
  let seq = 0
  const pending = new Map()
  const listeners = new Map()
  ws.onmessage = (e) => {
    const msg = JSON.parse(e.data)
    if (msg.method) {
      for (const fn of listeners.get(msg.method) ?? []) fn(msg.params)
      return
    }
    const p = pending.get(msg.id)
    if (!p) return
    pending.delete(msg.id)
    if (msg.error) p.reject(new Error(`${p.method}: ${msg.error.message}`))
    else p.resolve(msg.result)
  }
  const send = (method, params = {}) =>
    new Promise((resolve, reject) => {
      const id = ++seq
      pending.set(id, { resolve, reject, method })
      ws.send(JSON.stringify({ id, method, params }))
      setTimeout(() => {
        if (pending.delete(id)) reject(new Error(`${method} 超时`))
      }, 20000)
    })
  /** 订阅 CDP 事件（如 Runtime.consoleAPICalled） */
  const on = (method, fn) => listeners.set(method, [...(listeners.get(method) ?? []), fn])
  return new Promise((resolve, reject) => {
    ws.onopen = () => resolve({ send, on, close: () => ws.close() })
    ws.onerror = () => reject(new Error('连不上 Chrome DevTools'))
  })
}

/** 起 Chrome 并连上第一个标签页；用完调用 close() */
export async function launch() {
  const port = 9300 + (process.pid % 500)
  const profile = mkdtempSync(join(tmpdir(), 'parent-web-cdp-'))
  const chrome = spawn(CHROME, [
    '--headless', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', // 地图用 WebGL：无头时用软件渲染
    '--hide-scrollbars', '--no-first-run', '--no-default-browser-check',
    `--user-data-dir=${profile}`, `--remote-debugging-port=${port}`, '--lang=zh-CN', 'about:blank',
  ], { stdio: 'ignore' })
  const close = async () => {
    chrome.kill()
    await sleep(300)
    rmSync(profile, { recursive: true, force: true })
  }
  try {
    const cdp = await connect(await pageSocketUrl(port))
    return { cdp, close: async () => { cdp.close(); await close() } }
  } catch (err) {
    await close()
    throw err
  }
}

/** 在页面里求值，返回结果 */
export async function evaluate(cdp, expression) {
  const { result, exceptionDetails } = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (exceptionDetails) throw new Error(`页面里出错：${exceptionDetails.exception?.description ?? exceptionDetails.text}`)
  return result.value
}

// 等页面画完：地图容器带 data-idle（MapLibre 的 idle：瓦片都加载完、没有动画），登录页没有地图就看根节点（最多 10 秒，瓦片加载不出来也照样往下走）
const SETTLED = `(() => {
  if (document.querySelector('#root > :not(.boot-loading)') === null) return false
  const map = document.querySelector('[aria-label="地图"]')
  return map === null || map.dataset.idle === '1'
})()`

export async function waitSettled(cdp) {
  let stable = 0
  for (let i = 0; i < 50 && stable < 2; i++) {
    await sleep(200)
    stable = (await evaluate(cdp, SETTLED)) ? stable + 1 : 0
  }
}
