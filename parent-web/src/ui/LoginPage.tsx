// 首次打开：用 Traccar 后台的账号登录一次，之后这台手机记住（长期令牌）。
import { errorText } from '../api/errorText'
import { useState, type FormEvent } from 'react'
import type { Api } from '../api/types'

/** 令牌有效一年；Traccar 6.17 用令牌登录时不能再延长，到期要重新输一次密码 */
export const TOKEN_DAYS = 365

type Props = {
  api: Api
  expired: boolean
  onLoggedIn: (token: string) => void
}

export function LoginPage({ api, expired, onLoggedIn }: Props) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [needCode, setNeedCode] = useState(false)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(expired ? '登录已失效，请重新登录。' : null)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setMessage(null)
    try {
      const outcome = await api.login(email.trim(), password, needCode ? code.trim() : undefined)
      if (outcome === 'needCode') {
        setNeedCode(true)
        setMessage('这个账号开了两步验证，请输入验证器上的 6 位数字。')
        return
      }
      if (outcome === 'wrong') {
        setMessage(needCode ? '验证码不对，或账号密码不对。' : '账号或密码不对。')
        return
      }
      const expiration = new Date(api.now().getTime() + TOKEN_DAYS * 86_400_000)
      onLoggedIn(await api.createToken(expiration))
    } catch (err) {
      setMessage(`登录失败：${errorText(err)}，过一会儿再试。`)
    } finally {
      setBusy(false)
    }
  }

  const field =
    'h-12 w-full rounded-control border border-line bg-paper px-3.5 text-name text-ink outline-none placeholder:text-mute focus:border-an'

  return (
    <main className="flex min-h-dvh flex-col bg-paper px-6 pt-[max(72px,env(safe-area-inset-top))]">
      <div className="mx-auto w-full max-w-[400px]">
        <h1 className="text-[28px] font-semibold text-ink">孩子在哪</h1>
        <p className="mt-2 text-body leading-relaxed text-mute">用定位后台（Traccar）的账号登录一次，这台手机会记住，以后打开直接看位置。</p>

        <form className="mt-8 flex flex-col gap-3" onSubmit={(e) => void submit(e)}>
          <input
            className={field}
            type="text"
            autoComplete="username"
            autoCapitalize="none"
            placeholder="账号（邮箱）"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
          <input
            className={field}
            type="password"
            autoComplete="current-password"
            placeholder="密码"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
          {needCode && (
            <input
              className={field}
              inputMode="numeric"
              autoComplete="one-time-code"
              placeholder="6 位验证码"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              required
            />
          )}
          {message && <p className="text-body text-jing">{message}</p>}
          <button
            type="submit"
            disabled={busy}
            className="mt-2 h-12 rounded-control bg-an text-name font-semibold text-on-an disabled:opacity-60"
          >
            {busy ? '正在登录…' : '登录'}
          </button>
        </form>

        <p className="mt-6 text-note leading-relaxed text-mute">
          这里只看位置和告警。加设备、改通知去
          <a href="/" className="text-ink underline underline-offset-2">完整后台</a>。
        </p>
      </div>
    </main>
  )
}
