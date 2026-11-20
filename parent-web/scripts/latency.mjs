// 由 scripts/latency.sh 调用：打开页面（令牌写进 localStorage），往本机 Traccar 的 OsmAnd 端口灌点，
// 计时到状态卡上出现这个点的速度。每次换一个速度，灌 5 次，最慢一次超过 3 秒就退出 1。
import { evaluate, launch, sleep, waitSettled } from './cdp.mjs'

const [url] = process.argv.slice(2)
const { TRACCAR_URL, OSMAND_URL, TRACCAR_TOKEN, DEVICE } = process.env
const ROUNDS = 5
const LIMIT_MS = 3000

/** 灌一个移动中的点：速度 [kmh]，现在的时间 */
async function push(kmh, i) {
  const params = new URLSearchParams({
    id: DEVICE,
    lat: String(30.2744 + i * 0.0003),
    lon: String(120.1406 + i * 0.0003),
    timestamp: String(Math.floor(Date.now() / 1000)),
    speed: String(kmh / 1.852),
    bearing: '45',
    accuracy: '8',
    state: 'moving',
  })
  const res = await fetch(`${OSMAND_URL}/?${params}`, { method: 'POST', signal: AbortSignal.timeout(5000) })
  if (!res.ok) throw new Error(`OsmAnd 回了 ${res.status}`)
}

// 页面打开时选中这台（存在 localStorage 的「上次看的设备」）
const devices = await (await fetch(`${TRACCAR_URL}/api/devices`, {
  headers: { Authorization: `Bearer ${TRACCAR_TOKEN}` },
  signal: AbortSignal.timeout(5000),
})).json()
const device = devices.find((d) => d.uniqueId === DEVICE)
if (!device) {
  console.log(`这个账号看不到设备 ${DEVICE}`)
  process.exit(125)
}

const { cdp, close } = await launch()
let failed = false
try {
  await cdp.send('Page.enable')
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true })
  // 页面里记下每条 WebSocket 消息到达的时间，分得清慢在服务器推送还是页面渲染
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `window.__socketAt = []; const W = window.WebSocket; window.WebSocket = class extends W {
      constructor(...a) { super(...a); this.addEventListener('message', (e) => { if (String(e.data).includes('positions')) window.__socketAt.push(Date.now()) }) } }`,
  })
  await cdp.send('Page.navigate', { url })
  await sleep(500)
  await evaluate(cdp, `localStorage.setItem('parent-web.token', ${JSON.stringify(TRACCAR_TOKEN)})`)
  await evaluate(cdp, `localStorage.setItem('parent-web.device', '${device.id}')`)
  await cdp.send('Page.navigate', { url })
  await waitSettled(cdp)
  // 等 WebSocket 连上：先灌一个点，看到它再开始计时
  await push(3, 0)
  for (let i = 0; i < 50 && !(await evaluate(cdp, `document.body.innerText.includes('实时')`)); i++) await sleep(200)
  const times = []
  for (let i = 1; i <= ROUNDS; i++) {
    const kmh = 20 + i * 7
    // 页面里用 MutationObserver 记下字出现的时刻（不受这边轮询的延迟影响）
    await evaluate(cdp, `window.__shownAt = 0; window.__socketAt = []; new MutationObserver((_, o) => {
      if (document.body.innerText.includes('${kmh} km/h')) { window.__shownAt = Date.now(); o.disconnect() }
    }).observe(document.body, { subtree: true, childList: true, characterData: true })`)
    const start = Date.now()
    await push(kmh, i)
    let shownAt = 0
    while (Date.now() - start < 10_000 && !shownAt) {
      await sleep(100)
      shownAt = await evaluate(cdp, 'window.__shownAt')
    }
    const socketAt = (await evaluate(cdp, 'window.__socketAt'))[0]
    const ms = shownAt ? shownAt - start : Infinity
    times.push(ms)
    const via = socketAt ? `（推送到达 ${socketAt - start} 毫秒）` : ''
    console.log(`第 ${i} 个点：${shownAt ? `${ms} 毫秒` : '10 秒内没出现'}${via}`)
    await sleep(1000)
  }
  if (times.includes(Infinity)) console.log(`页面上的字：${await evaluate(cdp, 'document.body.innerText')}`)
  const worst = Math.max(...times)
  failed = worst > LIMIT_MS
  console.log(`最慢 ${worst} 毫秒，${failed ? '超过' : '在'} ${LIMIT_MS} 毫秒${failed ? '' : '以内'}`)
} finally {
  await close()
}
process.exit(failed ? 1 : 0)
