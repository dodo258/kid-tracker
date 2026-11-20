import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { loadThemePref, loadToken } from './api/storage'
import { traccarApi } from './api/traccar'
import type { Api } from './api/types'
import { watchBreathing } from './map/breathe'
import { App, type InitialUi } from './ui/App'
import { ErrorBoundary } from './ui/ErrorBoundary'
import { applyTheme } from './ui/theme'
import './index.css'

async function boot() {
  // 设置里固定了深色、浅色：先套上再画，不先闪一下系统的颜色
  applyTheme(loadThemePref())
  // iPhone 主屏幕打开的网页放后台再回来，呼吸会变成一闪一闪：回到前台就重起呼吸动画（map/breathe）
  watchBreathing()
  let api: Api = traccarApi
  let token = loadToken()
  let ui: InitialUi | undefined
  let clock: (() => number) | undefined
  // 只有 `vite --mode fixtures`（截图）才用假数据；正式构建里这段整个被删掉
  if (import.meta.env.MODE === 'fixtures') {
    const { fixtureBackend } = await import('../fixtures/mockApi')
    const backend = fixtureBackend(new URLSearchParams(location.search).get('state') ?? 'normal')
    api = backend.api
    token = backend.token
    ui = backend.ui
    clock = backend.clock
  }
  const root = document.getElementById('root')
  if (!root) throw new Error('index.html 里没有 #root')
  createRoot(root).render(
    <StrictMode>
      <ErrorBoundary>
        <App api={api} initialToken={token} initialUi={ui} clock={clock} />
      </ErrorBoundary>
    </StrictMode>,
  )
  // index.html 里的「页面没能打开」提示：脚本慢慢加载完、页面已经出来了，就撤掉
  document.querySelector('.boot-fail')?.remove()
}

boot().catch((err: unknown) => {
  console.error(err)
  document.body.textContent = `页面加载失败：${err instanceof Error ? err.message : String(err)}`
})
