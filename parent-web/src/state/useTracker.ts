// 页面的数据：设备、各设备最新一条上报和最近 30 分钟的点、选中设备的 24 小时告警和今天轨迹。
// 实时更新走 WebSocket，断了每 30 秒轮询，并立刻重连（再失败每 30 秒一次）；页面回到前台立刻刷新，
// 刷新有结果（拉到或失败）之前 updating 为真、「现在」先不往前走：卡片说「正在更新…」，不按停在后台那会儿的数据闪一下「已失联」；
// 10 秒还没结果 slow 为真，卡片说「同步较慢」（请求自己 15 秒超时，失败了再按家长这边连不上服务器说）。
// 告警：推送不通（还没连上、断了）或上次没查到时每 30 秒查一次选中孩子的事件，打开告警页也补查一次；查到哪一刻记在 eventsAsOf。
// 选中设备打开时、换孩子时、刷新时、断线重连收到首帧时补拉最近 30 分钟的点（轨迹线、「在路上从什么时候开始」）和事件、今天的轨迹；
// Traccar 的最新位置指针停在手机时间快的超前点上时（serverPointers），每 30 秒补拉一次最近 30 分钟：校时后的正常点 Traccar 不推，
// 页面补拉到正常点以后指针也还停在那，要一直拉到指针的定位时间被现在追上（见 domain/presence.timelineTime）。
// 告警和轨迹按设备号存（state/selected）：换孩子、自动换孩子、点已选中的孩子都不串、不清空。
import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from 'react'
import { watchAuth } from '../api/authWatch'
import { errorText } from '../api/errorText'
import { runSocketLoop } from '../api/socketLoop'
import { loadDeviceId, saveDeviceId } from '../api/storage'
import { AuthError, type Api, type Device, type Position, type SocketMessage, type TraccarEvent } from '../api/types'
import { isClockAhead } from '../domain/time'
import { EMPTY_HISTORY, TRAIL_MS, type History } from '../domain/track'
import { hasUnknownDevices, initialLatest, latestReducer } from './latest'
import { DAY_MS, eventsAsOf, eventsErrorOf, eventsOf, initialSelected, selectedReducer, trackOf } from './selected'

const POLL_MS = 30_000
const TICK_MS = 30_000
/** 回到前台后刷新这么久还没结果，改说「同步较慢」 */
export const SLOW_MS = 10_000

/**
 * 各设备 Traccar 最新位置指针的定位时间（毫秒）。Traccar 只在定位时间不早于指针的点到达时换指针、推送，指针只往后走；
 * /api/positions 给的、WebSocket 推来的就是当时的指针，取看到过的最大定位时间。补拉的轨迹不是指针，不算
 */
export function serverPointers(prev: ReadonlyMap<number, number>, incoming: readonly Position[]): ReadonlyMap<number, number> {
  let next: Map<number, number> | null = null
  for (const p of incoming) {
    const fix = p.fixTime.getTime()
    if (fix <= ((next ?? prev).get(p.deviceId) ?? -Infinity)) continue
    next ??= new Map(prev)
    next.set(p.deviceId, fix)
  }
  return next ?? prev
}

/**
 * 要不要每 30 秒自己补拉：指针的定位时间比现在晚 5 分钟以上（手机时间快的超前点）。这时比它早的正常点 Traccar 既不推也不换最新位置，
 * 页面补拉到正常点、自己的「最新」已经换掉也一样，得一直拉到现在追上指针
 */
export function needsPull(pointer: number | undefined, now: Date): boolean {
  return pointer !== undefined && isClockAhead(new Date(pointer), now)
}

export type Tracker = {
  loaded: boolean
  devices: Device[]
  selectedId: number | null
  /** 各设备最新一条上报（地图上所有设备的点） */
  positions: Map<number, Position>
  /** 各设备最近 30 分钟的点 */
  history: Map<number, History>
  position: Position | null
  /** 选中设备最近 30 分钟的点 */
  selectedHistory: History
  /** 选中设备最近 24 小时的事件；还没拉到（刚打开、刚切换孩子）是 null */
  events: TraccarEvent[] | null
  /** 选中设备的告警核实到哪一刻（selected.eventsAsOf）；还没查成功过是 null */
  eventsAsOf: Date | null
  /** 最近一次查告警的错误；查成功了是 null */
  eventsError: string | null
  /** 事件 positionId → 当时的位置（取电量） */
  eventPositions: Map<number, Position>
  trackOn: boolean
  /** 轨迹没打开或还没拉到时是 null */
  track: Position[] | null
  live: boolean
  error: string | null
  /** 最后一次和服务器同步上（拉到设备和最新位置，或收到推送，含保活）的时刻；还没同步过是 null */
  syncedAt: Date | null
  now: Date
  /** 刚回到前台、最新数据还没拉回来 */
  updating: boolean
  /** 正在更新已经 10 秒了 */
  slow: boolean
  select: (id: number) => void
  setTrackOn: (on: boolean) => void
  refresh: () => void
  /** 马上再查一次选中孩子的告警（打开告警页、点重试） */
  reloadEvents: () => void
}

function startOfToday(now: Date): Date {
  return new Date(now.getFullYear(), now.getMonth(), now.getDate())
}

export function useTracker(api: Api, token: string, onAuthLost: () => void, initialTrack = false): Tracker {
  const [latest, dispatch] = useReducer(latestReducer, initialLatest)
  const { loaded, devices, positions, history, error } = latest
  const [chosenId, setChosenId] = useState<number | null>(loadDeviceId)
  const [selected, dispatchSelected] = useReducer(selectedReducer, initialSelected)
  const [eventPositions, setEventPositions] = useState<Map<number, Position>>(new Map())
  const [trackOn, setTrackOn] = useState(initialTrack)
  const [live, setLive] = useState(false)
  /** 推送上次断开的时刻：那之前的告警是推来的 */
  const [liveUntil, setLiveUntil] = useState<Date | null>(null)
  const [syncedAt, setSyncedAt] = useState<Date | null>(null)
  const [pointers, setPointers] = useState<ReadonlyMap<number, number>>(new Map())
  const [now, setNow] = useState(() => api.now())
  const [updating, setUpdating] = useState(false)
  const [slow, setSlow] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)
  const [eventsKey, setEventsKey] = useState(0)

  // 选中的设备：上次看的那台还在就用它，否则第一台
  const selectedId = devices.some((d) => d.id === chosenId) ? chosenId : (devices[0]?.id ?? null)
  const selectedRef = useRef(selectedId)
  const trackOnRef = useRef(trackOn)
  const authLostRef = useRef(onAuthLost)
  const liveRef = useRef(live)
  useEffect(() => {
    selectedRef.current = selectedId
    trackOnRef.current = trackOn
    authLostRef.current = onAuthLost
    liveRef.current = live
  })

  const fail = useCallback((err: unknown) => {
    if (err instanceof AuthError) authLostRef.current()
    else dispatch({ type: 'failed', error: errorText(err) })
  }, [])

  const fetchLatest = useCallback(
    () => Promise.all([api.devices(token), api.latestPositions(token)]),
    [api, token],
  )
  const applyLatest = useCallback(
    ([ds, ps]: [Device[], Position[]]) => {
      dispatch({ type: 'fetched', devices: ds, positions: ps })
      setPointers((prev) => serverPointers(prev, ps))
      setSyncedAt(api.now())
    },
    [api],
  )

  const loadEventPositions = useCallback(
    async (evs: TraccarEvent[]) => {
      const ids = [...new Set(evs.map((e) => e.positionId).filter((id) => id > 0))].slice(0, 50)
      const ps = await api.positionsById(token, ids)
      setEventPositions((prev) => {
        const next = new Map(prev)
        for (const p of ps) next.set(p.id, p)
        return next
      })
    },
    [api, token],
  )

  const onSocket = useCallback(
    (m: SocketMessage) => {
      dispatch({ type: 'socket', message: m })
      setPointers((prev) => serverPointers(prev, m.positions))
      dispatchSelected({ type: 'socket', selectedId: selectedRef.current, trackOn: trackOnRef.current, message: m })
      const newEvents = m.events.filter((e) => e.deviceId === selectedRef.current)
      if (newEvents.length > 0) loadEventPositions(newEvents).catch(fail)
      const at = api.now()
      setSyncedAt(at)
      setNow(at)
    },
    [api, fail, loadEventPositions],
  )

  // 设备和最新位置：首次、重连、回到前台。拉回来（或失败）以后「现在」才往前走
  useEffect(() => {
    let cancelled = false
    const done = () => {
      if (cancelled) return
      setUpdating(false)
      setSlow(false)
      setNow(api.now())
    }
    fetchLatest()
      .then((r) => {
        if (!cancelled) applyLatest(r)
      })
      .catch(fail)
      .finally(done)
    return () => {
      cancelled = true
    }
  }, [api, fetchLatest, applyLatest, fail, refreshKey])

  // 正在更新 10 秒还没结果：改说「同步较慢」，还是不按旧数据判（结果回来由上面结束）
  useEffect(() => {
    if (!updating) return
    const id = setTimeout(() => setSlow(true), SLOW_MS)
    return () => clearTimeout(id)
  }, [updating])

  // 首拉失败后靠 WebSocket 恢复时，推来的位置所属设备还不在列表里：补拉一次设备
  const unknownDevices = hasUnknownDevices(latest)
  useEffect(() => {
    if (!unknownDevices) return
    let cancelled = false
    fetchLatest()
      .then((r) => {
        if (!cancelled) applyLatest(r)
      })
      .catch(fail)
    return () => {
      cancelled = true
    }
  }, [unknownDevices, fetchLatest, applyLatest, fail])

  const onLive = useCallback(
    (on: boolean) => {
      if (!on && liveRef.current) setLiveUntil(api.now())
      setLive(on)
    },
    [api],
  )

  // WebSocket；断了立刻重连一次，再失败每 30 秒重新换会话再连（socketLoop.ts）
  useEffect(
    () =>
      runSocketLoop({
        openSession: () => api.openSession(token),
        subscribe: (onMessage, onClose) => api.subscribe(onMessage, onClose),
        onMessage: onSocket,
        onLive,
        // 断线期间的告警、轨迹 WebSocket 不补推：和手动刷新一样全部补拉一遍（按 id 合并）
        onResync: () => setRefreshKey((k) => k + 1),
        onAuthLost: () => authLostRef.current(),
        onError: fail,
      }),
    [api, token, onSocket, onLive, fail],
  )

  // WebSocket 通着时每 2 分钟带令牌查一次登录状态：账号停用、删除，令牌吊销后 Traccar 不断开已有的 WebSocket
  useEffect(() => {
    if (!live) return
    return watchAuth(() => api.checkAuth(token), () => authLostRef.current())
  }, [live, api, token])

  // WebSocket 不在时每 30 秒轮询（401 也会回登录页）
  useEffect(() => {
    if (live) return
    const id = setInterval(() => fetchLatest().then(applyLatest).catch(fail), POLL_MS)
    return () => clearInterval(id)
  }, [live, fetchLatest, applyLatest, fail])

  // 「几分钟前」每 30 秒走一次；在后台和正在更新时不走（iPhone 回到前台时攒下的定时器会先跑，别抢在最新数据前面）
  const updatingRef = useRef(updating)
  useEffect(() => {
    updatingRef.current = updating
  })
  useEffect(() => {
    const id = setInterval(() => {
      if (document.visibilityState === 'visible' && !updatingRef.current) setNow(api.now())
    }, TICK_MS)
    return () => clearInterval(id)
  }, [api])

  // 回到前台立刻刷新（iPhone 切出去再回来时 WebSocket 常常已经断了）
  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState !== 'visible') return
      setUpdating(true)
      setRefreshKey((k) => k + 1)
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [api])

  // 选中设备最近 24 小时的告警。推送不通（还没连上、断了）或上次没查到时每 30 秒再查：断线期间的求救推送不会补，
  // 只查一次就会一直显示查的那一刻的列表。没查到记下错误（告警页写「只更新到…」，可以重试）
  useEffect(() => {
    if (selectedId === null) return
    let cancelled = false
    let failed = false
    const attempt = async () => {
      const to = api.now()
      let evs: TraccarEvent[]
      try {
        evs = await api.events(token, selectedId, new Date(to.getTime() - DAY_MS), to)
      } catch (err) {
        if (cancelled) return
        failed = true
        if (!(err instanceof AuthError)) dispatchSelected({ type: 'eventsFailed', deviceId: selectedId, error: errorText(err) })
        fail(err)
        return
      }
      if (cancelled) return
      failed = false
      dispatchSelected({ type: 'events', deviceId: selectedId, events: evs, at: to })
      loadEventPositions(evs).catch(fail)
    }
    void attempt()
    const id = setInterval(() => {
      if (!liveRef.current || failed) void attempt()
    }, POLL_MS)
    return () => {
      cancelled = true
      clearInterval(id)
    }
  }, [api, token, selectedId, refreshKey, eventsKey, loadEventPositions, fail])

  // 选中设备最近 30 分钟的点：轨迹线和「在路上从什么时候开始」要用；拉不到不影响别的，只打在控制台
  const pull = needsPull(selectedId === null ? undefined : pointers.get(selectedId), now)
  useEffect(() => {
    if (selectedId === null) return
    let cancelled = false
    const load = () => {
      const to = api.now()
      api
        .route(token, selectedId, new Date(to.getTime() - TRAIL_MS), to)
        .then((ps) => {
          if (!cancelled) dispatch({ type: 'route', deviceId: selectedId, positions: ps })
        })
        .catch((err: unknown) => {
          if (err instanceof AuthError) authLostRef.current()
          else console.warn('最近 30 分钟的点没拉到', err)
        })
    }
    load()
    // Traccar 的指针停在超前点上：校时以后的正常点它不推，只能自己拉
    const id = pull ? setInterval(load, POLL_MS) : undefined
    return () => {
      cancelled = true
      clearInterval(id)
    }
  }, [api, token, selectedId, refreshKey, pull])

  // 今天的轨迹（打开时才拉）
  useEffect(() => {
    if (!trackOn || selectedId === null) return
    let cancelled = false
    const to = api.now()
    api
      .route(token, selectedId, startOfToday(to), to)
      .then((ps) => {
        if (!cancelled) dispatchSelected({ type: 'track', deviceId: selectedId, positions: ps })
      })
      .catch(fail)
    return () => {
      cancelled = true
    }
  }, [api, token, trackOn, selectedId, refreshKey, fail])

  // 告警和轨迹按设备号存，换孩子不用清：读的时候不是这台的就当还没拉到；点已选中的孩子什么都不变
  const select = useCallback((id: number) => {
    setChosenId(id)
    saveDeviceId(id)
  }, [])

  const setTrackOnAndClear = useCallback((on: boolean) => {
    setTrackOn(on)
    if (!on) dispatchSelected({ type: 'trackOff' })
  }, [])

  const refresh = useCallback(() => {
    setNow(api.now())
    setRefreshKey((k) => k + 1)
  }, [api])

  const reloadEvents = useCallback(() => setEventsKey((k) => k + 1), [])

  // 按「现在」滚动：事件只留 24 小时，轨迹只留今天；每 30 秒的「现在」才重算，地图上的轨迹线不会每次渲染都重画
  const visibleEvents = useMemo(() => eventsOf(selected, selectedId, now), [selected, selectedId, now])
  const visibleTrack = useMemo(() => (trackOn ? trackOf(selected, selectedId, now) : null), [selected, selectedId, now, trackOn])

  return {
    loaded,
    devices,
    selectedId,
    positions,
    history,
    position: selectedId === null ? null : (positions.get(selectedId) ?? null),
    selectedHistory: (selectedId === null ? undefined : history.get(selectedId)) ?? EMPTY_HISTORY,
    events: visibleEvents,
    eventsAsOf: eventsAsOf(selected, selectedId, { live, liveUntil }, now),
    eventsError: eventsErrorOf(selected, selectedId),
    eventPositions,
    trackOn,
    track: visibleTrack,
    live,
    error,
    syncedAt,
    now,
    updating,
    slow,
    select,
    setTrackOn: setTrackOnAndClear,
    refresh,
    reloadEvents,
  }
}
