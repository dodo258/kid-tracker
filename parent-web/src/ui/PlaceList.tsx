// 「常去地点」二级页的列表：每行名字和范围，点一行打开这个地点的卡片（改名、改范围、删除都在卡片里）。
import { ChevronRight } from 'lucide-react'
import type { Geofence } from '../api/types'
import { parseCircle } from '../domain/places'

type Props = { places: Geofence[]; onOpen: (id: number) => void }

export function PlaceList({ places, onOpen }: Props) {
  if (places.length === 0) {
    return <p className="py-2 text-body text-mute">还没有。点「设为常去地点」，或在地图上长按（电脑上右键）一处，存成家、学校，到达和离开会记在「告警」里。</p>
  }
  return (
    <ul>
      {places.map((p) => {
        const radius = parseCircle(p.area)?.radius
        return (
          <li key={p.id} className="border-b border-line last:border-b-0">
            <button type="button" onClick={() => onOpen(p.id)} className="flex min-h-11 w-full items-center gap-3 py-1.5 text-left text-body active:bg-press">
              <span className="min-w-0 flex-1 truncate text-ink">{p.name}</span>
              {radius !== undefined && <span className="shrink-0 text-mute tabular-nums">{Math.round(radius)} 米内</span>}
              <ChevronRight size={17} className="shrink-0 text-mute" aria-hidden />
            </button>
          </li>
        )
      })}
    </ul>
  )
}
