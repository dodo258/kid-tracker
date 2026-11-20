// 「设置」二级页：地图底图、深浅色、完整后台、退出登录、版本号。加载中、没有设备、连不上服务器时也能进（要能退出）
import { ChevronRight } from 'lucide-react'
import type { BasePref, ThemePref } from '../api/types'
import { GroupTitle } from './SubPage'
import { Segmented } from './Segmented'

const BASES: { value: BasePref; label: string }[] = [
  { value: 'auto', label: '自动' },
  { value: 'amap', label: '高德' },
  { value: 'tencent', label: '腾讯' },
  { value: 'osm', label: 'OSM' },
]

const THEMES: { value: ThemePref; label: string }[] = [
  { value: 'system', label: '跟随系统' },
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
]

type Props = {
  base: BasePref
  onBase: (b: BasePref) => void
  theme: ThemePref
  onTheme: (t: ThemePref) => void
  onLogout: () => void
}

export function SettingsPage({ base, onBase, theme, onTheme, onLogout }: Props) {
  return (
    <>
      <GroupTitle>
        <span id="set-base">地图底图</span>
      </GroupTitle>
      <Segmented value={base} onChange={onBase} options={BASES} labelledBy="set-base" />
      <p className="mt-1.5 text-note text-mute">自动：先用高德，打不开换腾讯，再换 OpenStreetMap。</p>

      <GroupTitle>
        <span id="set-theme">深色 / 浅色</span>
      </GroupTitle>
      <Segmented value={theme} onChange={onTheme} options={THEMES} labelledBy="set-theme" />

      <GroupTitle>账号</GroupTitle>
      <ul>
        <li className="border-b border-line">
          <a href="/" className="-mx-5 flex h-[52px] items-center justify-between px-5 text-body text-ink active:bg-press">
            完整后台
            <span className="flex items-center gap-1 text-note text-mute">
              加设备、改通知
              <ChevronRight size={18} strokeWidth={2} aria-hidden />
            </span>
          </a>
        </li>
        <li>
          <button type="button" onClick={onLogout} className="-mx-5 flex h-[52px] w-[calc(100%+40px)] items-center px-5 text-body text-ink active:bg-press">
            退出登录
          </button>
        </li>
      </ul>

      <p className="mt-8 text-center text-note text-mute tabular-nums">孩子在哪 · 版本 {__APP_VERSION__}</p>
    </>
  )
}
