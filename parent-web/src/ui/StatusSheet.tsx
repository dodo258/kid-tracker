// 底部状态卡，两档（方案 docs/specs/2026-11-18-parent-web-polish.md「卡片」）：
// - 收起（默认）：名字和电量（ui/Battery）；一句话状态（全页最大的字，domain/status）；在哪 + 多久前。
// - 展开（上拉或点把手）：两个文字按钮「今天的轨迹」「设为常去地点」，下面四个入口行（告警、常去地点、这次定位的详情、设置），
//   点了整页滑入（route.ts），不在卡片里展开。
// 拖把手或卡片上下拖，松手按位置和速度吸附到一档（sheetSnap）；点把手切换；收起时点卡片展开；电脑上滚轮一次挪一档、Esc 收起。
import { ChevronRight } from 'lucide-react'
import { useEffect, useLayoutEffect, useRef, useState, type PointerEvent, type ReactNode, type WheelEvent } from 'react'
import type { Geofence, Position, Snap, TraccarEvent } from '../api/types'
import { isPlaceEvent } from '../domain/alarms'
import type { Presence } from '../domain/presence'
import { statusLine, whereLine, type Tone } from '../domain/status'
import { clockLabel } from '../domain/time'
import type { History } from '../domain/track'
import { eventsStale } from '../state/selected'
import { Battery } from './Battery'
import { describeEvent } from './events'
import type { Page } from './route'
import { settleSnap, stepSnap, tapSnap, type SnapHeights } from './sheetSnap'

/** 卡片最高占屏幕这么多 */
const MAX_SHEET = 0.8
/** 把手那一条的高度（h-5） */
const HANDLE_PX = 20
/** 手指挪这么多像素以上才算拖，不然是点 */
const SLOP_PX = 6
/** 滚轮、触控板累计这么多才挪一档 */
const WHEEL_STEP = 40
const LOADING = '正在加载…'
// Tailwind 只认完整类名，不能拼字符串
const DOT: Record<Tone, string> = { an: 'bg-an', calm: 'bg-mute', jing: 'bg-jing' }
const TEXT: Record<Tone, string> = { an: 'text-ink', calm: 'text-ink', jing: 'text-jing' }

type Props = {
  name: string
  /** 最新一条上报（状态、电量、最后联系按它） */
  position: Position | null
  /** 地图实际采信的那个定位点（地址、定位方式和误差按它）；null 是手机还从没定到过位置 */
  fix: Position | null
  /** 位置可能没跟上（domain/lagging）时第三行写的提示，代替地址；平时 null */
  lagging?: string | null
  /** 孩子现在所在的常去地点 */
  place: Geofence | undefined
  /** 这台设备的常去地点；还没拉到是 null */
  places: Geofence[] | null
  presence: Presence
  /** 最近 30 分钟的点和这一轮静止的起点（「刚停下」「静止多久」） */
  history: Pick<History, 'reports' | 'fixes' | 'still'>
  /** 家长这边连不上服务器时最后一次同步的时刻 */
  offlineSince: Date | null
  /** 刚回到前台、还在拉最新数据 */
  updating: boolean
  /** 刷新 10 秒还没结果 */
  slow?: boolean
  now: Date
  /** 最近 24 小时的事件；还没拉到是 null */
  events: TraccarEvent[] | null
  /** 告警核实到哪一刻；还没查成功过是 null */
  eventsAsOf: Date | null
  eventPositions: Map<number, Position>
  error: string | null
  trackOn: boolean
  snap: Snap
  onSnap: (snap: Snap) => void
  onToggleTrack: () => void
  onAddPlace: () => void
  /** 打开二级页 */
  onOpen: (page: Page) => void
  /** 家长这边断网时点「点这里重连」 */
  onReconnect: () => void
}

/** 一次拖动：起点、开始时露出的内容高度和档、最近几次移动（算松手速度） */
type Drag = { y: number; height: number; from: Snap; onHandle: boolean; inBody: boolean; moved: boolean; samples: [number, number][] }

/** 松手前约 100 毫秒内的速度，像素/毫秒，往上拉为正 */
function releaseVelocity(samples: readonly [number, number][]): number {
  if (samples.length < 2) return 0
  const [t0, y0] = samples[0]
  const [t1, y1] = samples[samples.length - 1]
  return t1 > t0 ? (y0 - y1) / (t1 - t0) : 0
}

/** 展开后的入口行：左边名字，右边小字和箭头 */
function EntryRow({ label, note, urgent, onClick }: { label: string; note?: ReactNode; urgent?: boolean; onClick: () => void }) {
  return (
    <li className="border-t border-line">
      <button type="button" onClick={onClick} className="-mx-5 flex h-[52px] w-[calc(100%+40px)] items-center gap-3 px-5 text-left active:bg-press">
        <span className="shrink-0 text-body text-ink">{label}</span>
        <span className={`min-w-0 flex-1 truncate text-right text-note ${urgent ? 'text-jing' : 'text-mute'}`}>{note}</span>
        <ChevronRight size={18} strokeWidth={2} className="shrink-0 text-mute" aria-hidden />
      </button>
    </li>
  )
}

/** 展开后的文字按钮（不是灰底块）：细线框，按下才有底色 */
function TextButton({ children, active, disabled, onClick }: { children: ReactNode; active?: boolean; disabled?: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-pressed={active}
      className={`h-11 flex-1 rounded-control border text-body font-medium text-ink active:bg-press disabled:opacity-40 ${active ? 'border-an' : 'border-line'}`}
    >
      {children}
    </button>
  )
}

/** 告警入口右边的小字：「24 小时 7 条」；失联时写最近一次告警（关机、低电），警色；核实得不够新写「只更新到 几点」 */
function alertsNote(
  events: TraccarEvent[] | null,
  asOf: Date | null,
  lost: boolean,
  eventPositions: Map<number, Position>,
  places: Geofence[],
  now: Date,
) {
  if (events === null) return { text: LOADING, urgent: false }
  const latest = lost ? (events.find((e) => e.type === 'alarm') ?? events.find((e) => !isPlaceEvent(e.type))) : undefined
  if (latest) return { text: `${clockLabel(latest.eventTime, now)} ${describeEvent(latest, eventPositions, places, false)}`, urgent: true }
  if (asOf && eventsStale(asOf, now)) return { text: `只更新到 ${clockLabel(asOf, now)}`, urgent: false }
  return { text: events.length === 0 ? '24 小时没有' : `24 小时 ${events.length} 条`, urgent: false }
}

export function StatusSheet(props: Props) {
  const { name, position, place, places, presence, offlineSince, now, events, error, trackOn, snap, onSnap, onOpen } = props

  // 两档各露出多少内容：收起到第一块的底、展开是全部（最高 MAX_SHEET 屏）。内容一变（新的点、告警拉回来）重新量
  const sectionRef = useRef<HTMLElement>(null)
  const bodyRef = useRef<HTMLDivElement>(null)
  const contentRef = useRef<HTMLDivElement>(null)
  const peekRef = useRef<HTMLDivElement>(null)
  const [sizes, setSizes] = useState<SnapHeights | null>(null)
  useLayoutEffect(() => {
    const section = sectionRef.current
    const content = contentRef.current
    const peekEl = peekRef.current
    if (!section || !content || !peekEl) return
    const measure = () => {
      const pad = parseFloat(getComputedStyle(section).paddingBottom) || 0
      const peek = peekEl.offsetHeight
      const max = Math.max(peek, window.innerHeight * MAX_SHEET - HANDLE_PX - pad)
      const next = { peek, open: Math.min(max, content.offsetHeight) }
      setSizes((s) => (s && s.peek === next.peek && s.open === next.open ? s : next))
    }
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(content)
    window.addEventListener('resize', measure)
    return () => {
      ro.disconnect()
      window.removeEventListener('resize', measure)
    }
  }, [])

  // 收起时内容滚回顶上：展开时滚到下面再收起，露出的那一截要是名字和状态，不是入口
  useLayoutEffect(() => {
    if (snap === 'peek' && bodyRef.current) bodyRef.current.scrollTop = 0
  }, [snap])

  // 拖动时露出的高度跟着手指走（不加过渡），松手吸附到一档再用过渡滑过去
  const [dragHeight, setDragHeight] = useState<number | null>(null)
  const drag = useRef<Drag | null>(null)
  const scrollable = () => {
    const body = bodyRef.current
    return snap === 'open' && body !== null && body.scrollHeight > body.clientHeight
  }
  const onPointerDown = (e: PointerEvent<HTMLElement>) => {
    const target = e.target as HTMLElement
    if (!sizes || target.closest('button, a, input')) return
    const body = bodyRef.current
    const inBody = body?.contains(target) ?? false
    // 展开、内容在滚中间时，鼠标拖不动卡片（滚轮滚内容）；手指由浏览器自己滚
    if (inBody && scrollable() && (body?.scrollTop ?? 0) > 0) return
    drag.current = {
      y: e.clientY,
      height: sizes[snap],
      from: snap,
      onHandle: target.closest('[data-sheet-handle]') !== null,
      inBody,
      moved: false,
      samples: [[e.timeStamp, e.clientY]],
    }
    e.currentTarget.setPointerCapture(e.pointerId)
  }
  const onPointerMove = (e: PointerEvent<HTMLElement>) => {
    const d = drag.current
    if (!d || !sizes) return
    const dy = e.clientY - d.y
    if (!d.moved) {
      if (Math.abs(dy) < SLOP_PX) return
      // 展开、内容能滚时往上推是滚内容，不是拖卡片
      if (d.inBody && scrollable() && dy < 0) {
        drag.current = null
        return
      }
      d.moved = true
    }
    d.samples = [...d.samples.filter(([t]) => e.timeStamp - t < 100), [e.timeStamp, e.clientY]]
    setDragHeight(Math.min(sizes.open, Math.max(sizes.peek, d.height - dy)))
  }
  const onPointerUp = (e: PointerEvent<HTMLElement>) => {
    const d = drag.current
    drag.current = null
    if (!d || !sizes) return
    setDragHeight(null)
    if (d.moved) {
      const height = Math.min(sizes.open, Math.max(sizes.peek, d.height - (e.clientY - d.y)))
      onSnap(settleSnap(sizes, height, releaseVelocity(d.samples), d.from))
    } else if (d.onHandle) onSnap(tapSnap(snap))
    else if (snap === 'peek') onSnap('open') // 收起时点卡片任何地方都展开
  }
  const onPointerCancel = () => {
    drag.current = null
    setDragHeight(null)
  }

  // 手机上展开、内容滚到顶以后再往下拉：拦下浏览器的滚动，交给上面的拖动收卡片（React 的 touch 事件是被动的，拦不住，自己挂）
  useEffect(() => {
    const body = bodyRef.current
    if (!body) return
    const onTouchMove = (e: TouchEvent) => {
      const d = drag.current
      if (!d || !d.inBody || body.scrollTop > 0) return
      if (d.moved || e.touches[0].clientY > d.y) e.preventDefault()
    }
    body.addEventListener('touchmove', onTouchMove, { passive: false })
    return () => body.removeEventListener('touchmove', onTouchMove)
  }, [])

  // 电脑上在卡片上滚滚轮、触控板双指上下滑：往上推展开、往下拉收起；展开时先滚卡片里的内容，滚到顶再往下拉才收
  const wheel = useRef({ sum: 0, at: 0 })
  const onWheel = (e: WheelEvent<HTMLElement>) => {
    const body = bodyRef.current
    if (body?.contains(e.target as Node) && scrollable() && (body.scrollTop > 0 || e.deltaY > 0)) return
    const w = wheel.current
    if (e.timeStamp - w.at > 300) w.sum = 0
    w.at = e.timeStamp
    w.sum += e.deltaY
    if (Math.abs(w.sum) < WHEEL_STEP) return
    const next = stepSnap(snap, w.sum > 0 ? 1 : -1)
    w.sum = 0
    if (next !== snap) onSnap(next)
  }

  // 电脑上按 Esc 收起
  useEffect(() => {
    if (snap === 'peek') return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onSnap('peek')
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [snap, onSnap])

  const battery = position?.batteryLevel
  const lost = presence.freshness === 'lost' && !offlineSince
  const status = position
    ? statusLine({ position, presence, history: props.history, placeName: place?.name, offlineSince, updating: props.updating, slow: props.slow, now })
    : null
  const line = position ? whereLine(position, props.fix, place?.name, lost, now) : null
  const where = props.lagging ?? line?.where ?? null
  const ago = line && !props.updating && !offlineSince ? line.ago : null
  const alerts = alertsNote(events, props.eventsAsOf, lost, props.eventPositions, places ?? [], now)

  const height = dragHeight ?? sizes?.[snap]
  const settling = dragHeight === null
  const bodyScroll = snap === 'open' && settling

  return (
    <section
      ref={sectionRef}
      className="pointer-events-auto mx-auto w-full max-w-[480px] touch-none select-none rounded-t-card bg-paper px-5 pb-[max(14px,env(safe-area-inset-bottom))] shadow-card"
      aria-label="孩子的状态"
      data-snap={snap}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerCancel}
      onWheel={onWheel}
    >
      <div
        data-sheet-handle
        role="button"
        tabIndex={0}
        aria-label={snap === 'peek' ? '展开' : '收起'}
        aria-expanded={snap !== 'peek'}
        onKeyDown={(e) => {
          if (e.key !== 'Enter' && e.key !== ' ') return
          e.preventDefault()
          onSnap(tapSnap(snap))
        }}
        className="-mx-5 flex h-5 cursor-grab items-center justify-center rounded-t-card outline-none focus-visible:bg-press"
      >
        <span className="h-[5px] w-9 rounded-full bg-line" />
      </div>

      <div
        ref={bodyRef}
        style={{ height }}
        className={`relative ${bodyScroll ? 'touch-pan-y overflow-y-auto overscroll-contain' : 'overflow-hidden'} ${
          settling ? 'transition-[height] duration-300 ease-[cubic-bezier(0.2,0.8,0.2,1)] motion-reduce:transition-none' : ''
        }`}
      >
        <div ref={contentRef}>
          {/* 收起时露出的三行 */}
          <div ref={peekRef} className="pb-1">
            <div className="flex items-baseline justify-between gap-3">
              <h1 className="min-w-0 truncate text-name font-semibold text-ink">{name}</h1>
              {battery !== undefined && <Battery level={battery} charging={position?.charging} />}
            </div>

            {position && status ? (
              <>
                <p className={`mt-1 flex min-w-0 items-center gap-2 text-status font-semibold ${TEXT[status.tone]}`}>
                  {/* 前缀小圆点：在路上、刚停下、静止是安（在路上实时时呼吸），失联是警，手机时间不对、位置旧了、正在更新是灰 */}
                  <span className={`relative inline-block size-2 shrink-0 rounded-full ${DOT[status.tone]} ${status.pulse ? 'live-dot' : ''}`} />
                  <span className="min-w-0 truncate tabular-nums" title={status.text}>{status.text}</span>
                </p>
                {offlineSince ? (
                  <button type="button" onClick={props.onReconnect} className="mt-0.5 block w-full truncate text-left text-body text-mute underline-offset-2 active:underline">
                    最后看到 {clockLabel(offlineSince, now)} · 点这里重连
                  </button>
                ) : (
                  <div className="mt-0.5 flex items-baseline gap-3">
                    {/* 位置可能没跟上的提示要看得到后面的地址：最多两行，不截成一行 */}
                    <span className={`min-w-0 flex-1 text-body text-ink ${props.lagging || line?.wrap ? 'line-clamp-2' : 'truncate'}`} title={where ?? undefined}>
                      {where}
                    </span>
                    {ago && <span className="shrink-0 text-note text-mute tabular-nums">{ago}</span>}
                  </div>
                )}
              </>
            ) : (
              <>
                <p className="mt-1 text-status font-semibold text-ink">还没有收到位置</p>
                <p className="mt-0.5 text-body text-mute">孩子手机上打开 App 点「开始」后，这里会出现。</p>
              </>
            )}

            {error && !offlineSince && <p className="mt-1 text-note text-mute">{error}，30 秒后自动重试</p>}
          </div>

          {/* 展开时再露出：两个按钮、四个入口 */}
          <div inert={snap === 'peek' && settling}>
            <div className="mt-3 flex gap-2.5">
              <TextButton active={trackOn} onClick={props.onToggleTrack}>
                {trackOn ? '收起今天的轨迹' : '今天的轨迹'}
              </TextButton>
              <TextButton disabled={!props.fix} onClick={props.onAddPlace}>
                设为常去地点
              </TextButton>
            </div>
            <ul className="mt-3">
              <EntryRow label="告警" note={alerts.text} urgent={alerts.urgent} onClick={() => onOpen('alerts')} />
              <EntryRow label="常去地点" note={places === null ? LOADING : `${places.length} 个`} onClick={() => onOpen('places')} />
              {position && <EntryRow label="这次定位的详情" onClick={() => onOpen('details')} />}
              <EntryRow label="设置" onClick={() => onOpen('settings')} />
            </ul>
          </div>
        </div>
      </div>
    </section>
  )
}
