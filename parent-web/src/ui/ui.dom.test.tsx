// @vitest-environment happy-dom
// 要真 DOM 才看得出的：收起抽屉时滚动归零（审计 11-18 #9）、二级页焦点（#10）、告警页没核实时的说法（#1）
import { act, type ReactNode } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { afterEach, describe, expect, it } from 'vitest'
import type { Position, Snap } from '../api/types'
import { AlertsPage, EMPTY_TEXT } from './AlertsPage'
import { StatusSheet } from './StatusSheet'
import { SubPage } from './SubPage'

declare global {
  var IS_REACT_ACT_ENVIRONMENT: boolean
}
globalThis.IS_REACT_ACT_ENVIRONMENT = true

const NOW = new Date('2026-11-18T12:00:00Z')
const P: Position = {
  id: 1,
  deviceId: 1,
  fixTime: NOW,
  deviceTime: NOW,
  serverTime: NOW,
  latitude: 30.27,
  longitude: 120.14,
  speedKmh: 0,
  accuracy: 8,
  provider: 'gps',
  address: '甲区',
  state: 'zone',
  geofenceIds: [],
}
const noop = () => {}

let root: Root | null = null
let host: HTMLElement
async function render(node: ReactNode) {
  if (!root) {
    host = document.createElement('div')
    document.body.append(host)
    root = createRoot(host)
  }
  const r = root
  await act(async () => r.render(node))
}
afterEach(async () => {
  const r = root
  root = null
  if (r) await act(async () => r.unmount())
  document.body.innerHTML = ''
})

function sheet(snap: Snap) {
  return (
    <StatusSheet
      name="小宝"
      position={P}
      fix={P}
      place={undefined}
      places={[]}
      presence={{ freshness: 'fresh', live: null }}
      history={{ reports: [P], fixes: [P] }}
      offlineSince={null}
      updating={false}
      now={NOW}
      events={[]}
      eventsAsOf={NOW}
      eventPositions={new Map()}
      error={null}
      trackOn={false}
      snap={snap}
      onSnap={noop}
      onToggleTrack={noop}
      onAddPlace={noop}
      onOpen={noop}
      onReconnect={noop}
    />
  )
}

describe('收起抽屉时内容滚回顶上（审计 11-18 #9）', () => {
  it('展开时滚到下面看入口，再收起：滚动归零，露出名字和状态', async () => {
    await render(sheet('open'))
    const body = host.querySelector('[data-sheet-handle]')?.nextElementSibling
    if (!(body instanceof HTMLElement)) throw new Error('没找到抽屉内容')
    body.scrollTop = 81
    expect(body.scrollTop).toBe(81)
    await render(sheet('peek'))
    expect(body.scrollTop).toBe(0)
  })
})

describe('二级页的键盘焦点（审计 11-18 #10）', () => {
  it('打开时焦点移到「返回」，关掉时还给打开它的入口', async () => {
    const entry = document.createElement('button')
    entry.textContent = '告警'
    document.body.append(entry)
    entry.focus()
    await render(
      <SubPage title="最近 24 小时" onBack={noop}>
        <p>内容</p>
      </SubPage>,
    )
    expect(document.activeElement?.textContent).toBe('返回')
    expect(document.activeElement?.closest('[role="dialog"]')).not.toBeNull()
    await render(null)
    expect(document.activeElement).toBe(entry)
  })
  it('手指点开的（没有键盘焦点）：焦点也在页里，但不在「返回」上画框', async () => {
    await render(
      <SubPage title="最近 24 小时" onBack={noop}>
        <p>内容</p>
      </SubPage>,
    )
    expect(document.activeElement?.getAttribute('role')).toBe('dialog')
  })
})

describe('告警没核实时不能说「24 小时里没有事」（审计 11-18 #1）', () => {
  const page = (asOf: Date | null, error: string | null, now = NOW) => (
    <AlertsPage events={[]} asOf={asOf} error={error} eventPositions={new Map()} places={[]} now={now} onRetry={noop} />
  )
  it('刚核实过：照常说没有事', async () => {
    await render(page(NOW, null))
    expect(host.textContent).toContain(EMPTY_TEXT)
  })
  it('查询失败：写只更新到几点，可以重试', async () => {
    const asOf = new Date(NOW.getTime() - 5 * 60_000)
    await render(page(asOf, '网络不通'))
    expect(host.textContent).not.toContain(EMPTY_TEXT)
    expect(host.textContent).toMatch(/只更新到 \d\d:\d\d/)
    expect(host.querySelector('button')?.textContent).toBe('重试')
  })
  it('推送断着、两分钟没核实：也一样', async () => {
    await render(page(new Date(NOW.getTime() - 2 * 60_000), null))
    expect(host.textContent).not.toContain(EMPTY_TEXT)
    expect(host.textContent).toContain('只更新到')
  })
})
