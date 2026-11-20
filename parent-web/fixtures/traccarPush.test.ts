import { describe, expect, it } from 'vitest'
import type { Position } from '../src/api/types'
import { traccarFeed } from './traccarPush'

const T0 = Date.UTC(2026, 10, 20, 8, 0)
const pos = (id: number, fixS: number, serverS: number): Position => ({
  id,
  deviceId: 1,
  fixTime: new Date(T0 + fixS * 1000),
  deviceTime: new Date(T0 + fixS * 1000),
  serverTime: new Date(T0 + serverS * 1000),
  latitude: 30,
  longitude: 120,
  speedKmh: 0,
  accuracy: 5,
  geofenceIds: [],
})

describe('回放按 Traccar 规则推送', () => {
  it('乱序到达的旧点不推，也不当最新位置（审计 #15：0、2、1 到达只推 0、2）', () => {
    const feed = traccarFeed([pos(1, 0, 1), pos(2, 20, 21), pos(3, 10, 22)])
    expect(feed.pushed.map((p) => p.id)).toEqual([1, 2])
    expect(feed.latestAt(T0 + 30_000)?.id).toBe(2)
    expect(feed.latestAt(T0 + 500)).toBeUndefined()
  })

  it('没带坐标的心跳抄上一个点的定位时间，照推', () => {
    const feed = traccarFeed([pos(1, 0, 1), pos(2, 0, 300)])
    expect(feed.pushed.map((p) => p.id)).toEqual([1, 2])
  })
})
