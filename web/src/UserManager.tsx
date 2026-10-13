import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { userApi, errorMessage } from './api'
import type { PlatformUser, UserRole } from './api'
import { useErrorState } from './ErrorNotice'

export default function UserManager({ currentUser, onCurrentChanged }: {
  currentUser: PlatformUser; onCurrentChanged: (user: PlatformUser) => void
}) {
  const [users, setUsers] = useState<PlatformUser[]>([])
  const [loading, setLoading] = useState(true)
  const [revision, setRevision] = useState(0)
  const [search, setSearch] = useState('')
  const [filter, setFilter] = useState('all')
  const [error, setError] = useErrorState()
  const [notice, setNotice] = useState('')
  const [editor, setEditor] = useState<PlatformUser | 'new' | null>(null)
  const [reset, setReset] = useState<PlatformUser | null>(null)
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    userApi.list(controller.signal).then(value => {
      if (!controller.signal.aborted) { setUsers(value); setError('') }
    }).catch(e => { if (!controller.signal.aborted) setError(errorMessage(e)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [revision, setError])
  const filtered = users.filter(user => (filter === 'all' || user.role === filter)
    && (user.username + ' ' + user.displayName).toLowerCase().includes(search.toLowerCase()))
  return <>
    <div className="page-heading"><div><div className="eyebrow">ACCESS MANAGEMENT</div><h1>用户与角色</h1>
      <p>管理工作空间账号，分配管理员或普通用户角色。</p></div>
      <button type="button" className="button primary" onClick={() => setEditor('new')}>＋ 创建用户</button></div>
    <div className="user-role-cards">
      <div className="panel"><span className="user-role-icon">A</span><h2>管理员</h2><p>管理用户、模型和 MCP 配置，使用全部工作台功能。</p><strong>{users.filter(user => user.role === 'ADMIN').length} 个账号</strong></div>
      <div className="panel"><span className="user-role-icon member">U</span><h2>普通用户</h2><p>创建和使用智能体、管理 Skills、查看会话与调用轨迹。</p><strong>{users.filter(user => user.role === 'USER').length} 个账号</strong></div>
    </div>
    {notice && <div className="notice" role="status">{notice}</div>}
    {error && <div className="error" role="alert">{error}</div>}
    <div className="toolbar user-toolbar"><label className="search"><span aria-hidden="true">⌕</span><input aria-label="搜索用户" placeholder="搜索账号或姓名…" value={search} onChange={e => setSearch(e.target.value)} /></label>
      <select className="user-role-filter" aria-label="筛选用户角色" value={filter} onChange={e => setFilter(e.target.value)}><option value="all">全部角色</option><option value="ADMIN">管理员</option><option value="USER">普通用户</option></select>
      <button type="button" className="button secondary" disabled={loading} onClick={() => setRevision(v => v + 1)}>{loading ? '加载中…' : '刷新'}</button></div>
    <div className="panel user-table-wrap"><table className="user-table"><thead><tr><th>用户</th><th>角色</th><th>状态</th><th>创建时间</th><th>操作</th></tr></thead>
      <tbody>{filtered.map(user => <tr key={user.id}><td><div className="user-cell"><span className="account-avatar">{user.displayName.slice(0,1)}</span><div><strong>{user.displayName}{user.id === currentUser.id && <small className="user-self">你</small>}</strong><small>{user.username}</small></div></div></td>
        <td><span className={'user-role-badge ' + user.role.toLowerCase()}>{user.role === 'ADMIN' ? '管理员' : '普通用户'}</span></td>
        <td><span className={'user-state ' + (user.enabled ? 'enabled' : '')}>{user.enabled ? '已启用' : '已禁用'}</span></td>
        <td className="user-date">{new Date(user.createdAt).toLocaleDateString('zh-CN')}</td>
        <td><div className="user-row-actions"><button className="button secondary" type="button" onClick={() => setEditor(user)}>编辑</button>
          {user.id !== currentUser.id && <button className="button secondary" type="button" onClick={() => setReset(user)}>重置密码</button>}</div></td></tr>)}</tbody></table>
      {!loading && !filtered.length && <div className="user-empty">没有符合条件的用户。</div>}
      <p className="user-table-note">当前为共享工作空间；角色控制管理功能，暂不按用户隔离智能体或会话。最多展示最近 500 个账号。</p>
    </div>
    {editor && <UserEditor user={editor === 'new' ? null : editor} self={editor !== 'new' && editor.id === currentUser.id}
      onClose={() => setEditor(null)} onSaved={user => {
        if (user.id === currentUser.id) onCurrentChanged(user)
        setEditor(null); setNotice('用户已保存。'); setRevision(v => v + 1)
      }} />}
    {reset && <ResetDialog user={reset} onClose={() => setReset(null)} onSaved={() => {
      setReset(null); setNotice('密码已重置，该用户需要重新登录。')
    }} />}
  </>
}

function UserEditor({ user, self, onClose, onSaved }: {
  user: PlatformUser | null; self: boolean; onClose: () => void; onSaved: (user: PlatformUser) => void
}) {
  const ref = useRef<HTMLDialogElement>(null)
  const [username, setUsername] = useState(user?.username ?? '')
  const [name, setName] = useState(user?.displayName ?? '')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState<UserRole>(user?.role ?? 'USER')
  const [enabled, setEnabled] = useState(user?.enabled ?? true)
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
    setBusy(true); setError('')
    try {
      const value = user
        ? await userApi.update(user.id, { displayName: name.trim(), role, enabled })
        : await userApi.create({ username: username.trim(), displayName: name.trim(), password, role })
      onSaved(value)
    } catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }
  return <dialog ref={ref} className="registry-dialog" aria-label={user ? '编辑用户' : '创建用户'}
    onCancel={e => { if (busy) e.preventDefault() }}
    onClose={e => { if (!e.currentTarget.open) onClose() }}>
    <header><h2>{user ? '编辑用户' : '创建用户'}</h2><button type="button" className="button secondary" disabled={busy} onClick={onClose} aria-label="关闭用户编辑">✕</button></header>
    <form className="registry-editor-form user-form" onSubmit={submit}><fieldset disabled={busy}>
      <label>账号<input required disabled={!!user} minLength={3} maxLength={32} autoComplete="off" value={username} onChange={e => setUsername(e.target.value)} placeholder="例如 zhangsan" /></label>
      {!user && <p className="form-help">3–32 个字符，支持字母、数字、下划线、点和短横线；账号不区分大小写。</p>}
      <label>显示名称<input required maxLength={80} value={name} onChange={e => setName(e.target.value)} placeholder="例如 张三" /></label>
      {!user && <label>初始密码<input type="password" required minLength={10} maxLength={128} autoComplete="new-password" value={password} onChange={e => setPassword(e.target.value)} /><small>10–128 个字符。</small></label>}
      <label>角色<select disabled={self} value={role} onChange={e => setRole(e.target.value as UserRole)}><option value="USER">普通用户</option><option value="ADMIN">管理员</option></select></label>
      {user && <label className="user-enabled"><input type="checkbox" disabled={self} checked={enabled} onChange={e => setEnabled(e.target.checked)} />启用账号</label>}
      {user && <p className="form-help">{self ? '不能禁用自己或取消自己的管理员角色。' : '修改角色或禁用账号后，旧登录状态将失效。'}</p>}
      {error && <div className="error" role="alert">{error}</div>}
      <div className="form-actions"><button type="button" className="button secondary" onClick={onClose}>取消</button><button type="submit" className="button primary">{busy ? '保存中…' : '保存用户'}</button></div>
    </fieldset></form>
  </dialog>
}

function ResetDialog({ user, onClose, onSaved }: { user: PlatformUser; onClose: () => void; onSaved: () => void }) {
  const ref = useRef<HTMLDialogElement>(null)
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useErrorState()
  useEffect(() => { const dialog = ref.current; dialog?.showModal(); return () => dialog?.close() }, [])
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    setBusy(true); setError('')
    try { await userApi.reset(user.id, password); onSaved() }
    catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }
  return <dialog ref={ref} className="registry-dialog" aria-label="重置用户密码"
    onCancel={e => { if (busy) e.preventDefault() }} onClose={e => { if (!e.currentTarget.open) onClose() }}>
    <header><h2>重置用户密码</h2><button type="button" className="button secondary" disabled={busy} onClick={onClose} aria-label="关闭重置密码">✕</button></header>
    <form className="registry-editor-form user-form" onSubmit={submit}><fieldset disabled={busy}>
      <p>为 {user.displayName}（{user.username}）设置新密码。</p>
      <label>新密码<input type="password" required minLength={10} maxLength={128} autoComplete="new-password" value={password} onChange={e => setPassword(e.target.value)} /></label>
      <p className="form-help">该用户所有设备上的旧登录状态将失效。</p>
      {error && <div className="error" role="alert">{error}</div>}
      <div className="form-actions"><button type="button" className="button secondary" onClick={onClose}>取消</button><button type="submit" className="button primary">{busy ? '提交中…' : '重置密码'}</button></div>
    </fieldset></form>
  </dialog>
}
