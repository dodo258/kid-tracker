// 「常去地点」二级页：列表，点一行回到地图打开这个地点的卡片（改名、改范围、删除）
import type { Geofence } from '../api/types'
import { PlaceList } from './PlaceList'

type Props = { places: Geofence[] | null; onOpen: (id: number) => void }

export function PlacesPage({ places, onOpen }: Props) {
  if (places === null) return <p className="py-4 text-body text-mute">正在加载…</p>
  return (
    <div className="pt-1">
      <PlaceList places={places} onOpen={onOpen} />
      {places.length > 0 && <p className="mt-4 text-note text-mute">添加：在地图上长按一处（电脑上右键），或点卡片上的「设为常去地点」。到达和离开会记在「告警」里。</p>}
    </div>
  )
}
