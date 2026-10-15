import { useEffect, useState } from 'react'
import { errorMessage, longMemoryApi } from './api'
import type { LongMemoryItem, LongMemoryView } from './api'
import { useErrorState } from './ErrorNotice'

const categories: Record<string, string> = { preference: '偏好', default: '默认条件', fact: '稳定事实' }
const emptyDraft = { category: 'preference', key: '', content: '', enabled: true, expiresAt: '' }

export default function LongTermMemory({ agentId, disabled, mode }: { agentId: string; disabled: boolean; mode: import('./api').MemoryMode }) {
  const [open, setOpen] = useState(false)
  const [view, setView] = useState<LongMemoryView | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useErrorState()
  const [adding, setAdding] = useState(false)
  const [editing, setEditing] = useState<LongMemoryItem | null>(null)
  const [draft, setDraft] = useState(emptyDraft)

  useEffect(() => {
    if (!open) return
    const controller = new AbortController()
    const load = () => longMemoryApi.get(agentId, controller.signal).then(setView).catch(e => {
      if (!controller.signal.aborted) setError(errorMessage(e))
    })
    void load()
    const timer = setInterval(() => { void load() }, 5000)
    return () => { controller.abort(); clearInterval(timer) }
  }, [agentId, open])

  async function action(task: () => Promise<unknown>) {
    setBusy(true)
    setError('')
    try {
      await task()
      setView(await longMemoryApi.get(agentId))
      setAdding(false)
      setEditing(null)
    } catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }

  function edit(item: LongMemoryItem) {
    setEditing(item)
    setAdding(false)
    setDraft({ category: item.category, key: item.memory_key, content: item.content,
      enabled: item.enabled, expiresAt: item.expires_at ? new Date(item.expires_at).toISOString().slice(0, 10) : '' })
  }

  const locked = disabled || busy
  const payload = { content: draft.content, enabled: draft.enabled,
    expiresAt: draft.expiresAt ? new Date(draft.expiresAt + 'T23:59:59').toISOString() : null }
  const pending = view?.jobs.filter(job => ['pending', 'running'].includes(job.status)).length ?? 0

  return <details className="conversation-memory long-term-memory" open={open}
    onToggle={event => setOpen(event.currentTarget.open)}>
    <summary><span className="memory-indicator" /><strong>长期记忆</strong>
      <span>跨会话使用 · 仅当前用户与智能体</span>
      {pending > 0 && <small>{pending} 项待整理</small>}
    </summary>
    <div className="memory-content">
      <div className="long-memory-actions">
        <span className="memory-description">{mode === 'CROSS_SESSION' ? '跨会话记忆正在使用' : '当前模式不读取或自动记录长期记忆，已保存内容仍可管理'}</span>
        <button type="button" className="button secondary" disabled={locked || !view}
          onClick={() => { setAdding(true); setEditing(null); setDraft(emptyDraft) }}>新增记忆</button>
      </div>
      <p className="memory-description">第一版本地识别明确表达，如“以后默认查询苏州工厂”“以后请用中文回答”“我长期负责注塑模具”。不增加模型请求。回答完成后异步整理，可在这里核对和修改。</p>
      {error && <p role="alert">{error}</p>}
      {!view && <p>正在读取记忆…</p>}
      {view && !view.items.length && <p className="memory-empty">暂无长期记忆。你可以新增，也可以在对话中明确表达偏好。</p>}
      <div className="long-memory-list">{view?.items.map(item => <article key={item.id}>
        <div><small>{categories[item.category]} · {item.manual ? '手动维护' : '自动记录'}
          {!item.enabled && ' · 已禁用'}
          {item.expires_at && (new Date(item.expires_at).getTime() <= Date.now() ? ' · 已过期' : ' · 有效至 ' + new Date(item.expires_at).toLocaleDateString('zh-CN'))}</small>
          <p>{item.content}</p><code>{item.memory_key}</code>
          {item.evidence && <details><summary>查看来源原文</summary><p>{item.evidence}</p>
            <small>来源会话：{item.source_session ?? '已删除'}</small></details>}
        </div>
        <div className="long-memory-item-actions">
          <button type="button" className="button secondary" disabled={locked} onClick={() => edit(item)}>编辑</button>
          <button type="button" className="button secondary" disabled={locked} onClick={() => {
            void action(() => longMemoryApi.edit(agentId, item.id, { content: item.content, enabled: !item.enabled,
              expiresAt: item.expires_at, version: item.version }))
          }}>{item.enabled ? '禁用' : '启用'}</button>
          <button type="button" className="button secondary" disabled={locked} onClick={() => {
            void action(() => longMemoryApi.remove(agentId, item))
          }}>删除</button>
        </div>
      </article>)}</div>
      {(adding || editing) && <form className="long-memory-form" onSubmit={e => {
        e.preventDefault()
        if (editing) {
          void action(() => longMemoryApi.edit(agentId, editing.id, { ...payload, version: editing.version }))
        } else {
          void action(() => longMemoryApi.create(agentId, { ...payload, category: draft.category, key: draft.key }))
        }
      }}>
        <h4>{editing ? '编辑记忆' : '新增记忆'}</h4>
        {!editing && <div className="long-memory-fields"><label>类型<select value={draft.category}
          onChange={e => setDraft({ ...draft, category: e.target.value })}>
          {Object.entries(categories).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select></label><label>编码<input required pattern="[a-z][a-z0-9_.-]{0,79}" maxLength={80}
          placeholder="如 default.factory" value={draft.key}
          onChange={e => setDraft({ ...draft, key: e.target.value })} /></label></div>}
        <label>记忆内容<textarea required maxLength={500} value={draft.content}
          onChange={e => setDraft({ ...draft, content: e.target.value })} /></label>
        <label>有效期（留空则长期有效）<input type="date" value={draft.expiresAt}
          onChange={e => setDraft({ ...draft, expiresAt: e.target.value })} /></label>
        <div className="long-memory-actions"><button className="button" disabled={locked}>保存</button>
          <button type="button" className="button secondary" onClick={() => { setAdding(false); setEditing(null) }}>取消</button></div>
      </form>}
      {!!view?.jobs.length && <details className="long-memory-jobs"><summary>最近整理任务</summary>
        {view.jobs.map(job => <p key={job.request_id}>
          {new Date(job.created_at).toLocaleString('zh-CN')} · {({ pending: '等待整理', running: '整理中', completed: '已完成', failed: '失败', skipped: '已跳过' } as Record<string,string>)[job.status] ?? job.status}
          {' · '}{job.total_tokens ?? 0} Token{job.error_message ? ' · ' + job.error_message : ''}
        </p>)}
      </details>}
      <p className="memory-description">偏好和默认条件直接带入；稳定事实按关键词匹配，最多召回8条。本次输入优先。切换到其他模式后停止记录和召回；手动修改或删除的内容不会被后台覆盖。</p>
    </div>
  </details>
}
