import { describe, expect, it } from 'vitest'
import css from '../index.css?raw'

// index.css 用 `--radius-*: initial` 清掉了 Tailwind 自带的圆角和阴影，只留自己定义的几级；
// 用了没定义的名字（如 rounded-t-sheet）Tailwind 不报错，只是不生成样式，卡片就成了直角。
const files: Record<string, string> = import.meta.glob(['../**/*.{ts,tsx}', '!../**/*.test.{ts,tsx}'], {
  query: '?raw',
  import: 'default',
  eager: true,
})

const defined = (ns: string) => new Set([...css.matchAll(new RegExp(`--${ns}-([a-z-]+):`, 'g'))].map((m) => m[1]))

// Tailwind 不读主题、写死的圆角
const STATIC_RADIUS = new Set(['full', 'none'])

function used(pattern: RegExp): Map<string, string[]> {
  const found = new Map<string, string[]>()
  for (const [file, text] of Object.entries(files)) {
    for (const m of text.matchAll(pattern)) found.set(m[1], [...(found.get(m[1]) ?? []), file])
  }
  return found
}

describe('界面用到的主题名都在 index.css 里有定义', () => {
  it('扫到了界面文件', () => {
    expect(Object.keys(files)).toContain('./StatusSheet.tsx')
  })
  it('rounded-*', () => {
    const radius = defined('radius')
    const missing = [...used(/\brounded(?:-[trblse]{1,2})?-([a-z]+)\b/g)].filter(
      ([name]) => !radius.has(name) && !STATIC_RADIUS.has(name),
    )
    expect(missing).toEqual([])
  })
  it('shadow-*', () => {
    const shadow = defined('shadow')
    const missing = [...used(/\bshadow-([a-z]+)\b/g)].filter(([name]) => !shadow.has(name) && name !== 'none')
    expect(missing).toEqual([])
  })
})
