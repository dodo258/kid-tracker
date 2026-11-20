// 「这次定位的详情」二级页：地址全文、常去地点，加最近一次上报的各字段（domain/details，卡片上已经有的电量、速度不重复）
import type { Geofence, Position } from '../api/types'
import { detailRows } from '../domain/details'
import { Rows } from './SubPage'

type Props = { position: Position; place: Geofence | undefined; now: Date }

export function DetailsPage({ position, place, now }: Props) {
  const rows = [
    ...(position.address ? [{ label: '地址', value: position.address.trim() }] : []),
    ...(place ? [{ label: '常去地点', value: `在${place.name}范围内` }] : []),
    ...detailRows(position, now),
  ]
  return (
    <>
      <p className="pt-2 pb-1 text-note text-mute">孩子手机最近一次上报</p>
      <Rows rows={rows} />
    </>
  )
}
