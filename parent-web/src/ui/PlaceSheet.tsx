// 「设为常去地点」：替换底部状态卡的表单。圆心默认是孩子现在的位置，「换个位置」去地图上选别处（或者直接长按、右键地图）；
// 填名字（家、学校两个快捷选项或自己写）、选半径，地图上同时画出预览的圈。保存时建 Traccar 地理围栏并关联到这台设备。
import { errorText } from '../api/errorText'
import { MapPin } from 'lucide-react'
import { useEffect, useState } from 'react'
import { RadiusPicker } from './RadiusPicker'

const QUICK_NAMES = ['家', '学校']
/** Traccar 的名字列够长；这里限制在一行放得下 */
const MAX_NAME = 20

type Props = {
  kidName: string
  /** 圆心是在地图上选的（不是孩子现在的位置） */
  pickedOnMap: boolean
  /** 名字放在外面，去选点再回来不丢 */
  name: string
  onName: (name: string) => void
  radius: number
  onRadius: (r: number) => void
  /** 「换个位置」：进选点模式 */
  onPick: () => void
  onSave: (name: string) => Promise<void>
  onCancel: () => void
}

function Chip({ selected, onClick, children }: { selected: boolean; onClick: () => void; children: string }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={selected}
      className={`rounded-control px-4 py-2 text-body font-medium transition-colors ${
        selected ? 'bg-an-soft text-an' : 'bg-press text-ink active:bg-press'
      }`}
    >
      {children}
    </button>
  )
}

export function PlaceSheet({ kidName, pickedOnMap, name, onName: setName, radius, onRadius, onPick, onSave, onCancel }: Props) {
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const trimmed = name.trim()

  // 电脑上按 Esc 取消
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onCancel])

  const save = async () => {
    if (!trimmed || saving) return
    setSaving(true)
    setError(null)
    try {
      await onSave(trimmed)
    } catch (err) {
      setError(errorText(err))
      setSaving(false)
    }
  }

  return (
    <section
      className="pointer-events-auto mx-auto w-full max-w-[480px] rounded-t-card bg-paper px-5 pt-5 pb-[max(16px,env(safe-area-inset-bottom))] shadow-card"
      aria-label="设为常去地点"
    >
      <div className="flex items-start justify-between gap-3">
        <h2 className="text-status font-semibold leading-tight text-ink">设为常去地点</h2>
        <button
          type="button"
          onClick={onPick}
          className="-my-1 flex shrink-0 items-center gap-1 rounded-full bg-press py-1.5 pr-3 pl-2.5 text-note font-medium text-an active:bg-press"
        >
          <MapPin size={15} strokeWidth={2.2} aria-hidden />
          换个位置
        </button>
      </div>
      <p className="mt-1 text-body text-mute">
        圆心是{pickedOnMap ? '地图上选的位置' : `${kidName}现在的位置`}。到达、离开会记在「最近 24 小时」里。
      </p>

      <form
        className="mt-4"
        onSubmit={(e) => {
          e.preventDefault()
          void save()
        }}
      >
        <label htmlFor="place-name" className="text-note font-medium text-mute">
          名字
        </label>
        <div className="mt-1.5 flex gap-2">
          {QUICK_NAMES.map((n) => (
            <Chip key={n} selected={trimmed === n} onClick={() => setName(n)}>
              {n}
            </Chip>
          ))}
          <input
            id="place-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            maxLength={MAX_NAME}
            placeholder="其他，如奶奶家"
            enterKeyHint="done"
            autoComplete="off"
            className="min-w-0 flex-1 rounded-control bg-press px-3 text-[16px] text-ink placeholder:text-mute focus:outline-2 focus:outline-an"
          />
        </div>

        <p className="mt-4 text-note font-medium text-mute" id="place-radius">
          范围
        </p>
        <div className="mt-1.5">
          <RadiusPicker value={radius} onChange={onRadius} labelledBy="place-radius" />
        </div>

        {error && <p className="mt-3 text-note text-jing">{error}</p>}

        <div className="mt-5 flex gap-2">
          <button type="button" onClick={onCancel} className="flex-1 rounded-control border border-line py-3 text-body font-medium text-ink active:bg-press">
            取消
          </button>
          <button
            type="submit"
            disabled={!trimmed || saving}
            className="flex-1 rounded-control bg-an py-3 text-body font-semibold text-on-an disabled:opacity-40"
          >
            {saving ? '正在保存…' : '保存'}
          </button>
        </div>
      </form>
    </section>
  )
}
