import { CurrentUserContext } from './CurrentUser'
import BrandLogo from './BrandLogo'
import { useEffect, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import LoginVisual from './LoginVisual'
import { authApi, clearCsrf, errorMessage, HttpError } from './api'
import type { PlatformUser } from './api'

export default function AuthGate({ children }: {
  children: (user: PlatformUser, changed: (user: PlatformUser) => void, signedOut: (message?: string) => void) => ReactNode
}) {
  const [user, setUser] = useState<PlatformUser | null>(null)
  const [checking, setChecking] = useState(true)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setChecking(true)
    authApi.me(controller.signal).then(value => {
      if (!controller.signal.aborted) { setUser(value); setError('') }
    }).catch(e => {
      if (!controller.signal.aborted && !(e instanceof HttpError && e.status === 401)) setError(errorMessage(e))
    }).finally(() => { if (!controller.signal.aborted) setChecking(false) })
    return () => controller.abort()
  }, [revision])
  function signedOut(message = '') {
    clearCsrf()
    setUser(null)
    setNotice(message)
    setError('')
  }
  useEffect(() => {
    function expired() { signedOut('登录状态已失效，请重新登录。') }
    window.addEventListener('febridge:auth-expired', expired)
    return () => window.removeEventListener('febridge:auth-expired', expired)
  }, [])
  if (checking) return <div className="auth-loading">正在验证登录状态…</div>
  if (user) return <CurrentUserContext.Provider value={user}>{children(user, setUser, signedOut)}</CurrentUserContext.Provider>
  return <LoginPage initialError={error} notice={notice} onLogin={setUser} onRetry={() => setRevision(v => v + 1)} />
}

function LoginPage({ initialError, notice, onLogin, onRetry }: {
  initialError: string; notice: string; onLogin: (user: PlatformUser) => void; onRetry: () => void
}) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setError('')
    try {
      const user = await authApi.login(username.trim(), password)
      setPassword('')
      onLogin(user)
    } catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }
  return <div className="login-page">
    <div className="login-ambient" aria-hidden="true"><div className="login-grid" /><div className="login-aura aura-one" /><div className="login-aura aura-two" />
      {Array.from({ length: 14 }, (_, index) => <i key={index} className="login-particle" style={{ left: (index * 37 % 100) + '%', top: (index * 23 % 100) + '%', animationDelay: -(index * .8) + 's' }} />)}
    </div>
    <header className="login-header"><a className="brand" href="/" aria-label="枫桥首页"><BrandLogo /><span className="brand-name">枫桥<small>feBridge</small></span></a>
      <span className="login-header-caption">IDEAS. CONNECTED.</span></header>
    <div className="login-layout">
      <section className="login-intro">
        <div className="login-intro-content"><div className="login-kicker"><span /> THE NEXT BRIDGE TO INTELLIGENCE</div>
          <h1>让智能体，<br /><span>走进你的业务。</span></h1>
          <p>从一个想法，到一个行动。<br />让提示词、技能与业务工具，在这里连接。</p>
          <LoginVisual />
          <div className="login-capabilities"><span>智能体编排</span><i /><span>业务工具连接</span><i /><span>全链路追踪</span></div>
        </div>
      </section>
      <section className="login-form-area">
        <div className="login-card"><div className="login-card-accent" aria-hidden="true" />
          <form className="login-form" onSubmit={submit}>
            <div className="login-card-symbol" aria-hidden="true"><BrandLogo className="login-card-logo" /></div>
            <small className="eyebrow">WELCOME TO FEBRIDGE</small><h2>开启你的工作空间</h2><p>登录，开始构建你的智能体。</p>
            {notice && <div className="notice" role="status">{notice}</div>}
            {initialError && <div className="error" role="alert">{initialError}<button type="button" className="auth-retry" onClick={onRetry}>重新连接</button></div>}
            <fieldset disabled={busy}>
              <label htmlFor="login-username">账号 <span>ACCOUNT</span></label>
              <div className="login-input"><svg aria-hidden="true" viewBox="0 0 24 24" fill="none"><circle cx="12" cy="8" r="3.5" /><path d="M5 20v-2a7 7 0 0 1 14 0v2" /></svg>
                <input id="login-username" autoComplete="username" required maxLength={32} value={username} onChange={e => setUsername(e.target.value)} placeholder="请输入账号" autoFocus /></div>
              <label htmlFor="login-password">密码 <span>PASSWORD</span></label>
              <div className="login-input"><svg aria-hidden="true" viewBox="0 0 24 24" fill="none"><rect x="5" y="10" width="14" height="11" rx="2" /><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 4v3" /></svg>
                <input id="login-password" type={showPassword ? 'text' : 'password'} autoComplete="current-password" required maxLength={128} value={password} onChange={e => setPassword(e.target.value)} placeholder="请输入密码" />
                <button type="button" className="login-password-toggle" aria-label={showPassword ? '隐藏密码' : '显示密码'} aria-pressed={showPassword} onClick={() => setShowPassword(value => !value)}>
                  <svg aria-hidden="true" viewBox="0 0 24 24" fill="none"><path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12Z" /><circle cx="12" cy="12" r="2.5" />{showPassword && <path d="m3 3 18 18" />}</svg>
                </button></div>
              {error && <div className="error" role="alert">{error}</div>}
              <button className="button primary login-submit" type="submit" aria-busy={busy}><span>{busy ? '正在连接工作空间…' : '进入工作空间'}</span>{busy ? <i className="login-spinner" aria-hidden="true" /> : <span className="login-submit-arrow" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none"><path d="M5 12h14m-5-5 5 5-5 5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" /></svg></span>}</button>
            </fieldset>
            <div className="login-form-divider" />
            <p className="login-help"><svg aria-hidden="true" viewBox="0 0 24 24" fill="none"><path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3Z" /><path d="m8 12 3 3 5-6" /></svg>账号由管理员管理。如需开通或重置密码，请联系管理员。</p>
          </form>
        </div>
        <p className="login-panel-caption">BUILD WITH INTELLIGENCE · CONNECT WITH FEBRIDGE</p>
      </section>
    </div>
    <footer className="login-footer"><span>枫桥 · 连接业务与智能</span><span>YOUR IDEAS, IN MOTION.</span></footer>
  </div>
}
