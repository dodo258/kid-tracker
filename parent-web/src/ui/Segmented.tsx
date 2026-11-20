// 分段选择：一行几个选项选一个（范围 100 / 200 / 500 / 1000 米、设置里的底图和深浅色）。选中的那格是纸白底加细线，其余是灰字
type Props<T extends string | number> = {
  value: T
  onChange: (v: T) => void
  options: readonly { value: T; label: string }[]
  /** 外面的标题元素 id，读屏用 */
  labelledBy: string
}

export function Segmented<T extends string | number>({ value, onChange, options, labelledBy }: Props<T>) {
  return (
    <div className="flex gap-1 rounded-control bg-press p-1" role="radiogroup" aria-labelledby={labelledBy}>
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          role="radio"
          aria-checked={o.value === value}
          onClick={() => onChange(o.value)}
          className={`min-w-0 flex-1 truncate rounded-tag border py-1.5 text-body tabular-nums ${
            o.value === value ? 'border-line bg-paper font-semibold text-ink' : 'border-transparent text-mute'
          }`}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}
