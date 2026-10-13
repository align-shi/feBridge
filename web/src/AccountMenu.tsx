import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { authApi, errorMessage } from './api'
import type { PlatformUser } from './api'
import { useErrorState } from './ErrorNotice'

export default function AccountMenu({ user, onSignedOut }: {
  user: PlatformUser; onSignedOut: (message?: string) => void
}) {
  const [editing, setEditing] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useErrorState()
  async function logout() {
    if (busy) return
    setBusy(true)
    try { await authApi.logout(); onSignedOut() }
    catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }
  return <div className="account-menu">
    <div className="account-identity"><span className="account-avatar">{user.displayName.slice(0,1)}</span>
      <div><strong>{user.displayName}</strong><small>{user.username} · {user.role === 'ADMIN' ? '管理员' : '普通用户'}</small></div></div>
    <div className="account-actions"><button type="button" disabled={busy} onClick={() => setEditing(true)}>修改密码</button>
      <button type="button" disabled={busy} onClick={logout}>{busy ? '退出中…' : '退出登录'}</button></div>
    {error && <p className="account-error">{error}</p>}
    {editing && <PasswordDialog onClose={() => setEditing(false)} onChanged={() => onSignedOut('密码已修改，请使用新密码重新登录。')} />}
  </div>
}

function PasswordDialog({ onClose, onChanged }: { onClose: () => void; onChanged: () => void }) {
  const ref = useRef<HTMLDialogElement>(null)
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [repeat, setRepeat] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useErrorState()
  useEffect(() => {
    const dialog = ref.current
    dialog?.showModal()
    return () => dialog?.close()
  }, [])
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    setError('')
    if (next !== repeat) { setError('两次新密码不一致。'); return }
    setBusy(true)
    try { await authApi.password(current, next); onChanged() }
    catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }
  return <dialog ref={ref} className="registry-dialog" aria-label="修改密码"
    onCancel={e => { if (busy) e.preventDefault() }}
    onClose={e => { if (!e.currentTarget.open) onClose() }}>
    <header><h2>修改密码</h2><button type="button" className="button secondary" disabled={busy} onClick={onClose} aria-label="关闭修改密码">✕</button></header>
    <form className="registry-editor-form user-form" onSubmit={submit}><fieldset disabled={busy}>
      <label>当前密码<input type="password" required maxLength={128} autoComplete="current-password" value={current} onChange={e => setCurrent(e.target.value)} /></label>
      <label>新密码<input type="password" required minLength={10} maxLength={128} autoComplete="new-password" value={next} onChange={e => setNext(e.target.value)} /></label>
      <label>再次输入新密码<input type="password" required minLength={10} maxLength={128} autoComplete="new-password" value={repeat} onChange={e => setRepeat(e.target.value)} /></label>
      <p className="form-help">密码长度为 10–128 个字符。修改后，所有设备上的旧登录状态都会失效。</p>
      {error && <div className="error" role="alert">{error}</div>}
      <div className="form-actions"><button type="button" className="button secondary" onClick={onClose}>取消</button><button className="button primary" type="submit">{busy ? '提交中…' : '修改密码'}</button></div>
    </fieldset></form>
  </dialog>
}
