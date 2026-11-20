// 选点模式顶部的搜索框：边输边出高德输入提示（限定孩子所在城市），点一条地图飞过去、图钉对准它。
// 提示里只是一个类别（没有坐标）的，用关键字搜索再查一次取第一个。坐标是 GCJ-02，交给 spotFromAmap 转。
import { Search, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import type { AmapPlace, Api } from '../api/types'
import { spotFromAmap, type Spot } from '../domain/pick'

/** 停止输入这么久才查，少打几次接口 */
const TYPE_DELAY_MS = 300
const MAX_TIPS = 6

type Props = {
  api: Api
  token: string
  /** 孩子所在城市（regeo 取的），还没有就全国搜 */
  city: string | null
  onChoose: (spot: Spot) => void
  /** 截图用：打开时就带着这个词和提示 */
  initialQuery?: string
}

type Result = { q: string; list: AmapPlace[] } | { q: string; error: string }

export function PlaceSearch({ api, token, city, onChoose, initialQuery = '' }: Props) {
  const [query, setQuery] = useState(initialQuery)
  const [open, setOpen] = useState(initialQuery !== '')
  const [result, setResult] = useState<Result | null>(null)
  const [choosing, setChoosing] = useState(false)
  const [chooseError, setChooseError] = useState<string | null>(null)
  const box = useRef<HTMLDivElement>(null)
  const input = useRef<HTMLInputElement>(null)
  const q = query.trim()

  useEffect(() => {
    if (!open || q === '') return
    let cancelled = false
    const timer = setTimeout(() => {
      api
        .amapTips(token, q, city)
        .then((list) => {
          if (!cancelled) setResult({ q, list: list.slice(0, MAX_TIPS) })
        })
        .catch((err: unknown) => {
          console.warn('搜地址失败', err)
          if (!cancelled) setResult({ q, error: '搜索暂时用不了，可以直接拖地图选' })
        })
    }, TYPE_DELAY_MS)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [api, token, city, q, open])

  // 点搜索框外面（地图、底部卡片）收起提示
  useEffect(() => {
    if (!open) return
    const onDown = (e: PointerEvent) => {
      if (e.target instanceof Node && !box.current?.contains(e.target)) setOpen(false)
    }
    document.addEventListener('pointerdown', onDown)
    return () => document.removeEventListener('pointerdown', onDown)
  }, [open])

  const choose = async (p: AmapPlace) => {
    setChooseError(null)
    setChoosing(true)
    try {
      const location = p.location ?? (await api.amapSearch(token, p.name, city)).find((x) => x.location)?.location ?? null
      if (!location) {
        setChooseError(`没找到「${p.name}」在哪，换个说法试试`)
        return
      }
      setQuery(p.name)
      setOpen(false)
      input.current?.blur()
      onChoose(spotFromAmap(location))
    } catch (err) {
      console.warn('查地点坐标失败', err)
      setChooseError('搜索暂时用不了，可以直接拖地图选')
    } finally {
      setChoosing(false)
    }
  }

  const current = result?.q === q ? result : null
  const showList = open && q !== ''

  return (
    <div ref={box} className="pointer-events-auto w-full">
      <div className="flex h-11 items-center gap-2 rounded-control bg-paper px-3 shadow-card">
        <Search size={17} strokeWidth={2} className="shrink-0 text-mute" aria-hidden />
        <input
          ref={input}
          type="search"
          value={query}
          onChange={(e) => {
            setQuery(e.target.value)
            setOpen(true)
            setChooseError(null)
          }}
          onFocus={() => setOpen(true)}
          onKeyDown={(e) => {
            if (e.key === 'Escape' && open) {
              // 只收起提示，不退出选点
              e.stopPropagation()
              setOpen(false)
            }
          }}
          placeholder="搜地址、学校、小区"
          aria-label="搜地址、学校、小区"
          enterKeyHint="search"
          autoComplete="off"
          className="min-w-0 flex-1 bg-transparent text-[16px] text-ink outline-none placeholder:text-mute [&::-webkit-search-cancel-button]:hidden"
        />
        {query !== '' && (
          <button
            type="button"
            onClick={() => {
              setQuery('')
              setChooseError(null)
              input.current?.focus()
            }}
            aria-label="清空"
            className="-mr-1 flex size-7 shrink-0 items-center justify-center rounded-full text-mute active:bg-press"
          >
            <X size={16} strokeWidth={2.2} />
          </button>
        )}
      </div>

      {showList && (
        <div className="mt-1.5 max-h-[50dvh] overflow-y-auto rounded-control bg-paper shadow-card" role="listbox" aria-label="搜索提示">
          {chooseError ? (
            <p className="px-4 py-3 text-body text-jing">{chooseError}</p>
          ) : !current ? (
            <p className="px-4 py-3 text-body text-mute">正在搜…</p>
          ) : 'error' in current ? (
            <p className="px-4 py-3 text-body text-mute">{current.error}</p>
          ) : current.list.length === 0 ? (
            <p className="px-4 py-3 text-body text-mute">没找到，换个说法试试</p>
          ) : (
            <ul>
              {current.list.map((p, i) => (
                <li key={`${i}-${p.name}`} className="border-b border-line last:border-b-0">
                  <button
                    type="button"
                    role="option"
                    aria-selected={false}
                    disabled={choosing}
                    onClick={() => void choose(p)}
                    className="block w-full px-4 py-2.5 text-left active:bg-press disabled:opacity-60"
                  >
                    <span className="block truncate text-body text-ink">{p.name}</span>
                    {p.detail && <span className="block truncate text-note text-mute">{p.detail}</span>}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}
