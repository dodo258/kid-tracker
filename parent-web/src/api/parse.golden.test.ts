// 重写 parse.ts（2026-11-19）前钉住的行为：仓库里所有按 Traccar 原样格式存的假数据和真实回放，加一组故意写坏、写怪的条目，
// 解析结果逐字段序列化后算 SHA-256。对不上时打印那一份的解析结果，看哪里变了。
// 有意改解析时：跑 GOLDEN_PRINT=1 npx vitest run src/api/parse.golden.test.ts，看清每一处变化是想要的，再换掉下面的摘要。
import { describe, expect, it } from 'vitest'
import devices from '../../fixtures/devices.json'
import eventPositions from '../../fixtures/event-positions.json'
import events from '../../fixtures/events.json'
import geofences from '../../fixtures/geofences.json'
import ride from '../../fixtures/p20-ride-1118.json'
import jump from '../../fixtures/p20-wifi-jump-1118.json'
import p20 from '../../fixtures/p20.json'
import positions from '../../fixtures/positions.json'
import route from '../../fixtures/route.json'
import { parseDevices, parseEvents, parseGeofences, parsePositions, parseSocketMessage } from './parse'

const sims = import.meta.glob<unknown>('../../fixtures/sim/*.json', { eager: true, import: 'default' })

const BASE = { id: 1, deviceId: 2, fixTime: '2026-11-16T08:39:00.000+00:00', latitude: 30.27, longitude: 120.14 }

/** 写坏、写怪的条目：缺字段、类型不对、空字符串、NaN 样的值、占位坐标，每条都要么丢掉、要么按现在的规矩读 */
const ODD: unknown[] = [
  null,
  42,
  'x',
  [],
  {},
  { ...BASE, id: '1' },
  { ...BASE, deviceId: null },
  { ...BASE, fixTime: 'not a date' },
  { ...BASE, fixTime: 12345 },
  { ...BASE, latitude: '30.27' },
  { ...BASE, longitude: null },
  { ...BASE, speed: 'fast', accuracy: null, course: '45' },
  { ...BASE, address: '   ', geofenceIds: [1, '2', null, 3] },
  { ...BASE, deviceTime: 'bad', serverTime: 'bad' },
  { ...BASE, outdated: 'true' },
  { ...BASE, attributes: null },
  { ...BASE, attributes: [] },
  { ...BASE, attributes: { state: 'running', provider: 7, steps: '12', charge: 'yes', powerSave: 1, locked: 'true' } },
  { ...BASE, attributes: { state: 'still', charge: true, powerSave: false, locked: true, netLat: 30.3, netLon: '120.1', netAcc: 80 } },
  { ...BASE, latitude: 0, longitude: 0, outdated: true, fixTime: '1980-01-06T00:00:00.000+00:00' },
  { ...BASE, latitude: 0, longitude: 0 },
  { ...BASE, fixTime: '1999-12-31T23:59:59.000+00:00' },
  { ...BASE, fixTime: '2000-01-01T00:00:00.000+00:00' },
  { ...BASE, latitude: 0 },
]

/** 解析结果稳定地写成字符串：日期写 ISO，undefined 的键去掉，键按字母排 */
function serialize(v: unknown): string {
  return JSON.stringify(v, (_k, x: unknown) => {
    if (x && typeof x === 'object' && !Array.isArray(x) && !(x instanceof Date)) {
      return Object.fromEntries(Object.entries(x).sort(([a], [b]) => a.localeCompare(b)))
    }
    return x
  })
}

async function digest(text: string): Promise<string> {
  const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)))
  return [...hash.slice(0, 8)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

const SOURCES: Record<string, () => unknown> = {
  positions: () => Object.fromEntries(Object.entries(positions).map(([k, v]) => [k, parsePositions(v)])),
  p20: () => parsePositions(p20),
  ride: () => parsePositions(ride),
  jump: () => parsePositions(jump),
  route: () => parsePositions(route),
  eventPositions: () => parsePositions(eventPositions),
  sim: () => Object.fromEntries(Object.entries(sims).map(([k, v]) => [k, parsePositions(v)])),
  odd: () => ODD.map((v) => parsePositions([v])),
  others: () => ({
    devices: parseDevices(devices),
    events: parseEvents(events),
    geofences: Object.fromEntries(Object.entries(geofences).map(([k, v]) => [k, parseGeofences(v)])),
    socket: [parseSocketMessage(null), parseSocketMessage({ positions: [BASE], devices: [{ id: 1, name: '小宝' }], events: [] })],
  }),
}

const GOLDEN: Record<string, string> = {
  positions: '6cd1ecdffe988c17',
  p20: 'e61c15bcff1df402',
  ride: 'a9659e68b82d4698',
  jump: '8a184eb885726464',
  route: '227d74da8a463752',
  eventPositions: '8c99fa784d7e0525',
  sim: '3f531ebd1fa6e605',
  odd: '072011497ea932a9',
  others: 'aed1585af0d84e21',
}

describe('解析结果和重写前一样（全部假数据、真实回放、写坏的条目）', () => {
  for (const [name, run] of Object.entries(SOURCES)) {
    it(name, async () => {
      const text = serialize(run())
      const got = await digest(text)
      if (import.meta.env.GOLDEN_PRINT) console.log(`GOLDEN ${name} ${got}`)
      if (got !== GOLDEN[name]) console.log(`${name} 对不上，解析结果：\n${text.slice(0, 4000)}`)
      expect(got).toBe(GOLDEN[name])
    })
  }
})
