// 退出登录：本机先锁上（令牌已清、位置已不显示），再去服务器退出会话、吊销令牌。
// 服务器那一步没成（断网、服务器出错）要让家长看到并能重试：不然别人用这台手机打开完整后台，可能还能看到位置。
type Props = {
  busy: boolean
  onRetry: () => void
  onDone: () => void
}

export function SignOutPage({ busy, onRetry, onDone }: Props) {
  return (
    <main className="flex min-h-dvh flex-col bg-paper px-6 pt-[max(72px,env(safe-area-inset-top))]">
      <div className="mx-auto w-full max-w-[400px]">
        <h1 className="text-[28px] font-semibold text-ink">{busy ? '正在退出…' : '本机已退出'}</h1>
        {busy ? (
          <p className="mt-2 text-body leading-relaxed text-mute">这台手机上已经看不到位置，正在通知服务器。</p>
        ) : (
          <>
            <p className="mt-2 text-body leading-relaxed text-jing">服务器上还没退出成功（连不上服务器）。</p>
            <p className="mt-2 text-body leading-relaxed text-mute">
              这台手机上已经看不到位置，但别人用它打开完整后台可能还能看到。有网以后点「重试」。
            </p>
            <button type="button" onClick={onRetry} className="mt-8 h-12 w-full rounded-control bg-an text-name font-semibold text-on-an">
              重试
            </button>
            <button type="button" onClick={onDone} className="mt-3 h-12 w-full rounded-control border border-line text-name text-ink active:bg-press">
              先这样，回登录页
            </button>
          </>
        )}
      </div>
    </main>
  )
}
