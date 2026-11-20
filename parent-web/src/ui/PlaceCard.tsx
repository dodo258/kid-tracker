// 一个常去地点的小卡片（点地图上的圈、圈上的名字，或常去地点列表里的一行打开）：名字、范围、地址，「改名」「改范围」「删除」。
// 改名、改范围是 PUT 同一个地理围栏（id 不变，关联的设备和到达/离开提醒都保留）；改范围时地图上的圈实时预览；
// 删除先确认：先查这个地点还用在哪些别的孩子身上，有就写出来，另给「只对 {这个孩子} 取消」（只去掉这一台的关联）。
import { PencilLine, Radius, Trash2, X } from 'lucide-react'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { errorText } from '../api/errorText'
import type { Geofence } from '../api/types'
import { radiusChoices, type Circle } from '../domain/places'
import { RadiusPicker } from './RadiusPicker'

/** 和「设为常去地点」一致 */
const MAX_NAME = 20

export type CardMode = 'view' | 'rename' | 'radius' | 'delete'

type Props = {
  place: Geofence
  /** 圆形的才有；完整后台画的多边形只能改名、删除 */
  circle: Circle | undefined
  /** 圆心的地址（高德逆地理），查不到不显示 */
  address: string | null
  onRename: (name: string) => Promise<void>
  /** 改范围：圆心不变，换半径 */
  onResize: (circle: Circle) => Promise<void>
  /** 改范围时地图上预览的半径，null = 不在改 */
  onPreview: (radius: number | null) => void
  onDelete: () => Promise<void>
  /** 正在看的孩子的名字（「只对小宝取消」） */
  kidName: string
  /** 这个地点还用在哪些别的孩子身上（名字） */
  sharedWith: () => Promise<string[]>
  /** 只对这个孩子取消 */
  onUnlink: () => Promise<void>
  onClose: () => void
  /** 截图用：打开时就在改范围，选的是这个半径 */
  initialMode?: CardMode
  initialRadius?: number
}

const BUTTON = 'flex-1 rounded-control py-3 text-body'

function Action({ label, danger, disabled, onClick, children }: { label: string; danger?: boolean; disabled?: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={`flex min-w-0 flex-1 flex-col items-center gap-1 rounded-control border border-line py-2.5 text-note font-medium active:bg-press disabled:opacity-40 ${danger ? 'text-jing' : 'text-ink'}`}
    >
      {children}
      {label}
    </button>
  )
}

export function PlaceCard(props: Props) {
  const { place, circle, address, onRename, onResize, onPreview, onDelete, kidName, sharedWith, onUnlink, onClose, initialMode = 'view', initialRadius } = props
  const [mode, setMode] = useState<CardMode>(initialMode)
  const [name, setName] = useState(place.name)
  const [radius, setRadius] = useState(initialRadius ?? Math.round(circle?.radius ?? 0))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  /** 删除确认时：这个地点还用在哪些别的孩子身上；正在查是 null */
  const [others, setOthers] = useState<string[] | null>(null)
  // 页面每次推来新位置都会重画，用 ref 拿最新的查询函数，只在进删除确认时查一次
  const sharedWithRef = useRef(sharedWith)
  useEffect(() => {
    sharedWithRef.current = sharedWith
  })

  // 进删除确认时查一次；查不到就按没有别人用处理（只是少一句提醒，删除本身照常确认）
  useEffect(() => {
    if (mode !== 'delete') return
    let cancelled = false
    sharedWithRef
      .current()
      .then((names) => {
        if (!cancelled) setOthers(names)
      })
      .catch((err: unknown) => {
        console.warn('查地点关联的设备失败', err)
        if (!cancelled) setOthers([])
      })
    return () => {
      cancelled = true
    }
  }, [mode])

  const go = (next: CardMode) => {
    setError(null)
    setName(place.name)
    setRadius(Math.round(circle?.radius ?? 0))
    onPreview(null)
    setOthers(null)
    setMode(next)
  }

  // Esc：在改就回到卡片，没在改就关掉
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      if (mode === 'view') onClose()
      else {
        setError(null)
        onPreview(null)
        setMode('view')
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [mode, onClose, onPreview])

  const run = async (work: () => Promise<void>, after: () => void) => {
    if (busy) return
    setBusy(true)
    setError(null)
    try {
      await work()
      after()
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  const trimmed = name.trim()
  const meta = [circle ? `${Math.round(circle.radius)} 米内` : null, address].filter(Boolean).join(' · ')

  return (
    <section
      className="pointer-events-auto mx-auto w-full max-w-[480px] rounded-t-card bg-paper px-5 pt-5 pb-[max(16px,env(safe-area-inset-bottom))] shadow-card"
      aria-label="常去地点"
    >
      <div className="flex items-start justify-between gap-3">
        <h2 className="min-w-0 truncate text-status font-semibold leading-tight text-ink">
          {mode === 'rename' ? '改名' : mode === 'radius' ? `改范围 · ${place.name}` : mode === 'delete' ? `删除「${place.name}」？` : place.name}
        </h2>
        {mode === 'view' && (
          <button type="button" onClick={onClose} aria-label="关闭" className="-mt-1 -mr-2 flex size-8 shrink-0 items-center justify-center rounded-full bg-press text-mute active:bg-press">
            <X size={17} strokeWidth={2.2} />
          </button>
        )}
      </div>

      {mode === 'view' && (
        <>
          {meta && (
            <p className="mt-1 truncate text-body text-mute tabular-nums" title={meta}>
              {meta}
            </p>
          )}
          <div className="mt-4 flex gap-2">
            <Action label="改名" onClick={() => go('rename')}>
              <PencilLine size={20} strokeWidth={2} />
            </Action>
            <Action label="改范围" disabled={!circle} onClick={() => go('radius')}>
              <Radius size={20} strokeWidth={2} />
            </Action>
            <Action label="删除" danger onClick={() => go('delete')}>
              <Trash2 size={20} strokeWidth={2} />
            </Action>
          </div>
        </>
      )}

      {mode === 'rename' && (
        <form
          className="mt-3"
          onSubmit={(e) => {
            e.preventDefault()
            if (trimmed && trimmed !== place.name) void run(() => onRename(trimmed), () => setMode('view'))
            else if (trimmed) setMode('view')
          }}
        >
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            maxLength={MAX_NAME}
            aria-label="名字"
            enterKeyHint="done"
            autoComplete="off"
            autoFocus
            className="h-11 w-full rounded-control bg-press px-3 text-[16px] text-ink placeholder:text-mute focus:outline-2 focus:outline-an"
          />
          {error && <p className="mt-3 text-note text-jing">{error}</p>}
          <div className="mt-4 flex gap-2">
            <button type="button" onClick={() => go('view')} className={`${BUTTON} border border-line font-medium text-ink active:bg-press`}>
              取消
            </button>
            <button type="submit" disabled={!trimmed || busy} className={`${BUTTON} bg-an font-semibold text-on-an disabled:opacity-40`}>
              {busy ? '正在保存…' : '保存'}
            </button>
          </div>
        </form>
      )}

      {mode === 'radius' && circle && (
        <div className="mt-3">
          <p className="text-body text-mute" id="card-radius">
            圆心不变，圈里就算到了
          </p>
          <div className="mt-2">
            <RadiusPicker
              value={radius}
              choices={radiusChoices(circle.radius)}
              onChange={(r) => {
                setRadius(r)
                onPreview(r)
              }}
              labelledBy="card-radius"
            />
          </div>
          {error && <p className="mt-3 text-note text-jing">{error}</p>}
          <div className="mt-4 flex gap-2">
            <button type="button" onClick={() => go('view')} className={`${BUTTON} border border-line font-medium text-ink active:bg-press`}>
              取消
            </button>
            <button
              type="button"
              disabled={busy}
              onClick={() => {
                if (radius === Math.round(circle.radius)) go('view')
                else
                  void run(
                    () => onResize({ ...circle, radius }),
                    () => {
                      onPreview(null)
                      setMode('view')
                    },
                  )
              }}
              className={`${BUTTON} bg-an font-semibold text-on-an disabled:opacity-40`}
            >
              {busy ? '正在保存…' : '保存'}
            </button>
          </div>
        </div>
      )}

      {mode === 'delete' && (
        <div className="mt-1">
          <p className="text-body text-ink">
            {others === null
              ? '正在查这个地点还用在谁身上…'
              : others.length > 0
                ? `这个地点也用在${others.join('、')}身上，删除后他们的到达/离开提醒也没了。`
                : '删除后到达/离开不再提醒。'}
          </p>
          {error && <p className="mt-3 text-note text-jing">{error}</p>}
          {others !== null && others.length > 0 && (
            <button
              type="button"
              disabled={busy}
              onClick={() => void run(onUnlink, onClose)}
              className={`mt-4 w-full ${BUTTON} min-w-0 truncate border border-line px-3 font-medium text-ink active:bg-press disabled:opacity-40`}
            >
              只对{kidName}取消
            </button>
          )}
          <div className={`${others !== null && others.length > 0 ? 'mt-2' : 'mt-4'} flex gap-2`}>
            <button type="button" onClick={() => go('view')} className={`${BUTTON} border border-line font-medium text-ink active:bg-press`}>
              取消
            </button>
            <button type="button" disabled={busy || others === null} onClick={() => void run(onDelete, onClose)} className={`${BUTTON} border border-line font-semibold text-jing active:bg-press disabled:opacity-40`}>
              {busy ? '正在删除…' : '删除'}
            </button>
          </div>
        </div>
      )}
    </section>
  )
}
