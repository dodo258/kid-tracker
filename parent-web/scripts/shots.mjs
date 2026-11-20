// 用 fixtures/ 假数据把 fixtures/mockApi.ts 里 SCENARIOS 的每个场景都截一张：390×844、2 倍屏、手机模式，北京时间。
// 场景清单只有 SCENARIOS 一份（每个场景截什么、为什么，注释写在那里）：这里打开页面后从页面里读，不另抄一份
//（以前手抄一份，新场景要改两处，漏了就一直没截，比如 alerts-failed）。
// 个别场景截之前要多等一会儿或先点一下，写在下面 extraWait、BEFORE_SHOT 里。
// 用法：node scripts/shots.mjs <页面地址> <输出目录>（由 scripts/shots.sh 调用）
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { evaluate, launch, sleep, waitSettled } from './cdp.mjs'

const [url, outDir] = process.argv.slice(2)
/** 页面里读场景清单：fixtures 模式的 dev server 直接给出 mockApi.ts 这个模块（地址按页面所在的 /m/ 算） */
const SCENARIO_NAMES = `import(new URL('fixtures/mockApi.ts', location.href).href).then((m) => Object.keys(m.SCENARIOS))`
/** 收起时卡片最多占屏幕这么多（地图至少 75%） */
const PEEK_MAX = 0.25
// 卡片顶在哪、选中的圆点在哪、视口多高：查收起时地图够不够大、圆点有没有被卡片挡住
const MEASURE = `(() => {
  const sheet = document.querySelector('section[aria-label="孩子的状态"]')
  const dot = document.querySelector('.kid-selected .kid-dot')
  return [sheet ? sheet.getBoundingClientRect().top : null, dot ? dot.getBoundingClientRect().bottom : null, innerHeight, sheet ? sheet.dataset.snap : null]
})()`
/** 选点模式：图钉地址在地图停下 600 毫秒后才查，多等一会儿；stillwifi 打开后圆点滑回精度圈的中心（0.8 秒） */
const extraWait = (state) => (state.startsWith('pick') ? 1000 : state.startsWith('stillwifi') ? 900 : state.startsWith('backfill') ? 900 : 0)
/** 打开后先点几下再截：placeshared 点「删除」看共用提示；backfill 推来一批离线补传的点，截快放到一半的样子 */
const BEFORE_SHOT = {
  placeshared: `[...document.querySelectorAll('button')].find((b) => b.textContent.trim() === '删除').click()`,
  backfill: 'window.fixtureBackfill()',
  // 断网后回到前台：时钟拨到 NOW、重新拉取失败
  parentoffline: `window.fixtureGoOffline(); document.dispatchEvent(new Event('visibilitychange'))`,
  'parentoffline-dark': `window.fixtureGoOffline(); document.dispatchEvent(new Event('visibilitychange'))`,
  signoutfailed: `[...document.querySelectorAll('button')].find((b) => b.textContent.trim() === '退出登录').click()`,
  'backfill-dark': 'window.fixtureBackfill()',
}

const problems = []
const { cdp, close } = await launch()
try {
  await cdp.send('Page.enable')
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true })
  await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true })
  await cdp.send('Emulation.setTimezoneOverride', { timezoneId: 'Asia/Shanghai' })
  await cdp.send('Emulation.setLocaleOverride', { locale: 'zh-CN' })
  await cdp.send('Page.navigate', { url })
  await waitSettled(cdp)
  const states = await evaluate(cdp, SCENARIO_NAMES)
  if (!Array.isArray(states) || states.length === 0) throw new Error('从页面里没读到场景清单（fixtures/mockApi.ts 的 SCENARIOS）')
  for (const state of states) {
    await cdp.send('Emulation.setEmulatedMedia', {
      features: [{ name: 'prefers-color-scheme', value: state === 'dark' || state.endsWith('-dark') ? 'dark' : 'light' }],
    })
    await cdp.send('Page.navigate', { url: `${url}?state=${state}` })
    await waitSettled(cdp)
    if (BEFORE_SHOT[state]) {
      await evaluate(cdp, BEFORE_SHOT[state])
      await sleep(300)
    }
    await sleep(400 + extraWait(state)) // 地图移动和卡片高度的最后一帧
    const { data } = await cdp.send('Page.captureScreenshot', { format: 'png' })
    writeFileSync(join(outDir, `parent-${state}.png`), Buffer.from(data, 'base64'))
    const [sheetTop, dotBottom, height, snap] = await evaluate(cdp, MEASURE)
    const note = sheetTop === null ? '' : `（卡片${snap ?? ''}占 ${Math.round((1 - sheetTop / height) * 100)}%${dotBottom === null ? '' : `，圆点底 ${Math.round(dotBottom)} / 卡片顶 ${Math.round(sheetTop)}`}）`
    console.log(`parent-${state}.png${note}`)
    if (state.startsWith('sheet-')) {
      if (snap === 'peek' && 1 - sheetTop / height > PEEK_MAX) problems.push(`${state}：收起时卡片占 ${Math.round((1 - sheetTop / height) * 100)}%，超过 ${PEEK_MAX * 100}%`)
      if (dotBottom === null || dotBottom > sheetTop) problems.push(`${state}：圆点被卡片挡住或没有圆点`)
    }
  }
} finally {
  await close()
}
if (problems.length > 0) {
  console.error(problems.join('\n'))
  process.exit(1)
}
