// 范围分段选择：100 / 200 / 500 / 1000 米。「设为常去地点」、选位置、改范围共用；选了地图上的圈马上跟着变。
import { RADII } from '../domain/places'
import { Segmented } from './Segmented'

type Props = {
  value: number
  onChange: (r: number) => void
  /** 可选的半径，默认常用四档（改范围时会把现在的非常用值插进去） */
  choices?: readonly number[]
  /** 外面的标题元素 id，读屏用 */
  labelledBy: string
}

export function RadiusPicker({ value, onChange, choices = RADII, labelledBy }: Props) {
  return <Segmented value={value} onChange={onChange} options={choices.map((r) => ({ value: r, label: `${r} 米` }))} labelledBy={labelledBy} />
}
