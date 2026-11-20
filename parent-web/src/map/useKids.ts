// 地图上的设备：每台一个播放器（motion/player）加一组 DOM（kidMarker），一个 requestAnimationFrame 循环逐帧摆：
// 圆点位置、方向箭头（往行进方向平滑转，地图转了按地图朝向换算）、选中那台的精度圈、最近 30 分钟轨迹线（画到圆点为止）、
// 跟随时镜头（圆点留在底部卡片上面那块的正中，指数逼近，不抖不跳）。都停稳了循环就停，有变化再叫醒（kick）。
import type { Position } from '../api/types'
import { nearlyStopped } from '../domain/fixQuality'
import { onTheMove, type Freshness, type Live } from '../domain/presence'
import { approachHeading, screenAngle, wrap180 } from '../motion/heading'
import { finalFrame, frameAt, isCatchUp, pushFix, retarget, setLive, startPlayer, type Fix, type Player } from '../motion/player'
import type { GeoJSONSource, Map as MlMap } from 'maplibre-gl'
import { useCallback, useEffect, useMemo, useRef, type RefObject } from 'react'
import { EMPTY, themeColor } from './baseMap'
import { metersPerPixel, project, type Base, type LngLat } from './geometry'
import { onPageResume } from './breathe'
import { createAccuracyView, createKidView, type AccuracyView, type KidView } from './kidMarker'
import { traceFrame } from './trace'

/** 地图上的一台设备 */
export type KidOnMap = {
  id: number
  name: string
  freshness: Freshness
  /** 在路上的几种（presence）：实时、定位中时匀速播放、迟到外推；实时才画方向箭头 */
  live: Live
  /** 在路上但最新一条只报了时间、没走动（presence.holdsDot）：圆点停在最后一个点上，不往前推 */
  hold: boolean
  /** 挪过圆点的点剔除跳点、平滑以后（motion/smooth 的 dot），按定位时间排好；空的不画 */
  fixes: readonly Position[]
}

type Entry = { player: Player; view: KidView; kid: KidOnMap; heading: number | null }

/** 箭头转向的时间常数（约 0.75 秒转到位） */
const TURN_TAU_MS = 250
/** 跟随时镜头追圆点的时间常数 */
const FOLLOW_TAU_MS = 90
/** 镜头和圆点差不到这么多像素算对准 */
const FOLLOW_EPS_PX = 0.5
/** 轨迹线最多每 100 毫秒重画一次 */
const TRAIL_EVERY_MS = 100
const REDUCED_MOTION = '(prefers-reduced-motion: reduce)'

export function toFix(p: Position): Fix {
  return { at: { latitude: p.latitude, longitude: p.longitude }, time: p.fixTime.getTime(), accuracy: p.accuracy, speedKmh: p.speedKmh, course: p.course, stopped: nearlyStopped(p) }
}

/** 圆点应该在屏幕上的哪儿：底部卡片上面那块的正中 */
export function followPoint(map: MlMap, bottomInset: number): [number, number] {
  const c = map.getContainer()
  return [c.clientWidth / 2, (c.clientHeight - bottomInset) / 2]
}

type Args = {
  map: MlMap | null
  base: Base
  kids: readonly KidOnMap[]
  selectedId: number | null
  onSelect: (id: number) => void
  /** 现在几点（毫秒，和定位时间同一个时钟）；截图回放时是假时钟 */
  clock: () => number
  following: RefObject<boolean>
  bottomInset: number
  /** 选中设备最近 30 分钟轨迹线上的点；null 不画 */
  trail: readonly Position[] | null
  dark: boolean
}

export type KidsHandle = {
  /** 叫醒循环（地图动了、转了、缩放了、跟随重新打开） */
  kick: () => void
  /** 某台设备圆点现在在哪（底图坐标），没有返回 null */
  dotOf: (id: number) => LngLat | null
}

export function useKids({ map, base, kids, selectedId, onSelect, clock, following, bottomInset, trail, dark }: Args): KidsHandle {
  const entries = useRef(new Map<number, Entry>())
  const accuracy = useRef<AccuracyView | null>(null)
  const frameId = useRef(0)
  const lastTs = useRef(0)
  const lastTrail = useRef(0)
  const trailDrawn = useRef(false)
  // 循环里读的最新参数
  const args = useRef({ base, selectedId, clock, bottomInset, trail, onSelect })
  const tickRef = useRef<(ts: number) => void>(() => {})
  const kick = useCallback(() => {
    if (frameId.current === 0) frameId.current = requestAnimationFrame((ts) => tickRef.current(ts))
  }, [])

  useEffect(() => {
    args.current = { base, selectedId, clock, bottomInset, trail, onSelect }
  })

  // 一帧
  useEffect(() => {
    tickRef.current = (ts: number) => {
      frameId.current = 0
      if (!map) return
      const { base, selectedId, clock, bottomInset, trail } = args.current
      const now = clock()
      const dt = lastTs.current ? ts - lastTs.current : 16
      lastTs.current = ts
      const reduced = window.matchMedia(REDUCED_MOTION).matches
      const bearing = map.getBearing()
      let active = false
      for (const e of entries.current.values()) {
        const f = reduced ? finalFrame(e.player) : frameAt(e.player, now)
        const at = project(base, f.at)
        e.view.moveTo(at)
        if (!f.settled) active = true
        // 方向箭头：实时才画；往行进方向平滑转（走近的那边），屏幕上的角度减去地图朝向
        if (e.kid.live === 'live' && f.heading !== null) {
          e.heading = e.heading === null || reduced ? f.heading : approachHeading(e.heading, f.heading, dt, TURN_TAU_MS)
          if (Math.abs(wrap180(f.heading - e.heading)) > 0.1) active = true
          e.view.setArrow(screenAngle(e.heading, bearing))
        } else {
          e.heading = null
          e.view.setArrow(null)
        }
        if (e.kid.id !== selectedId) continue
        traceFrame(now, f.at, e.kid.live)
        // 精度圈：半径按这个缩放级别换成像素
        const mpp = metersPerPixel(f.at.latitude, map.getZoom())
        if (!accuracy.current) accuracy.current = createAccuracyView(map, at)
        accuracy.current.update(at, f.accuracy > 0 ? f.accuracy / mpp : 0, e.kid.freshness)
        // 最近 30 分钟轨迹线：画到圆点已经走过的点，再连到圆点
        const source = map.getSource<GeoJSONSource>('trail')
        if (trail && trail.length > 0 && (ts - lastTrail.current >= TRAIL_EVERY_MS || f.settled)) {
          lastTrail.current = ts
          const coords = [...trail.filter((p) => p.fixTime.getTime() <= f.passed).map((p) => project(base, p)), at]
          source?.setData(
            coords.length < 2
              ? EMPTY
              : { type: 'FeatureCollection', features: [{ type: 'Feature', properties: { color: themeColor(e.kid.freshness === 'fresh' ? '--an' : '--mute') }, geometry: { type: 'LineString', coordinates: coords } }] },
          )
        } else if ((!trail || trail.length === 0) && trailDrawn.current) source?.setData(EMPTY)
        trailDrawn.current = trail !== null && trail.length > 0
        // 跟随：镜头追圆点，用户在拖、在缩放、镜头在飞的时候不抢
        if (following.current && !map.isMoving()) {
          const [wx, wy] = followPoint(map, bottomInset)
          const p = map.project(at)
          const ex = p.x - wx
          const ey = p.y - wy
          if (Math.hypot(ex, ey) > FOLLOW_EPS_PX) {
            // 减少动态效果时直接对准，不缓动
            const k = reduced || Math.hypot(ex, ey) < 2 ? 1 : 1 - Math.exp(-dt / FOLLOW_TAU_MS)
            const c = map.getContainer()
            map.jumpTo({ center: map.unproject([c.clientWidth / 2 + ex * k, c.clientHeight / 2 + ey * k]) })
            active = true
          }
        }
      }
      if (selectedId === null || !entries.current.has(selectedId)) {
        accuracy.current?.remove()
        accuracy.current = null
      }
      if (active) kick()
      else lastTs.current = 0
    }
    kick()
  }, [map, following, kick])

  // 设备来了新点：喂给各自的播放器；新设备建 DOM，没了的删掉
  useEffect(() => {
    if (!map) return
    const now = clock()
    const ids = new Set<number>()
    for (const kid of kids) {
      const newest = kid.fixes.at(-1)
      if (!newest) continue
      ids.add(kid.id)
      const look = { name: kid.name, freshness: kid.freshness, selected: kid.id === selectedId }
      const moving = onTheMove(kid) && !kid.hold
      const e = entries.current.get(kid.id)
      if (!e) {
        const view = createKidView(map, look, project(base, newest), () => args.current.onSelect(kid.id))
        entries.current.set(kid.id, { player: setLive(startPlayer(toFix(newest)), moving, now), view, kid, heading: null })
        continue
      }
      // 圆点该停的点比正在播的还早、或同一时刻换了坐标（补拉到的点里，最新的只是在精度圈里漂）：滑回去
      let player = retarget(e.player, toFix(newest), now)
      for (const f of kid.fixes) {
        if (f.fixTime.getTime() > player.last.time) player = pushFix(player, toFix(f), now, { live: moving, catchUp: isCatchUp(f, now) })
      }
      e.player = setLive(player, moving, now)
      e.kid = kid
      e.view.setLook(look)
    }
    for (const [id, e] of entries.current) {
      if (ids.has(id)) continue
      e.view.remove()
      entries.current.delete(id)
    }
    kick()
  }, [map, kids, selectedId, base, clock, kick])

  // 底图、深浅色、底部卡片高度、轨迹线变了：重摆一帧
  useEffect(() => {
    lastTrail.current = 0
    kick()
  }, [base, dark, bottomInset, trail, kick])

  // 回到前台：挂起前排的那一帧可能再也不来（循环就再也叫不醒），上一帧的时间也是挂起前的（第一帧会算出几分钟的 dt）。
  // 清掉重新起，第一帧按 16 毫秒算
  useEffect(
    () =>
      onPageResume(() => {
        cancelAnimationFrame(frameId.current)
        frameId.current = 0
        lastTs.current = 0
        kick()
      }),
    [kick],
  )

  // 地图转了、缩放了（精度圈半径、箭头角度）、镜头停下（跟随时对准）
  useEffect(() => {
    if (!map) return
    map.on('rotate', kick)
    map.on('zoom', kick)
    map.on('moveend', kick)
    return () => {
      map.off('rotate', kick)
      map.off('zoom', kick)
      map.off('moveend', kick)
    }
  }, [map, kick])

  // 地图没了：DOM 全删，循环停下
  useEffect(() => {
    if (!map) return
    const all = entries.current
    return () => {
      cancelAnimationFrame(frameId.current)
      frameId.current = 0
      for (const e of all.values()) e.view.remove()
      all.clear()
      accuracy.current?.remove()
      accuracy.current = null
    }
  }, [map])

  return useMemo<KidsHandle>(
    () => ({
      kick,
      dotOf: (id) => {
        const e = entries.current.get(id)
        if (!e) return null
        const { base, clock } = args.current
        return project(base, frameAt(e.player, clock()).at)
      },
    }),
    [kick],
  )
}
