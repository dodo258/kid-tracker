import { Component, type ErrorInfo, type ReactNode } from 'react'

type Props = { children: ReactNode }
type State = { error: Error | null }

/** 页面运行中出错时显示提示和错误原文，不让整页变成空白（家长的浏览器各式各样，出错要能看出原因）。 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: unknown): State {
    return { error: error instanceof Error ? error : new Error(String(error)) }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error(error, info.componentStack)
  }

  render() {
    const { error } = this.state
    if (!error) return this.props.children
    return (
      <main className="flex min-h-dvh flex-col bg-paper px-6 pt-[max(72px,env(safe-area-inset-top))]">
        <div className="mx-auto w-full max-w-[400px]">
          <h1 className="text-status font-semibold text-ink">页面出错了</h1>
          <p className="mt-2 text-body leading-relaxed text-mute">
            先点「重新加载」。还不行，就换用最新版的 Chrome、Safari 或 Edge，并把下面这行发给管理员。
          </p>
          <p className="mt-4 break-all rounded-control bg-paper p-3 font-mono text-note text-ink">{error.message}</p>
          <button
            type="button"
            onClick={() => location.reload()}
            className="mt-4 h-12 w-full rounded-control bg-an text-name font-semibold text-on-an"
          >
            重新加载
          </button>
        </div>
      </main>
    )
  }
}
