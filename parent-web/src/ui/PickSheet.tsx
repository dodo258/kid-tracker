// 选点模式的底部卡片：地图中间固定一个图钉（MapView 画），家长拖动地图把图钉对准地方，「用这里」回到「设为常去地点」。
// 卡片里：图钉位置的地址、范围（圈实时变）、「我的位置」（家长人就在那儿时直接用手机定位）。
// 地址那一行始终占位：地址来了、出错提示都不改变卡片高度，图钉（对准卡片上面那块的正中）不会跟着跳。
import { LocateFixed } from 'lucide-react'
import { useEffect } from 'react'
import { RadiusPicker } from './RadiusPicker'

type Props = {
  /** 图钉位置的地址；没有（还没查到、查失败）就显示操作提示 */
  address: string | null
  /** 「我的位置」拿不到时的提示，优先于地址显示 */
  notice: string | null
  radius: number
  onRadius: (r: number) => void
  onMyLocation: () => void
  locating: boolean
  onUse: () => void
  /** 回到「设为常去地点」，圆心不变 */
  onCancel: () => void
}

export function PickSheet({ address, notice, radius, onRadius, onMyLocation, locating, onUse, onCancel }: Props) {
  // 电脑上 Esc 回去（搜索框里的 Esc 只收起提示，不会传到这里）
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onCancel])

  return (
    <section
      className="pointer-events-auto mx-auto w-full max-w-[480px] rounded-t-card bg-paper px-5 pt-4 pb-[max(16px,env(safe-area-inset-bottom))] shadow-card"
      aria-label="在地图上选位置"
    >
      <div className="flex items-start justify-between gap-3">
        <h2 className="text-name font-semibold leading-snug text-ink">拖动地图，让图钉对准地方</h2>
        <button
          type="button"
          onClick={onMyLocation}
          disabled={locating}
          className="-my-0.5 flex shrink-0 items-center gap-1 rounded-full bg-press py-1.5 pr-3 pl-2.5 text-note font-medium text-an active:bg-press disabled:opacity-50"
        >
          <LocateFixed size={15} strokeWidth={2.2} aria-hidden />
          {locating ? '正在定位…' : '我的位置'}
        </button>
      </div>
      <p className={`mt-0.5 truncate text-note ${notice ? 'text-jing' : 'text-mute'}`} aria-live="polite" title={notice ?? address ?? undefined}>
        {notice ?? (address ? `图钉位置：${address}` : '双指可以放大缩小，圈里就算到了')}
      </p>

      <p className="mt-3 text-note font-medium text-mute" id="pick-radius">
        范围
      </p>
      <div className="mt-1.5">
        <RadiusPicker value={radius} onChange={onRadius} labelledBy="pick-radius" />
      </div>

      <div className="mt-4 flex gap-2">
        <button type="button" onClick={onCancel} className="flex-1 rounded-control border border-line py-3 text-body font-medium text-ink active:bg-press">
          取消
        </button>
        <button type="button" onClick={onUse} className="flex-1 rounded-control bg-an py-3 text-body font-semibold text-on-an">
          用这里
        </button>
      </div>
    </section>
  )
}
