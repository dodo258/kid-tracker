// 把服务器导出的真实上报（tc_positions，CSV）做成回放用的 fixtures/p20.json：
// - 坐标整体平移到假数据所在的杭州（第一个点放在 fixtures 里小宝的位置），点和点之间的距离、方向、漂移原样保留，不留孩子家的真实位置；
// - attributes 只留网页用得到的 state、provider、batteryLevel、charge、alarm；
// - 时间按服务器时区（Asia/Shanghai）写成带 +08:00 的 ISO。
// 导出办法（服务器上 Traccar 的 H2 只能单进程打开：先 scp 一份 /opt/traccar/data/database.mv.db 到本机，用同目录 lib 里的 h2 jar）：
//   java -cp h2.jar org.h2.tools.Shell -url "jdbc:h2:/本机目录/database;ACCESS_MODE_DATA=r" -user sa -password "" \
//     -sql "CALL CSVWRITE('/本机目录/positions.csv', 'SELECT id, servertime, devicetime, fixtime, valid, latitude, longitude, speed, course, accuracy, attributes FROM tc_positions ORDER BY id')"
// 用法：node scripts/p20-fixture.mjs <positions.csv> > fixtures/p20.json
import { readFileSync } from 'node:fs'

/** 双引号包着、引号里两个引号代表一个引号的 CSV（H2 CSVWRITE 的格式） */
function parseCsv(text) {
  const rows = []
  let row = []
  let field = ''
  let quoted = false
  for (let i = 0; i < text.length; i++) {
    const ch = text[i]
    if (quoted) {
      if (ch === '"' && text[i + 1] === '"') {
        field += '"'
        i++
      } else if (ch === '"') quoted = false
      else field += ch
    } else if (ch === '"') quoted = true
    else if (ch === ',') {
      row.push(field)
      field = ''
    } else if (ch === '\n') {
      row.push(field)
      rows.push(row)
      row = []
      field = ''
    } else if (ch !== '\r') field += ch
  }
  if (field || row.length) rows.push([...row, field])
  return rows
}

/** fixtures/positions.json 里小宝的位置（WGS-84） */
const ANCHOR = { latitude: 30.27439, longitude: 120.140581 }
const M_PER_DEG = 111_320
const KEEP = ['state', 'provider', 'batteryLevel', 'charge', 'alarm']

const [header, ...rows] = parseCsv(readFileSync(process.argv[2], 'utf8')).filter((r) => r.length > 1)
const col = Object.fromEntries(header.map((h, i) => [h.toLowerCase(), i]))
const origin = { latitude: Number(rows[0][col.latitude]), longitude: Number(rows[0][col.longitude]) }
const iso = (s) => `${s.replace(' ', 'T')}${s.includes('.') ? '' : '.000'}+08:00`

const out = rows.map((r) => {
  const lat = Number(r[col.latitude])
  const lon = Number(r[col.longitude])
  // 按米平移：北向、东向距离不变
  const north = (lat - origin.latitude) * M_PER_DEG
  const east = (lon - origin.longitude) * M_PER_DEG * Math.cos((origin.latitude * Math.PI) / 180)
  const attrs = JSON.parse(r[col.attributes])
  return {
    id: Number(r[col.id]),
    deviceId: 1,
    serverTime: iso(r[col.servertime]),
    deviceTime: iso(r[col.devicetime]),
    fixTime: iso(r[col.fixtime]),
    valid: r[col.valid] === 'TRUE',
    latitude: Number((ANCHOR.latitude + north / M_PER_DEG).toFixed(7)),
    longitude: Number((ANCHOR.longitude + east / (M_PER_DEG * Math.cos((ANCHOR.latitude * Math.PI) / 180))).toFixed(7)),
    speed: Number(r[col.speed]),
    course: Number(r[col.course]),
    accuracy: Number(r[col.accuracy]),
    attributes: Object.fromEntries(KEEP.filter((k) => k in attrs).map((k) => [k, attrs[k]])),
  }
})
process.stdout.write(`${JSON.stringify(out, null, 0).replace(/\},\{/g, '},\n{')}\n`)
