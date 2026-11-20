// 回放 P20 两天的真实上报（fixtures/p20.json，11-16 10:20 到 11-17 21:23，坐标已整体平移到杭州，见 scripts/p20-fixture.mjs）：
//   /m/?state=replay&from=2026-11-17T07:03:00%2B08:00&to=2026-11-17T07:36:00%2B08:00&speed=30
// 假时钟从 from 起按 speed 倍速走：打开时的「最新位置」是 from 之前服务器收到的最后一条，最近 30 分钟的点按 route 接口给，
// 之后每条按服务器收到的时间由 WebSocket 推来（和线上一样一条一条）；按 Traccar 的规则，定位时间早于当时最新位置的晚到旧点不推、
// 也不当最新位置（traccarPush.ts），只在补拉的轨迹里。连上先推一帧当时的最新位置。放到 to 时 window.replayDone = true。
import { parsePositions } from '../src/api/parse'
import type { Api } from '../src/api/types'
import type { FixtureBackend } from './mockApi'
import p20Json from './p20.json'
import { received, traccarFeed } from './traccarPush'

const DEFAULT_FROM = '2026-11-17T07:03:00+08:00'
const DEFAULT_TO = '2026-11-17T07:36:00+08:00'

const unsupported = () => Promise.reject(new Error('回放里不能改'))

export function replayBackend(q: URLSearchParams): FixtureBackend {
  const all = parsePositions(p20Json).sort((a, b) => received(a) - received(b) || a.id - b.id)
  const from = new Date(q.get('from') ?? DEFAULT_FROM).getTime()
  const to = new Date(q.get('to') ?? DEFAULT_TO).getTime()
  const speed = Number(q.get('speed') ?? '30')
  const t0 = performance.now()
  const clock = () => Math.min(to, from + (performance.now() - t0) * speed)
  const seen = () => all.filter((p) => received(p) <= clock())
  const feed = traccarFeed(all)
  const latest = () => feed.latestAt(clock()) ?? all[0]

  const api: Api = {
    login: async () => 'ok',
    createToken: async () => 'replay-token',
    openSession: async () => {},
    endSession: async () => {},
    revokeToken: async () => {},
    checkAuth: async () => {},
    devices: async () => [{ id: 1, name: '小宝' }],
    latestPositions: async () => [latest()],
    positionsById: async () => [],
    route: async (_t, deviceId, f, t) => seen().filter((p) => p.deviceId === deviceId && p.fixTime >= f && p.fixTime <= t),
    events: async () => [],
    geofences: async () => [],
    addGeofence: unsupported,
    updateGeofence: unsupported,
    deleteGeofence: unsupported,
    unlinkGeofence: unsupported,
    amapTips: async () => [],
    amapSearch: async () => [],
    amapRegeo: async () => ({ address: null, city: null }),
    subscribe: (onMessage) => {
      const now = clock()
      const timers = feed.pushed
        .filter((p) => received(p) > now && received(p) <= to)
        .map((p) => setTimeout(() => onMessage({ devices: [], positions: [p], events: [] }), (received(p) - now) / speed))
      timers.push(setTimeout(() => onMessage({ devices: [], positions: [latest()], events: [] }), 0))
      timers.push(setTimeout(() => Object.assign(window, { replayDone: true }), (to - now) / speed + 3000))
      return () => timers.forEach(clearTimeout)
    },
    now: () => new Date(clock()),
  }
  return { api, token: 'replay-token', clock }
}
