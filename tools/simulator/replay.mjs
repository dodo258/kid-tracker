// 回放检查的浏览器这一半：在无头 Chrome 里打开家长页的回放模式（fixtures 的 state=replay），逐帧记下圆点、记下状态行每次变化，按场景时间截图。
// 不改家长页：回放模式本来读 fixtures/p20.json，这里用 DevTools 的 Fetch 拦截把这个模块换成场景导出的点（Traccar /api/positions 的格式）。
// 判断通过不通过在 Python（checks.py）里做，这里只采数据。
// 用法：node replay.mjs <job.json>，由 sim.py replay 生成 job：
//   { url, cdp: <parent-web/scripts/cdp.mjs 的路径>, runs: [{ name, fixture, from, to, speed, shots: [场景时钟毫秒…], out }] }
// 每个 run 写 <out>/trace.json：{ frames: [[时钟毫秒, 纬度, 经度, 在路上的几种]…], status: [[时钟毫秒, 状态行文字]…], shots: [文件名…],
//   cover: { samples: 查了几次, covered: [[时钟毫秒, 圆点底, 卡片顶, 真实毫秒]…] }（选中的圆点有没有被底部状态卡挡住，每 200 毫秒查一次） }
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

const job = JSON.parse(readFileSync(process.argv[2], 'utf8'))
const { evaluate, launch, sleep } = await import(pathToFileURL(job.cdp).href)

// 页面里：__dotTrace 每推一帧就记下（时钟, performance.now），据此换算任意时刻的回放时钟；状态行一变就记一条
const INSTRUMENT = (speed, to) => `(() => {
  const frames = []
  const push = frames.push.bind(frames)
  frames.push = (f) => { window.__calib = [f[0], performance.now()]; return push(f) }
  window.__dotTrace = frames
  window.__clock = () => window.__calib ? Math.min(${to}, window.__calib[0] + (performance.now() - window.__calib[1]) * ${speed}) : null
  window.__status = []
  window.__cover = { samples: 0, covered: [] }
  let last
  const tick = () => {
    // 状态行：卡片里第一个段落（名字下面那行）
    const sheet = document.querySelector('section[aria-label="孩子的状态"]')
    const p = sheet ? sheet.querySelector('p') : null
    const text = p ? p.textContent : null
    if (text !== last) { last = text; window.__status.push([window.__clock(), text]) }
    const dot = document.querySelector('.kid-selected .kid-dot')
    if (sheet && dot) {
      const bottom = dot.getBoundingClientRect().bottom
      const top = sheet.getBoundingClientRect().top
      window.__cover.samples++
      if (bottom > top) window.__cover.covered.push([window.__clock(), Math.round(bottom), Math.round(top), Math.round(performance.now())])
    }
  }
  new MutationObserver(tick).observe(document.body, { subtree: true, childList: true, characterData: true })
  setInterval(tick, 200)
  tick()
})()`

const { cdp, close } = await launch()
let current = null
// 底图瓦片请求：截图前等它们落定（加速回放时跟随镜头走得快，前沿的瓦片来不及下载，截出来是灰块，见 shots.py）
const TILE_URL = /autonavi\.com|gtimg\.com|openstreetmap\.org/
const tiles = new Set()
cdp.on('Network.requestWillBeSent', ({ requestId, request }) => {
  if (TILE_URL.test(request.url)) tiles.add(requestId)
})
cdp.on('Network.loadingFinished', ({ requestId }) => tiles.delete(requestId))
cdp.on('Network.loadingFailed', ({ requestId }) => tiles.delete(requestId))
/** 没有在下载的瓦片、并且安静了 400 毫秒（瓦片淡入 300 毫秒）；最多等 maxMs */
async function tilesSettled(maxMs) {
  const end = Date.now() + maxMs
  let quiet = null
  while (Date.now() < end) {
    if (tiles.size > 0) quiet = null
    else if (quiet === null) quiet = Date.now()
    else if (Date.now() - quiet >= 400) return true
    await sleep(25)
  }
  return false
}
async function shoot(dir, file) {
  const settled = await tilesSettled(4000)
  const [clock] = await evaluate(cdp, '[window.__clock()]')
  const { data } = await cdp.send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(join(dir, file), Buffer.from(data, 'base64'))
  return { clock, settled }
}
cdp.on('Fetch.requestPaused', async ({ requestId }) => {
  const body = `export default JSON.parse(${JSON.stringify(current)})\n`
  await cdp.send('Fetch.fulfillRequest', {
    requestId,
    responseCode: 200,
    responseHeaders: [{ name: 'Content-Type', value: 'text/javascript' }, { name: 'Cache-Control', value: 'no-store' }],
    body: Buffer.from(body).toString('base64'),
  })
})
try {
  await cdp.send('Page.enable')
  await cdp.send('Network.enable')
  await cdp.send('Network.setCacheDisabled', { cacheDisabled: true })
  await cdp.send('Fetch.enable', { patterns: [{ urlPattern: '*/fixtures/p20.json*', requestStage: 'Request' }] })
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true })
  await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true })
  await cdp.send('Emulation.setTimezoneOverride', { timezoneId: 'Asia/Shanghai' })
  await cdp.send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: 'light' }] })
  for (const run of job.runs) {
    current = readFileSync(run.fixture, 'utf8')
    const q = new URLSearchParams({ state: 'replay', from: run.from, to: run.to, speed: String(run.speed) })
    // 圆点要等地图加载完（底图是在线瓦片，偶尔慢）：最多等 45 秒，没出来就重新打开一次，还不行截图留证再报错
    let ready = false
    for (let attempt = 0; attempt < 2 && !ready; attempt++) {
      await cdp.send('Page.navigate', { url: `${job.url}?${q}` })
      for (let i = 0; i < 450 && !ready; i++) {
        ready = await evaluate(cdp, `!!document.querySelector('.kid-selected')`)
        if (!ready) await sleep(100)
      }
    }
    if (!ready) {
      const { data } = await cdp.send('Page.captureScreenshot', { format: 'png' })
      writeFileSync(join(run.out, `${run.name}-not-ready.png`), Buffer.from(data, 'base64'))
      throw new Error(`${run.name}：页面两次 45 秒都没出圆点，截图 ${run.name}-not-ready.png`)
    }
    await evaluate(cdp, INSTRUMENT(run.speed, new Date(run.to).getTime()))
    const shots = [...run.shots].sort((a, b) => a - b)
    const files = []
    const deadline = Date.now() + (new Date(run.to) - new Date(run.from)) / run.speed + 60_000
    for (;;) {
      const [clock, done] = await evaluate(cdp, '[window.__clock(), window.replayDone === true]')
      while (shots.length && clock !== null && clock >= shots[0]) {
        const t = shots.shift()
        // 文件名用北京时间的时分秒（预定的截图时刻）；clock 是等瓦片以后真正截图时的回放时钟
        const name = `${run.name}-${new Date(t + 8 * 3600_000).toISOString().slice(11, 19).replaceAll(':', '')}.png`
        const { clock: at, settled } = await shoot(run.out, name)
        files.push({ clock: at ?? t, file: name, settled })
      }
      if (done) break
      if (Date.now() > deadline) throw new Error(`${run.name}：回放超时`)
      await sleep(40)
    }
    const end = await shoot(run.out, `${run.name}-end.png`)
    files.push({ clock: new Date(run.to).getTime(), file: `${run.name}-end.png`, settled: end.settled })
    const [frames, status, cover] = await evaluate(cdp, '[window.__dotTrace, window.__status, window.__cover]')
    writeFileSync(join(run.out, 'trace.json'), JSON.stringify({ frames, status, shots: files, cover }))
    console.log(`${run.name}：${frames.length} 帧，状态行变了 ${status.length} 次，截图 ${files.length} 张`)
  }
} finally {
  await close()
}
