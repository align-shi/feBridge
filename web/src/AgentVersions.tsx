import { createPortal } from 'react-dom'
import { useEffect, useRef, useState } from 'react'
import { errorMessage, versionApi } from './api'
import type { AgentVersionDetail, AgentVersionSnapshot, AgentVersionState } from './api'
import { useErrorState } from './ErrorNotice'

const modeNames = { NONE: '无记忆', CONTEXT: '上下文记忆', CROSS_SESSION: '跨会话记忆' }
const labels: [keyof AgentVersionSnapshot, string][] = [
  ['name','名称'], ['description','描述'], ['systemPrompt','系统提示词'], ['modelId','模型绑定'],
  ['parameters','模型参数'], ['rules','执行规则'], ['skillIds','Skills绑定'],
  ['mcpServerIds','MCP绑定'], ['knowledgeBaseIds','知识库绑定'], ['memoryMode','记忆模式'],
]
function stable(value: unknown): string {
  if (Array.isArray(value)) return '[' + value.map(stable).join(',') + ']'
  if (value && typeof value === 'object') return '{' + Object.entries(value)
    .sort(([a], [b]) => a.localeCompare(b)).map(([key,v]) => JSON.stringify(key) + ':' + stable(v)).join(',') + '}'
  return JSON.stringify(value)
}
function format(value: unknown): string {
  if (value === null || value === undefined) return '未配置'
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2)
}

export default function AgentVersions({ publishTarget, agentId, disabled, refreshKey, onBusy, onRestored }: {
  publishTarget?: HTMLElement | null;
  agentId: string; disabled: boolean; refreshKey: string; onBusy: (busy: boolean) => void; onRestored: () => Promise<void>
}) {
  const [state, setState] = useState<AgentVersionState | null>(null)
  const [working, setWorking] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useErrorState()
  const [notice, setNotice] = useState('')
  const [dialogType, setDialogType] = useState<'publish' | 'detail' | null>(null)
  const [detail, setDetail] = useState<AgentVersionDetail | null>(null)
  const [note, setNote] = useState('')
  const dialog = useRef<HTMLDialogElement>(null)
  const [revision, setRevision] = useState(0)
  const loadGeneration = useRef(0)

  useEffect(() => {
    if (disabled) return
    const generation = ++loadGeneration.current
    const controller = new AbortController()
    setLoading(true)
    versionApi.state(agentId, controller.signal).then(value => {
      if (generation === loadGeneration.current) setState(value)
    }).catch(e => { if (!controller.signal.aborted) setError(errorMessage(e)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [agentId, disabled, refreshKey, revision])

  useEffect(() => {
    if (dialogType) dialog.current?.showModal()
    else dialog.current?.close()
  }, [dialogType])

  async function action(task: () => Promise<void>) {
    if (working || disabled) return
    ++loadGeneration.current
    setWorking(true); onBusy(true); setError(''); setNotice('')
    try { await task() }
    catch (e) { setError(errorMessage(e)) }
    finally { setWorking(false); onBusy(false) }
  }

  async function openPublish() {
    await action(async () => {
      setState(await versionApi.state(agentId)); setNote(''); setDialogType('publish')
    })
  }
  async function openDetail(version: string) {
    await action(async () => {
      const [next, selected] = await Promise.all([versionApi.state(agentId), versionApi.detail(agentId, version)])
      setState(next); setDetail(selected); setDialogType('detail')
    })
  }
  const active = state?.versions.find(version => version.id === state.activeVersionId)
  const changedFields = detail && state ? labels.filter(([key]) =>
    stable(detail.snapshot[key]) !== stable(state.draft[key])).map(([,label]) => label) : []
  const locked = disabled || working || loading

  return <section className="agent-version-panel">
    {publishTarget && createPortal(<button type="button" className="button primary" disabled={locked || !state || !state.draft.modelId || !state.changed}
          onClick={() => { void openPublish() }}>发布版本</button>, publishTarget)}
    <div className="version-panel-heading"><div><strong>草稿与版本</strong>
      <p>当前工作台调试草稿；发布仅包含已保存配置。</p></div>
      <div className="version-actions"><button type="button" className="button secondary" disabled={locked} onClick={() => setRevision(n => n + 1)}>刷新版本</button>
        {!publishTarget && <button type="button" className="button primary" disabled={locked || !state || !state.draft.modelId || !state.changed}
          onClick={() => { void openPublish() }}>发布版本</button>}</div>
    </div>
    {error && <p className="error" role="alert">{error}</p>}
    {notice && <p className="notice" role="status">{notice}</p>}
    <div className="version-status"><span className="tag">开发草稿</span>
      <span>{active ? '当前发布 v' + active.number : '尚未发布'}</span>
      <span>{state ? state.changed ? '草稿有未发布改动' : '草稿与当前发布版本一致' : '正在读取…'}</span>
      {state && !state.draft.modelId && <span>绑定模型后可发布</span>}
    </div>
    <details className="version-history"><summary>历史版本 · {state?.versions.length ?? 0} 个</summary>
      {state?.versions.length === 0 && <p className="helper">发布后会在这里保留配置快照。</p>}
      {state?.versions.map(version => <article key={version.id}>
        <div><strong>v{version.number} {version.id === state.activeVersionId && <small className="tag">当前发布</small>}</strong>
          <p>{version.note || '未填写发布说明'}</p><small>{new Date(version.createdAt).toLocaleString('zh-CN')}</small></div>
        <div className="version-actions"><button type="button" className="button secondary" disabled={locked} onClick={() => { void openDetail(version.id) }}>查看 / 恢复草稿</button>
          {version.id !== state.activeVersionId && <button type="button" className="button secondary" disabled={locked} onClick={() => {
            void action(async () => { setState(await versionApi.activate(agentId, version.id)); setNotice('当前发布已切换为 v' + version.number + '，草稿保留。') })
          }}>设为当前发布</button>}</div>
      </article>)}
    </details>
    <p className="helper">快照保存提示词、模型绑定与参数、执行规则、记忆模式和各项绑定。共享 Skills、模型定义、知识库文档与 MCP 服务独立更新。发布版本供后续应用入口接入。</p>
    {createPortal(<dialog ref={dialog} className="version-dialog" onCancel={e => { if (working) e.preventDefault(); else setDialogType(null) }}
      onClick={e => { if (e.target === e.currentTarget && !working) setDialogType(null) }}>
      <div className="version-dialog-header"><h2>{dialogType === 'publish' ? '发布草稿' : '版本 v' + (detail?.version.number ?? '')}</h2>
        <button type="button" className="button secondary" disabled={working} onClick={() => setDialogType(null)}>关闭</button></div>
      {dialogType === 'publish' && state && <form onSubmit={e => {
        e.preventDefault()
        void action(async () => {
          const result = await versionApi.publish(agentId, note, state.fingerprint)
          setState(result); setDialogType(null); setNotice('已发布 v' + result.versions[0].number)
        })
      }}>
        <p>将已保存的“{state.draft.name}”发布为独立配置快照，并设为当前发布版本。</p>
        <p className="helper">Skills {state.draft.skillIds.length} 个 · MCP {state.draft.mcpServerIds.length} 个 · 知识库 {state.draft.knowledgeBaseIds.length} 个 · {modeNames[state.draft.memoryMode]}</p>
        <label>发布说明<textarea maxLength={1000} value={note} disabled={working}
          placeholder="例如：调整模具调拨审批提示词，绑定业务知识库" onChange={e => setNote(e.target.value)} /></label>
        <details><summary>查看将发布的系统提示词</summary><pre>{state.draft.systemPrompt}</pre></details>
        <div className="version-actions"><button className="button primary" disabled={working}>{working ? '发布中…' : '确认发布'}</button></div>
      </form>}
      {dialogType === 'detail' && detail && state && <>
        <p>{detail.version.note || '未填写发布说明'}</p>
        <p className="helper">{changedFields.length ? '与当前草稿不同：' + changedFields.join('、') : '此版本配置与当前草稿一致。'}</p>
        <div className="version-snapshot">{labels.map(([key,label]) => <details key={key} open={key === 'systemPrompt'}>
          <summary>{label}{stable(detail.snapshot[key]) !== stable(state.draft[key]) ? ' · 与草稿不同' : ''}</summary>
          <div className="version-compare"><div><small>此版本</small><pre>{key === 'memoryMode' ? modeNames[detail.snapshot.memoryMode] : format(detail.snapshot[key])}</pre></div>
            <div><small>当前已保存草稿</small><pre>{key === 'memoryMode' ? modeNames[state.draft.memoryMode] : format(state.draft[key])}</pre></div></div>
        </details>)}</div>
        <p className="helper">恢复将覆盖当前已保存草稿，不改变当前发布版本，也不回退共享资源内容。若引用资源已失效或不可访问，将拒绝恢复。</p>
        <div className="version-actions"><button type="button" className="button primary" disabled={working || !changedFields.length} onClick={() => {
          void action(async () => {
            setState(await versionApi.restore(agentId, detail.version.id, state.fingerprint))
            await onRestored(); setDialogType(null); setNotice('已将 v' + detail.version.number + ' 的配置恢复为草稿。')
          })
        }}>{working ? '恢复中…' : '用此版本恢复草稿'}</button></div>
      </>}
    </dialog>, document.body)}
  </section>
}
