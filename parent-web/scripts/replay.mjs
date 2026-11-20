// 无头 Chrome 里用真实页面回放 P20 的真实上报（fixtures/p20.json，坐标已平移；假后端见 fixtures/replay.ts），30 倍速：
// 逐帧记下选中圆点的位置（页面在 fixtures 模式下写 window.__dotTrace），查
//   1. 圆点不瞬移：相邻两帧按回放时钟算不超过每秒 400 米（motion/player 的速度上限）；
//   2. 不动时不跳：几段屋里 Wi-Fi 点来回漂的时间里，圆点一共挪了不到 1 米；
// 每段每 3 秒截一张图（390×844，浅色），写到输出目录 replay-<段名>-<序号>.png，逐帧记录写 replay-<段名>-trace.json，结果写 replay.json。
// 用法：node scripts/replay.mjs <页面地址> <输出目录>（由 scripts/replay.sh 调用）
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { evaluate, launch, sleep } from './cdp.mjs'

const [url, outDir] = process.argv.slice(2)
const SPEED = 30
/** motion/player 的 SPEED_CAP_M_PER_MS（每秒 400 米） */
const CAP_M_PER_MS = 0.4
const SHOT_EVERY_MS = 3000

/** 回放哪几段（北京时间）；still 是这一段里人没动、只有 Wi-Fi 点在精度圈里漂的时间 */
const WINDOWS = [
  // 早上在家：App 误判成移动中，每 10 秒一个 Wi-Fi/网络点来回漂；07:21 起 Wi-Fi 定位跳到几百米外
  { name: '0703-home-wifi', from: '2026-11-17T07:03:00+08:00', to: '2026-11-17T07:36:00+08:00', still: [['2026-11-17T07:05:30+08:00', '2026-11-17T07:11:00+08:00']] },
  // 傍晚出门：范围内活动 → 移动中，卫星点每 10 秒一个
  { name: '1950-out-gps', from: '2026-11-17T19:50:00+08:00', to: '2026-11-17T20:06:00+08:00', still: [] },
  // 晚上回来：Wi-Fi 点每 40 秒一个
  { name: '2113-back-wifi', from: '2026-11-17T21:13:00+08:00', to: '2026-11-17T21:19:00+08:00', still: [] },
  // 旧版 App（没有状态）出门：几分钟一个点，一次跳 1 公里
  { name: '1838-legacy-trip', from: '2026-11-16T18:38:00+08:00', to: '2026-11-16T18:48:00+08:00', still: [] },
]

function distance(a, b) {
  const rad = Math.PI / 180
  const dLat = (b[1] - a[1]) * rad
  const dLon = (b[2] - a[2]) * rad
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a[1] * rad) * Math.cos(b[1] * rad) * Math.sin(dLon / 2) ** 2
  return 6378137 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h))
}

const failures = []
function check(name, ok, detail = '') {
  console.log(`${ok ? '通过' : '失败'}  ${name}${detail ? `（${detail}）` : ''}`)
  if (!ok) failures.push(name)
}

const { cdp, close } = await launch()
const results = []
try {
  await cdp.send('Page.enable')
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true })
  await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true })
  await cdp.send('Emulation.setTimezoneOverride', { timezoneId: 'Asia/Shanghai' })
  await cdp.send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: 'light' }] })
  for (const w of WINDOWS) {
    const q = new URLSearchParams({ state: 'replay', from: w.from, to: w.to, speed: String(SPEED) })
    await cdp.send('Page.navigate', { url: `${url}?${q}` })
    for (let i = 0; i < 100 && !(await evaluate(cdp, `!!document.querySelector('.kid-selected')`)); i++) await sleep(100)
    await evaluate(cdp, 'window.__dotTrace = []')
    const realMs = (new Date(w.to) - new Date(w.from)) / SPEED + 3000
    let shot = 0
    for (let t = 0; t < realMs; t += SHOT_EVERY_MS) {
      const { data } = await cdp.send('Page.captureScreenshot', { format: 'png' })
      writeFileSync(join(outDir, `replay-${w.name}-${String(shot++).padStart(2, '0')}.png`), Buffer.from(data, 'base64'))
      await sleep(SHOT_EVERY_MS)
      if (await evaluate(cdp, 'window.replayDone === true')) break
    }
    const trace = await evaluate(cdp, 'window.__dotTrace')
    // 逐帧原始记录也存下来（[回放时钟毫秒, 纬度, 经度, 在路上的几种]），没过时拿它查
    writeFileSync(join(outDir, `replay-${w.name}-trace.json`), JSON.stringify(trace))
    // 1. 相邻帧
    let worst = { over: -Infinity, i: 0, meters: 0 }
    for (let i = 1; i < trace.length; i++) {
      const d = distance(trace[i - 1], trace[i])
      const over = d - (CAP_M_PER_MS * Math.max(trace[i][0] - trace[i - 1][0], 1) + 1)
      if (over > worst.over) worst = { over, i, meters: d }
    }
    const around = trace.slice(Math.max(0, worst.i - 3), worst.i + 3).map((f, k, a) =>
      k === 0 ? `${new Date(f[0]).toISOString().slice(11, 23)}` : `+${f[0] - a[k - 1][0]}ms ${distance(a[k - 1], f).toFixed(1)}m ${f[3]}`)
    check(`${w.name}：${trace.length} 帧，相邻帧不超过每秒 400 米`, trace.length > 20 && worst.over <= 0,
      `最接近上限的一帧挪了 ${worst.meters.toFixed(1)} 米；前后几帧 ${around.join('，')}`)
    // 2. 不动时不跳
    for (const [from, to] of w.still) {
      const s = trace.filter((f) => f[0] >= new Date(from).getTime() && f[0] < new Date(to).getTime())
      let sum = 0
      for (let i = 1; i < s.length; i++) sum += distance(s[i - 1], s[i])
      check(`${w.name}：${from.slice(11, 16)}–${to.slice(11, 16)} 屋里 Wi-Fi 漂移，圆点不动`, s.length > 10 && sum < 1, `${s.length} 帧，一共挪了 ${sum.toFixed(2)} 米`)
    }
    results.push({ window: w.name, frames: trace.length, worstFrameMeters: worst.meters, screenshots: shot })
  }
} finally {
  await close()
}
writeFileSync(join(outDir, 'replay.json'), `${JSON.stringify(results, null, 2)}\n`)
if (failures.length) {
  console.log(`\n${failures.length} 项没过`)
  process.exit(1)
}
console.log('\n真实数据回放都通过')
