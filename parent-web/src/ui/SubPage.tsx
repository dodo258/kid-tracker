// 二级页的壳（route.ts）：整页盖在地图和卡片上面，从右边滑入（跟手、无回弹，系统开了「减少动态效果」就直接出现），
// 左上角「返回」，标题居中。内容多了在页里滚。
// 打开时焦点移进来（背景由 App 设成 inert，Tab 不会跑到被盖住的抽屉上），关掉时还给打开它的入口：
// 用键盘打开的（入口带着键盘焦点框）移到「返回」；手指点开的移到整页上，不在「返回」上画焦点框。
import { ChevronLeft } from 'lucide-react'
import { useEffect, useRef, type ReactNode } from 'react'

type Props = { title: string; onBack: () => void; children: ReactNode }

export function SubPage({ title, onBack, children }: Props) {
  const backRef = useRef<HTMLButtonElement>(null)
  const pageRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const opener = document.activeElement
    const inside = pageRef.current?.contains(opener) ?? false
    const byKeyboard = opener instanceof HTMLElement && opener !== document.body && !inside && opener.matches(':focus-visible')
    ;(byKeyboard ? backRef.current : pageRef.current)?.focus()
    return () => {
      if (opener instanceof HTMLElement && opener.isConnected && !inside) opener.focus()
    }
  }, [])
  return (
    <div ref={pageRef} tabIndex={-1} className="sub-page fixed inset-0 z-[2000] flex flex-col bg-paper outline-none" role="dialog" aria-modal aria-label={title}>
      <header className="relative flex h-11 shrink-0 items-center justify-center px-2 pt-[env(safe-area-inset-top)] box-content">
        <button ref={backRef} type="button" onClick={onBack} className="absolute left-1 flex h-11 items-center gap-0.5 rounded-control pr-2 text-name text-ink active:bg-press">
          <ChevronLeft size={24} strokeWidth={2} aria-hidden />
          返回
        </button>
        <h1 className="max-w-[calc(100%-180px)] truncate text-name font-semibold text-ink">{title}</h1>
      </header>
      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 pb-[max(24px,env(safe-area-inset-bottom))]">{children}</div>
    </div>
  )
}

/** 二级页里的一组「左名右值」行 */
export function Rows({ rows }: { rows: { label: string; value: ReactNode }[] }) {
  return (
    <dl>
      {rows.map((r) => (
        <div key={r.label} className="flex gap-4 border-b border-line py-3 last:border-b-0">
          <dt className="w-[5.5em] shrink-0 text-body text-mute">{r.label}</dt>
          <dd className="min-w-0 flex-1 break-words text-body text-ink tabular-nums">{r.value}</dd>
        </div>
      ))}
    </dl>
  )
}

/** 二级页里一组的小标题 */
export function GroupTitle({ children }: { children: ReactNode }) {
  return <h2 className="mt-6 mb-1 text-note text-mute">{children}</h2>
}
