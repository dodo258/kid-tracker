// 「最近 24 小时」二级页：告警、失联、到达和离开常去地点，新的在上面。
// 告警核实得不够新（推送不通又没查到、查询失败）时，顶上写「只更新到 几点」和重试，空列表也不说「没有事」
import type { Geofence, Position, TraccarEvent } from '../api/types'
import { isUrgent } from '../domain/alarms'
import { clockLabel } from '../domain/time'
import { eventsStale } from '../state/selected'
import { describeEvent } from './events'

type Props = {
  events: TraccarEvent[] | null
  /** 告警核实到哪一刻；还没查成功过是 null */
  asOf: Date | null
  /** 最近一次查询的错误 */
  error: string | null
  eventPositions: Map<number, Position>
  places: Geofence[]
  now: Date
  onRetry: () => void
}

export const EMPTY_TEXT = '24 小时里没有事。低电、关机、定位被关、求救、失联，到达和离开常去地点，都会列在这里。'

function Notice({ text, onRetry }: { text: string; onRetry: () => void }) {
  return (
    <div className="mt-2 flex items-center gap-3 rounded-control bg-press px-3 py-2 text-body text-ink">
      <span className="min-w-0 flex-1">{text}</span>
      <button type="button" onClick={onRetry} className="h-9 shrink-0 rounded-full border border-line px-4 text-body text-ink active:bg-paper">
        重试
      </button>
    </div>
  )
}

export function AlertsPage({ events, asOf, error, eventPositions, places, now, onRetry }: Props) {
  if (events === null) {
    if (error) return <Notice text={`告警没查到：${error}`} onRetry={onRetry} />
    return <p className="py-4 text-body text-mute">正在加载…</p>
  }
  const stale = eventsStale(asOf, now)
  const until = asOf ? clockLabel(asOf, now) : null
  return (
    <>
      {stale && <Notice text={`${until ? `只更新到 ${until}` : '还没核实'}，之后的告警还没查到${error ? `（${error}）` : ''}`} onRetry={onRetry} />}
      {events.length === 0 ? (
        <p className="py-4 text-body text-mute">{stale ? `到 ${until ?? '上次查询'} 为止，24 小时里没有事。` : EMPTY_TEXT}</p>
      ) : (
        <ul>
          {events.map((e) => (
            <li key={e.id} className="flex gap-4 border-b border-line py-3 text-body last:border-b-0">
              <span className="w-[5.5em] shrink-0 text-mute tabular-nums">{clockLabel(e.eventTime, now)}</span>
              <span className={isUrgent(e.type, e.alarm) ? 'font-semibold text-jing' : 'text-ink'}>{describeEvent(e, eventPositions, places)}</span>
            </li>
          ))}
        </ul>
      )}
    </>
  )
}
