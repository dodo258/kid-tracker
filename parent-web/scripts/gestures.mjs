// 无头 Chrome 测地图上的手势（fixtures 假数据，normal 场景，选中的是「小宝」）：
// 手机（390×844、触摸）：点一下、按住拖动都不打开「设为常去地点」；长按打开，圆心是按的那一点；
//   从「设为常去地点」进「换个位置」，不动直接「用这里」存进去的就是孩子的 WGS-84 位置（误差 < 2 米，没转回去会差几百米）；拖动地图后「用这里」圆心跟着变。
// 电脑（1280×800、鼠标）：左键点一下不打开，右键打开。
// 常去地点第二版：点地图上的圈打开地点卡片；改范围后 PUT 的 area 圆心不变、半径是选的；改名不动范围；删除要先确认；
//   选点时调范围图钉不跑；搜地址（选只是类别的提示，走 place/text）存进去的是高德 GCJ-02 转回的 WGS-84，查地址传的是 GCJ-02；
//   「我的位置」存的是浏览器给的 WGS-84，拒绝定位时提示。
// 压测修复：孩子在地点圈里时点他的圆点（手机点、电脑点），状态卡不被换成地点卡片；
//   学校两个孩子共用时删除先提醒「也用在大宝身上」，「只对小宝取消」只去掉这一台的关联（fixture:unlinkGeofence）、不删地点。
// 实时移动（live 场景）：状态卡写「在路上 · 12 km/h」；推一条新位置（定位时间晚 4 秒），跟随时圆点一直停在正中、镜头跟着走不跳；
//   拖过地图后圆点按上报间隔匀速走过去（晚一个间隔播放：4 秒走到，走到一半时在一半的地方），中间每帧都有。
// 状态卡两档：默认收起（卡片不超过屏幕 25%）；点把手收起 ↔ 展开；往上甩展开；刷新后还是上次的档；展开时往下拉收起；
//   电脑上滚轮一次挪一档、Esc 收起。二级页：点「告警」入口整页滑入、地址带 #/alerts，点「返回」回到地图、hash 去掉；浏览器后退也能回。
//   拖走地图后右下角出「回到孩子」，点了圆点回到正中、圆钮消失。地图最多缩到看一个洲（缩放级别不低于 minZoomFor）。
// 存进去的 CIRCLE 从假后端打在控制台的 fixture:addGeofence 读，改、删、查地址分别读 fixture:updateGeofence、fixture:deleteGeofence、fixture:amapRegeo。
// 用法：node scripts/gestures.mjs <页面地址>（由 scripts/gestures.sh 调用）
import { gcj02ToWgs84 } from '../src/domain/coord.ts'
import { evaluate, launch, sleep, waitSettled } from './cdp.mjs'

const [url] = process.argv.slice(2)
/** fixtures/positions.json 里 normal 场景小宝的位置（WGS-84） */
const KID = { latitude: 30.27439, longitude: 120.140581 }
/** 每像素多少米：MapLibre 按 512 像素一块算 */
const metersPerPixel = (lat, zoom) => (40075016.686 * Math.cos((lat * Math.PI) / 180)) / (512 * 2 ** zoom)
/** 地图现在的缩放级别（MapView 在 idle 时写在地图容器上） */
const mapZoom = () => evaluate(cdp, `Number(document.querySelector('[aria-label="地图"]').dataset.zoom)`)

function distance(a, b) {
  const rad = Math.PI / 180
  const dLat = (b.latitude - a.latitude) * rad
  const dLon = (b.longitude - a.longitude) * rad
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.latitude * rad) * Math.cos(b.latitude * rad) * Math.sin(dLon / 2) ** 2
  return 6378137 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h))
}

function parseCircle(area) {
  const m = /^CIRCLE \((\S+) (\S+), (\d+)\)$/.exec(area ?? '')
  return m ? { latitude: Number(m[1]), longitude: Number(m[2]), radius: Number(m[3]) } : null
}

const failures = []
function check(name, ok, detail = '') {
  console.log(`${ok ? '通过' : '失败'}  ${name}${detail ? `（${detail}）` : ''}`)
  if (!ok) failures.push(name)
}

/** fixtures/geofences.json 里的学校（WGS-84），place 场景小宝就在它的圆心 */
const SCHOOL = { id: 12, latitude: 30.276749, longitude: 120.145126, radius: 200 }
/** fixtures/amap-place.json 第一条（GCJ-02，「经度,纬度」） */
const SEARCH_GCJ = { latitude: 30.278221, longitude: 120.147358 }
/** 「我的位置」测试给浏览器的定位（WGS-84） */
const PARENT = { latitude: 30.27139, longitude: 120.15322 }

const { cdp, close } = await launch()
const added = []
const updated = []
const deleted = []
const unlinked = []
const regeo = []
cdp.on('Runtime.consoleAPICalled', (p) => {
  const [tag, value] = [p.args[0]?.value, p.args[1]?.value]
  if (tag === 'fixture:addGeofence') added.push(value)
  if (tag === 'fixture:updateGeofence') updated.push(JSON.parse(value))
  if (tag === 'fixture:deleteGeofence') deleted.push(Number(value))
  if (tag === 'fixture:unlinkGeofence') unlinked.push(JSON.parse(value))
  if (tag === 'fixture:amapRegeo') {
    const [lon, lat] = value.split(',').map(Number)
    regeo.push({ latitude: lat, longitude: lon })
  }
})

const SHEET = (label) => `!!document.querySelector('section[aria-label="${label}"]')`
const sheetOpen = (label = '设为常去地点') => evaluate(cdp, SHEET(label))
const sheetText = () => evaluate(cdp, `document.querySelector('section[aria-label="设为常去地点"]')?.textContent ?? ''`)
const clickButton = (text) =>
  evaluate(cdp, `(() => {
    const b = [...document.querySelectorAll('button')].find((b) => b.textContent.trim() === ${JSON.stringify(text)})
    if (!b) throw new Error('没有按钮：${text}')
    b.click()
  })()`)
/** 选中孩子圆点的中心（屏幕坐标）= 孩子的位置 */
const kidPoint = () =>
  evaluate(cdp, `(() => {
    const r = document.querySelector('.kid-selected .kid-dot').getBoundingClientRect()
    return [r.x + r.width / 2, r.y + r.height / 2]
  })()`)

async function openPage(mobile, state = 'normal') {
  await cdp.send('Emulation.setDeviceMetricsOverride', mobile
    ? { width: 390, height: 844, deviceScaleFactor: 2, mobile: true }
    : { width: 1280, height: 800, deviceScaleFactor: 1, mobile: false })
  await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: mobile })
  await cdp.send('Page.navigate', { url: `${url}?state=${state}` })
  await waitSettled(cdp)
  await sleep(600)
}

async function touch(x, y, holdMs, dy = 0) {
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] })
  const steps = dy ? 10 : 0
  for (let i = 1; i <= steps; i++) {
    await sleep(20)
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y: y + (dy * i) / steps }] })
  }
  await sleep(Math.max(0, holdMs - steps * 20))
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
  await sleep(500)
}

async function mouse(x, y, button) {
  const buttons = button === 'right' ? 2 : 1
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y })
  await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button, buttons, clickCount: 1 })
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button, buttons: 0, clickCount: 1 })
  await sleep(500)
}

/** 点屏幕上一点（触摸），先确认点到的是地图本身 */
async function tapMap(x, y) {
  const onCanvas = await evaluate(cdp, `document.elementFromPoint(${x}, ${y})?.tagName === 'CANVAS'`)
  await touch(x, y, 80)
  return onCanvas
}

const cardTitle = () => evaluate(cdp, `document.querySelector('section[aria-label="常去地点"] h2')?.textContent ?? null`)
const cardText = () => evaluate(cdp, `document.querySelector('section[aria-label="常去地点"]')?.textContent ?? ''`)

/** 在输入框里打字（React 受控输入要走真实输入事件） */
async function typeInto(selector, text) {
  await evaluate(cdp, `(() => { const el = document.querySelector(${JSON.stringify(selector)}); el.focus(); el.select() })()`)
  await cdp.send('Input.insertText', { text })
}

/** fixtures/positions.json 里 live 场景小宝的位置（WGS-84，定位时间 08:41:40）；step 是往北 40 米、定位时间晚 4 秒的新点（Traccar 原样格式，速度 12 km/h，服务器刚收到） */
const LIVE = { latitude: 30.27439, longitude: 120.140581 }
const STEP_M = 40
const STEP_MS = 4000
const step = {
  id: 2001, deviceId: 1, fixTime: '2026-11-16T08:41:44.000+00:00', deviceTime: '2026-11-16T08:41:44.000+00:00', serverTime: '2026-11-16T08:42:00.000+00:00',
  latitude: LIVE.latitude + STEP_M / 111320, longitude: LIVE.longitude, speed: 6.7, accuracy: 8, attributes: { batteryLevel: 77 },
}
/** 从假后端推一条 WebSocket 位置，逐帧记下选中圆点的屏幕位置 [x, y, 毫秒]，记 ms 毫秒 */
const pushAndRecord = (position, ms) => evaluate(cdp, `new Promise((resolve) => {
  const dot = () => { const r = document.querySelector('.kid-selected .kid-dot').getBoundingClientRect(); return [r.x + r.width / 2, r.y + r.height / 2] }
  const out = [[...dot(), 0]]
  window.fixturePush({ positions: [${JSON.stringify(position)}] })
  const t0 = performance.now()
  const tick = () => { const t = performance.now() - t0; out.push([...dot(), t]); if (t < ${ms}) requestAnimationFrame(tick); else resolve(out) }
  requestAnimationFrame(tick)
})`)

/** 手指按住 (x, y) 用 ms 毫秒拖 dy 像素（每 16 毫秒一步）再松开 */
async function drag(x, y, dy, ms) {
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] })
  const steps = Math.max(2, Math.round(ms / 16))
  for (let i = 1; i <= steps; i++) {
    await sleep(16)
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y: y + (dy * i) / steps }] })
  }
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
  await sleep(500)
}
const STATUS = 'document.querySelector(\'section[aria-label="孩子的状态"]\')'
const snapNow = () => evaluate(cdp, `${STATUS}?.dataset.snap ?? null`)
/** 状态卡占屏幕的比例 */
const sheetShare = () => evaluate(cdp, `1 - ${STATUS}.getBoundingClientRect().top / innerHeight`)
/** 把手、名字（卡片里不是按钮的地方）的中心 */
const centerOf = (selector) => evaluate(cdp, `(() => { const r = document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect(); return [r.x + r.width / 2, r.y + r.height / 2] })()`)

/** 等某个条件成立（最多 3 秒） */
async function until(expr) {
  for (let i = 0; i < 30; i++) {
    if (await evaluate(cdp, expr)) return true
    await sleep(100)
  }
  return false
}

/** 进选点：设为常去地点 → 换个位置 */
async function enterPicking() {
  await clickButton('设为常去地点')
  await sleep(300)
  await clickButton('换个位置')
  await sleep(1200)
}

/** 点「家」再保存，返回存进去的圆 */
async function saveAsHome() {
  const before = added.length
  await clickButton('家')
  await clickButton('保存')
  for (let i = 0; i < 20 && added.length === before; i++) await sleep(100)
  return parseCircle(added[before])
}

/** 存进去的圆心在孩子正北或正东多少米，和屏幕上挪的像素（按当时的缩放级别换算）对得上（±10%） */
function checkOffset(name, c, pixels, zoom, axis) {
  if (!c) return check(name, false, '没有存进去')
  const expected = pixels * metersPerPixel(KID.latitude, zoom)
  const along = axis === 'north'
    ? distance(KID, { latitude: c.latitude, longitude: KID.longitude }) * Math.sign(c.latitude - KID.latitude)
    : distance(KID, { latitude: KID.latitude, longitude: c.longitude }) * Math.sign(c.longitude - KID.longitude)
  const across = axis === 'north'
    ? distance(KID, { latitude: KID.latitude, longitude: c.longitude })
    : distance(KID, { latitude: c.latitude, longitude: KID.longitude })
  check(name, Math.abs(along - expected) < expected * 0.1 && across < expected * 0.1,
    `圆心偏${axis === 'north' ? '北' : '东'} ${along.toFixed(1)} 米、横向 ${across.toFixed(1)} 米，应约 ${expected.toFixed(1)} 米`)
}

try {
  await cdp.send('Page.enable')
  await cdp.send('Runtime.enable')
  await cdp.send('Emulation.setTimezoneOverride', { timezoneId: 'Asia/Shanghai' })

  // —— 手机 ——
  await openPage(true)
  let [kx, ky] = await kidPoint()
  await touch(kx, ky - 120, 120)
  check('手机：点一下地图不打开', !(await sheetOpen()))
  await touch(kx, ky - 120, 800, 60)
  check('手机：按住拖动不打开', !(await sheetOpen()))

  await openPage(true)
  ;[kx, ky] = await kidPoint()
  let zoom = await mapZoom()
  await touch(kx, ky - 120, 800)
  check('手机：长按打开「设为常去地点」', await sheetOpen())
  check('手机：长按后圆心写的是地图上选的位置', (await sheetText()).includes('圆心是地图上选的位置'))
  checkOffset('手机：长按存进去的圆心是按的那一点', await saveAsHome(), 120, zoom, 'north')

  await openPage(true)
  await clickButton('设为常去地点')
  await sleep(300)
  check('「设为常去地点」默认圆心是孩子现在的位置', (await sheetText()).includes('圆心是小宝现在的位置'))
  await clickButton('换个位置')
  await sleep(1200)
  check('「换个位置」进选点：有图钉和「用这里」', (await sheetOpen('在地图上选位置')) && (await evaluate(cdp, `!!document.querySelector('.pick-pin')`)))
  await clickButton('用这里')
  await sleep(300)
  check('「用这里」回到「设为常去地点」', (await sheetText()).includes('圆心是地图上选的位置'))
  const still = await saveAsHome()
  check('不动直接「用这里」存的是孩子的 WGS-84 位置', still !== null && distance(KID, still) < 2, still ? `差 ${distance(KID, still).toFixed(2)} 米` : '没有存进去')

  await openPage(true)
  await clickButton('设为常去地点')
  await sleep(300)
  await clickButton('换个位置')
  await sleep(1200)
  ;[kx, ky] = await kidPoint()
  zoom = await mapZoom()
  // 手指往下拖 100 像素：图钉对准的是原来孩子北边 100 像素的地方
  await touch(kx + 40, ky + 40, 400, 100)
  await sleep(600)
  await clickButton('用这里')
  await sleep(300)
  checkOffset('选点时拖动地图，圆心跟着图钉走', await saveAsHome(), 100, zoom, 'north')

  // —— 电脑 ——
  await openPage(false)
  ;[kx, ky] = await kidPoint()
  zoom = await mapZoom()
  await mouse(kx + 150, ky, 'left')
  check('电脑：左键点一下不打开', !(await sheetOpen()))
  await mouse(kx + 150, ky, 'right')
  check('电脑：右键打开「设为常去地点」', await sheetOpen())
  checkOffset('电脑：右键存进去的圆心是点的那一点', await saveAsHome(), 150, zoom, 'east')

  // —— 状态卡两档（手机）——
  {
    await openPage(true)
    await evaluate(cdp, `localStorage.removeItem('parent-web.sheet')`)
    await openPage(true)
    const share = await sheetShare()
    check('默认收起，卡片不超过屏幕 25%', (await snapNow()) === 'peek' && share <= 0.25, `${await snapNow()}，占 ${(share * 100).toFixed(0)}%`)
    let [hx, hy] = await centerOf('[data-sheet-handle]')
    await touch(hx, hy, 80)
    check('点把手：收起 → 展开', (await snapNow()) === 'open')
    ;[hx, hy] = await centerOf('[data-sheet-handle]')
    await touch(hx, hy, 80)
    check('再点把手：展开 → 收起', (await snapNow()) === 'peek')
    let [nx, ny] = await centerOf('section[aria-label="孩子的状态"] h1')
    await drag(nx, ny, -200, 160)
    check('在卡片上往上甩：展开', (await snapNow()) === 'open')
    await openPage(true)
    check('刷新后还是上次的档（展开）', (await snapNow()) === 'open')
    ;[nx, ny] = await centerOf('section[aria-label="孩子的状态"] h1')
    await drag(nx, ny, 300, 900)
    check('慢慢往下拖到底：收起', (await snapNow()) === 'peek', await snapNow())

    // 二级页：点入口整页滑入，返回回到地图
    ;[hx, hy] = await centerOf('[data-sheet-handle]')
    await touch(hx, hy, 80)
    await sleep(400)
    const [ax, ay] = await evaluate(cdp, `(() => { const b = [...document.querySelectorAll('section[aria-label="孩子的状态"] li button')].find((x) => x.textContent.startsWith('告警')); const r = b.getBoundingClientRect(); return [r.x + r.width / 2, r.y + r.height / 2] })()`)
    await touch(ax, ay, 80)
    await sleep(400)
    const opened = await evaluate(cdp, `[!!document.querySelector('[role="dialog"][aria-label="最近 24 小时"]'), location.hash]`)
    check('点「告警」：整页打开最近 24 小时，地址带 #/alerts', opened[0] && opened[1] === '#/alerts', JSON.stringify(opened))
    const [bx, by] = await centerOf('[role="dialog"] header button')
    await touch(bx, by, 80)
    await sleep(400)
    const closed = await evaluate(cdp, `[!!document.querySelector('[role="dialog"]'), location.hash]`)
    check('点「返回」：回到地图，hash 去掉', !closed[0] && closed[1] === '', JSON.stringify(closed))
    const [sx, sy] = await evaluate(cdp, `(() => { const b = [...document.querySelectorAll('section[aria-label="孩子的状态"] li button')].find((x) => x.textContent.startsWith('设置')); const r = b.getBoundingClientRect(); return [r.x + r.width / 2, r.y + r.height / 2] })()`)
    await touch(sx, sy, 80)
    await sleep(300)
    await evaluate(cdp, 'history.back()')
    await sleep(400)
    check('设置页里浏览器后退（Safari 返回手势）：回到地图', !(await evaluate(cdp, `!!document.querySelector('[role="dialog"]')`)))

    // 回到孩子：拖走地图才出现，点了回到正中
    await openPage(true)
    check('跟着孩子时没有「回到孩子」', !(await evaluate(cdp, `!!document.querySelector('[aria-label="回到孩子"]')`)))
    await drag(195, 300, 250, 400)
    await sleep(300)
    check('拖走地图：右下角出「回到孩子」', await evaluate(cdp, `!!document.querySelector('[aria-label="回到孩子"]')`))
    const [rx, ry] = await centerOf('[aria-label="回到孩子"]')
    await touch(rx, ry, 80)
    await sleep(900)
    const [, dy] = await kidPoint()
    const sheetTop = await evaluate(cdp, `${STATUS}.getBoundingClientRect().top`)
    check('点「回到孩子」：圆点回到露出那块的正中，圆钮消失',
      Math.abs(dy - sheetTop / 2) < 40 && !(await evaluate(cdp, `!!document.querySelector('[aria-label="回到孩子"]')`)), `圆点 y ${dy.toFixed(0)}，露出那块中间 ${(sheetTop / 2).toFixed(0)}`)

  }

  // —— 状态卡两档（电脑）——
  {
    await evaluate(cdp, `localStorage.removeItem('parent-web.sheet')`)
    await openPage(false)
    const [nx, ny] = await centerOf('section[aria-label="孩子的状态"] h1')
    await cdp.send('Input.dispatchMouseEvent', { type: 'mouseWheel', x: nx, y: ny, deltaX: 0, deltaY: 100 })
    await sleep(500)
    check('电脑：在卡片上滚轮往上推，展开', (await snapNow()) === 'open', await snapNow())
    await cdp.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 })
    await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27 })
    await sleep(400)
    check('电脑：Esc 收起', (await snapNow()) === 'peek', await snapNow())
    await mouse(nx, ny, 'left')
    check('电脑：收起时点卡片展开', (await snapNow()) === 'open', await snapNow())
  }

  // —— 最多缩到看一个洲（电脑上滚轮缩小；1280 宽时下限约 3.9 级）——
  {
    await openPage(false)
    // 每次滚轮 MapLibre 只缩一截（带动画），多滚几次，等它停下
    for (let i = 0; i < 40; i++) {
      await cdp.send('Input.dispatchMouseEvent', { type: 'mouseWheel', x: 640, y: 300, deltaX: 0, deltaY: 3000 })
      await sleep(150)
    }
    await sleep(3000)
    const z = await mapZoom()
    const floor = Math.log2((1280 * 360) / (60 * 512))
    check('地图最多缩到看一个洲，缩不到整个地球', Math.abs(z - floor) < 0.05, `缩到 ${z.toFixed(2)} 级，下限 ${floor.toFixed(2)}`)
  }

  // —— 孩子在学校圈里，点他的圆点：还是状态卡，不弹地点卡片 ——
  await openPage(true, 'place')
  ;[kx, ky] = await kidPoint()
  await touch(kx, ky, 80)
  check('手机：孩子在地点圈里时点圆点不弹地点卡片', !(await sheetOpen('常去地点')) && (await sheetOpen('孩子的状态')))
  await openPage(false, 'place')
  ;[kx, ky] = await kidPoint()
  await mouse(kx, ky, 'left')
  check('电脑：孩子在地点圈里时点圆点不弹地点卡片', !(await sheetOpen('常去地点')) && (await sheetOpen('孩子的状态')))
  // 对照：点圆点旁边圈里的地方照样打开卡片（不是把圈的点击整个关了）
  await mouse(kx + 60, ky + 60, 'left')
  check('电脑：点圈里别处照样打开地点卡片', (await cardTitle()) === '学校', `标题：${await cardTitle()}`)

  // —— 共用的地点：删除先提醒，「只对小宝取消」只去掉关联 ——
  await openPage(true, 'placeshared')
  await clickButton('删除')
  check('删除共用地点先提醒也用在谁身上', await until(`document.querySelector('section[aria-label="常去地点"]').textContent.includes('这个地点也用在大宝身上')`),
    await cardText())
  await clickButton('只对小宝取消')
  check('「只对小宝取消」只去掉小宝的关联，不删地点',
    (await until(`!document.querySelector('section[aria-label="常去地点"]')`)) && unlinked.length === 1 && unlinked[0].deviceId === 1 && unlinked[0].geofenceId === SCHOOL.id && deleted.length === 0,
    `取消关联 ${JSON.stringify(unlinked)}，删除 ${deleted.join(',')}`)
  check('取消后小宝的常去地点里没有学校了', await until(`[...document.querySelectorAll('button')].some((b) => b.textContent.trim() === '常去地点1 个')`))

  // —— 常去地点卡片（手机，place 场景：小宝在学校圆心）——
  await openPage(true, 'place')
  ;[kx, ky] = await kidPoint()
  const hitCircle = await tapMap(kx + 90, ky + 40)
  check('点地图上的圈打开地点卡片', hitCircle && (await cardTitle()) === '学校', `标题：${await cardTitle()}`)
  const cardButtons = await evaluate(cdp, `[...document.querySelectorAll('section[aria-label="常去地点"] button')].map((b) => b.textContent.trim())`)
  check('地点卡片有改名、改范围、删除', ['改名', '改范围', '删除'].every((t) => cardButtons.includes(t)), cardButtons.join('、'))

  await clickButton('改范围')
  await sleep(300)
  const zoomBefore = await mapZoom()
  await clickButton('1000 米')
  await sleep(1200)
  check('改范围时地图缩小看全预览的圈', (await mapZoom()) < zoomBefore - 1, `缩放 ${zoomBefore.toFixed(2)} → ${(await mapZoom()).toFixed(2)}`)
  await clickButton('500 米')
  await sleep(300)
  await clickButton('保存')
  check('改范围发了 PUT', await until(`document.querySelector('section[aria-label="常去地点"] h2')?.textContent === '学校'`) && updated.length === 1)
  const put = updated[0]
  const putCircle = parseCircle(put?.area)
  check('改范围 PUT 的 area：圆心不变、半径 500', put?.id === SCHOOL.id && put?.name === '学校' && putCircle !== null &&
    putCircle.latitude === SCHOOL.latitude && putCircle.longitude === SCHOOL.longitude && putCircle.radius === 500, put?.area ?? '没有')
  check('改完卡片上范围跟着变', (await cardText()).includes('500 米内'))

  await clickButton('改名')
  await sleep(300)
  await typeInto('section[aria-label="常去地点"] input', '学校北门')
  await clickButton('保存')
  check('改名发了 PUT，范围不动', await until(`document.querySelector('section[aria-label="常去地点"] h2')?.textContent === '学校北门'`) &&
    updated[1]?.name === '学校北门' && updated[1]?.area === put?.area, JSON.stringify(updated[1] ?? null))

  await clickButton('删除')
  await sleep(300)
  check('点删除先确认，还没删', deleted.length === 0 && (await cardText()).includes('删除后到达/离开不再提醒'))
  await clickButton('取消')
  await sleep(200)
  check('确认时取消回到卡片', deleted.length === 0 && (await cardTitle()) === '学校北门')
  await clickButton('删除')
  await sleep(200)
  await clickButton('删除')
  check('确认后才删除', await until(`!document.querySelector('section[aria-label="常去地点"]')`) && deleted[0] === SCHOOL.id, `删了 ${deleted.join(',')}`)

  // 常去地点二级页：点一行回到地图、打开同一张卡片
  await clickButton('常去地点1 个')
  await sleep(200)
  await clickButton('家100 米内')
  await sleep(500)
  check('常去地点页点一行回到地图、打开卡片', (await cardTitle()) === '家' && !(await evaluate(cdp, `!!document.querySelector('[role="dialog"]')`)))

  // —— 实时移动：来了新点，圆点按上报间隔匀速走过去，不瞬移 ——
  {
    await openPage(true, 'live')
    const liveCard = await evaluate(cdp, `document.querySelector('section[aria-label="孩子的状态"]').textContent`)
    check('移动中、位置 20 秒前：状态卡写在路上和速度', liveCard.includes('在路上 · 12 km/h') && !liveCard.includes('分钟前'), liveCard.slice(0, 40))
    // 跟随中：镜头每帧追着圆点，圆点一直在露出来那块的正中附近（新点没有地址，状态卡变矮也一样）
    const follow = await pushAndRecord(step, STEP_MS + 500)
    const maxStep = Math.max(...follow.slice(1).map(([x, y], i) => Math.hypot(x - follow[i][0], y - follow[i][1])))
    const sheetTop = await evaluate(cdp, `document.querySelector('section[aria-label="孩子的状态"]').getBoundingClientRect().top`)
    const off = Math.abs(follow.at(-1)[1] - sheetTop / 2)
    check('跟随时来了新点：圆点不跳（镜头跟着走），停在露出来那块正中', maxStep < 8 && off < 3, `单帧最大挪 ${maxStep.toFixed(1)} 像素，离正中 ${off.toFixed(1)} 像素`)

    // 拖过地图（不跟随）：圆点自己走过去
    await openPage(true, 'live')
    await touch(195, 300, 0, 120)
    const liveZoom = await mapZoom()
    const frames = await pushAndRecord(step, STEP_MS + 300)
    const [x0, y0] = frames[0]
    const want = STEP_M / metersPerPixel(LIVE.latitude, liveZoom)
    const at = (ms) => frames.reduce((best, f) => (Math.abs(f[2] - ms) < Math.abs(best[2] - ms) ? f : best))
    const [xEnd, yEnd] = at(STEP_MS)
    check('不跟随时来了新点：4 秒（一个上报间隔）走到新位置', Math.abs(y0 - yEnd - want) < 4 && Math.abs(xEnd - x0) < 3,
      `4 秒时挪了 ${(y0 - yEnd).toFixed(1)} 像素，应该 ${want.toFixed(1)}`)
    const half = (y0 - at(STEP_MS / 2)[1]) / want
    const steps = frames.filter(([, , t]) => t < STEP_MS).slice(1).map(([, y], i) => frames[i][1] - y)
    check('匀速走：走到一半时间在一半的地方，每帧都在动、不跳', half > 0.4 && half < 0.6 && Math.max(...steps) < want / 10,
      `一半时间走了 ${(half * 100).toFixed(0)}%，单帧最多 ${Math.max(...steps).toFixed(1)} 像素`)
  }

  // —— 选点：调范围图钉不跑 ——
  await openPage(true)
  await enterPicking()
  zoom = await mapZoom()
  await clickButton('1000 米')
  await sleep(1200)
  check('选点时选 1000 米地图缩小', (await mapZoom()) < zoom - 1, `缩放 ${zoom.toFixed(2)} → ${(await mapZoom()).toFixed(2)}`)
  await clickButton('用这里')
  await sleep(300)
  const wide = await saveAsHome()
  check('选点时调范围，图钉还在原处、半径 1000', wide !== null && distance(KID, wide) < 2 && wide.radius === 1000,
    wide ? `差 ${distance(KID, wide).toFixed(2)} 米，半径 ${wide.radius}` : '没有存进去')

  // —— 选点：搜地址（选类别提示，走 place/text） ——
  await openPage(true)
  await enterPicking()
  await typeInto('input[type="search"]', '文三')
  check('搜索出 3 条提示', await until(`document.querySelectorAll('[role="option"]').length === 3`))
  await clickButton('小学浙江省杭州市西湖区')
  await sleep(2000) // 飞过去 + 停下 600 毫秒后查地址
  const want = gcj02ToWgs84(SEARCH_GCJ.latitude, SEARCH_GCJ.longitude)
  const lastRegeo = regeo.at(-1)
  check('查图钉地址传的是 GCJ-02（搜索结果原坐标）', lastRegeo !== undefined && distance(lastRegeo, SEARCH_GCJ) < 1,
    lastRegeo ? `差 ${distance(lastRegeo, SEARCH_GCJ).toFixed(2)} 米` : '没有查')
  check('图钉下面显示地址', (await evaluate(cdp, `document.querySelector('section[aria-label="在地图上选位置"]').textContent`)).includes('图钉位置：浙江省杭州市'))
  await clickButton('用这里')
  await sleep(300)
  const found = await saveAsHome()
  check('搜索结果存进去的是转回的 WGS-84', found !== null && distance(want, found) < 2,
    found ? `差 ${distance(want, found).toFixed(2)} 米（没转会差 ${distance(want, SEARCH_GCJ).toFixed(0)} 米）` : '没有存进去')

  // —— 选点：我的位置 ——
  const origin = new URL(url).origin
  await cdp.send('Browser.grantPermissions', { origin, permissions: ['geolocation'] })
  await cdp.send('Emulation.setGeolocationOverride', { ...PARENT, accuracy: 10 })
  await openPage(true)
  await enterPicking()
  await clickButton('我的位置')
  await sleep(1500)
  await clickButton('用这里')
  await sleep(300)
  const me = await saveAsHome()
  check('「我的位置」存的是浏览器给的 WGS-84', me !== null && distance(PARENT, me) < 2, me ? `差 ${distance(PARENT, me).toFixed(2)} 米` : '没有存进去')

  await cdp.send('Browser.setPermission', { origin, permission: { name: 'geolocation' }, setting: 'denied' })
  await openPage(true)
  await enterPicking()
  await clickButton('我的位置')
  check('拒绝定位时提示', await until(`document.querySelector('section[aria-label="在地图上选位置"]').textContent.includes('拿不到你的位置，请拖地图选')`))
} finally {
  await close()
}

if (failures.length) {
  console.log(`\n${failures.length} 项没过`)
  process.exit(1)
}
console.log('\n地图手势都通过')
