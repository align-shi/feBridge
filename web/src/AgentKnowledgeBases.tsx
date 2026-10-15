import { useEffect, useState } from 'react'
import { errorMessage, knowledgeApi } from './api'
import type { KnowledgeBase } from './api'
import { useErrorState } from './ErrorNotice'

export default function AgentKnowledgeBases({ agentId, disabled, onSaving }: {
  agentId: string; disabled: boolean; onSaving: (saving: boolean) => void
}) {
  const [all, setAll] = useState<KnowledgeBase[]>([])
  const [ids, setIds] = useState<string[]>([])
  const [loaded, setLoaded] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useErrorState()
  const [notice, setNotice] = useState('')
  useEffect(() => {
    let live = true
    Promise.all([knowledgeApi.list(), knowledgeApi.bound(agentId)]).then(([bases, bound]) => {
      if (live) { setAll(bases); setIds(bound.map(base => base.id)); setLoaded(true) }
    }).catch(e => { if (live) setError(errorMessage(e)) })
    return () => { live = false }
  }, [agentId])
  return <details className="agent-settings"><summary>绑定知识库 · {ids.length} 个</summary>
    <p className="helper">最多8个。模型需支持工具调用，执行规则需允许使用工具。通过 search_knowledge 按需检索，并遵守当前用户的知识库访问权限。</p>
    {error && <p className="error">{error}</p>}
    {notice && <p className="notice">{notice}</p>}
    <fieldset disabled={disabled || busy || !loaded}>
      {all.map(base => <label className="tool-choice" key={base.id}><input type="checkbox" checked={ids.includes(base.id)}
        onChange={e => setIds(old => e.target.checked ? [...old, base.id] : old.filter(id => id !== base.id))} />
        <span>{base.name} · {base.visibility === 'SHARED' ? '共享' : '私有，仅自己可检索'}</span></label>)}
      {loaded && !all.length && <p className="helper">请先在知识库页面创建或添加资料。</p>}
      <button type="button" className="button secondary" disabled={ids.length > 8} onClick={async () => {
        setBusy(true); onSaving(true); setError(''); setNotice('')
        try { await knowledgeApi.bind(agentId, ids); setNotice('知识库绑定已保存。') }
        catch (e) { setError(errorMessage(e)) }
        finally { setBusy(false); onSaving(false) }
      }}>{busy ? '保存中…' : '保存知识库绑定'}</button>
    </fieldset>
  </details>
}
