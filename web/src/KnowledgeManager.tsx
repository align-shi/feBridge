import { useEffect, useRef, useState } from 'react'
import { errorMessage, knowledgeApi } from './api'
import type { KnowledgeBase, KnowledgeDocument, KnowledgeHit } from './api'
import { useErrorState } from './ErrorNotice'

const states: Record<string, string> = { pending: '等待处理', processing: '生成向量中', ready: '已就绪',
  failed: '处理失败', deleting: '删除中', delete_failed: '删除失败' }

export default function KnowledgeManager() {
  const [bases, setBases] = useState<KnowledgeBase[]>([])
  const [selected, setSelected] = useState('')
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([])
  const [creating, setCreating] = useState(false)
  const [draft, setDraft] = useState({ name: '', description: '', visibility: 'PRIVATE' })
  const [busy, setBusy] = useState(false)
  const [searching, setSearching] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useErrorState()
  const [query, setQuery] = useState('')
  const [limit, setLimit] = useState(5)
  const [hits, setHits] = useState<KnowledgeHit[] | null>(null)
  const [revision, setRevision] = useState(0)
  const fileInput = useRef<HTMLInputElement>(null)
  const active = bases.find(base => base.id === selected)

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    knowledgeApi.list(controller.signal).then(setBases).catch(e => {
      if (!controller.signal.aborted) setError(errorMessage(e))
    }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [revision])

  useEffect(() => {
    setDocuments([]); setHits(null); setQuery(''); setError('')
    if (!selected) return
    const controller = new AbortController()
    const load = () => knowledgeApi.documents(selected, controller.signal).then(setDocuments).catch(e => {
      if (!controller.signal.aborted) setError(errorMessage(e))
    })
    void load()
    const timer = setInterval(() => { void load() }, 3000)
    return () => { controller.abort(); clearInterval(timer) }
  }, [selected])

  async function action(work: () => Promise<unknown>) {
    setBusy(true); setError('')
    try {
      await work()
      if (selected) setDocuments(await knowledgeApi.documents(selected))
      setRevision(n => n + 1)
    } catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }

  async function upload(file: File) {
    if (file.size > 524288 || !/\.(txt|md)$/i.test(file.name)) {
      setError('仅支持512KB以内的UTF-8 TXT/Markdown文档'); return
    }
    await action(async () => {
      let content: string
      try { content = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer()) }
      catch { throw new Error('文档不是有效的UTF-8文本，请转换编码后重试') }
      if (!content.trim() || content.length > 200000) throw new Error('文档不能为空且不能超过20万字符')
      await knowledgeApi.upload(selected, file.name, content)
      setHits(null)
    })
  }

  return <div className="knowledge-page">
    <div className="page-heading"><div><div className="eyebrow">KNOWLEDGE</div>
      <h1>{active?.name ?? '知识库'}</h1><p>{active?.description || '把企业资料连接到智能体，回答有据可查。'}</p></div>
      {active ? <button className="button secondary" disabled={busy || searching} onClick={() => setSelected('')}>返回知识库列表</button>
        : <button className="button primary" disabled={busy} onClick={() => setCreating(true)}>＋ 新建知识库</button>}
    </div>
    {error && <p className="error" role="alert">{error}</p>}
    {creating && !active && <form className="panel" onSubmit={e => {
      e.preventDefault()
      void action(async () => {
        const base = await knowledgeApi.create(draft)
        setBases(items => [base, ...items]); setCreating(false); setSelected(base.id)
        setDraft({ name: '', description: '', visibility: 'PRIVATE' })
      })
    }}><fieldset disabled={busy}><h2>新建知识库</h2>
      <label>名称<input required maxLength={100} value={draft.name} onChange={e => setDraft({ ...draft, name: e.target.value })} /></label>
      <label>描述<textarea maxLength={1000} rows={2} value={draft.description} onChange={e => setDraft({ ...draft, description: e.target.value })} /></label>
      <label>访问范围<select value={draft.visibility} onChange={e => setDraft({ ...draft, visibility: e.target.value })}>
        <option value="PRIVATE">私有 · 仅自己可检索</option><option value="SHARED">共享 · 登录用户可检索</option>
      </select></label>
      <div className="form-actions"><button type="button" className="button secondary" onClick={() => setCreating(false)}>取消</button><button className="button primary">创建</button></div>
    </fieldset></form>}
    {!active && <><div className="toolbar"><span>{loading ? '正在读取…' : bases.length + ' 个可访问知识库'}</span>
      <button className="button secondary" disabled={loading} onClick={() => setRevision(n => n + 1)}>刷新</button></div>
      {!loading && !bases.length && <div className="empty"><h2>还没有知识库</h2><p>新建知识库后，上传业务规范或操作手册。</p></div>}
      <div className="agent-grid">{bases.map(base => <button type="button" className="agent-card" key={base.id} onClick={() => setSelected(base.id)}>
        <div className="card-top"><span className="agent-avatar">知</span><span className="tag">{base.visibility === 'SHARED' ? '共享' : '私有'}</span></div>
        <h2>{base.name}</h2><p>{base.description || '暂无描述'}</p><div className="card-footer"><span>{base.documents ?? 0} 份文档</span><span>{base.editable ? '可管理' : '只读'}</span></div>
      </button>)}</div></>}
    {active && <>
      <section className="panel"><div className="knowledge-section-heading"><div><h2>文档</h2>
        <p className="helper">TXT / Markdown · UTF-8 · 单文件512KB以内。上传后自动切分并生成向量。</p></div>
        {active.editable && <><input ref={fileInput} hidden type="file" accept=".txt,.md" onChange={e => {
          const file = e.target.files?.[0]; e.target.value = ''; if (file) void upload(file)
        }} /><button className="button primary" disabled={busy} onClick={() => fileInput.current?.click()}>{busy ? '提交中…' : '上传文档'}</button></>}
      </div>
      {!documents.length && <p className="helper">暂无文档。</p>}
      <div className="knowledge-documents">{documents.map(document => <article key={document.id}>
        <div><strong>{document.name}</strong><small>{states[document.status] ?? document.status} · {document.chunk_count} 个片段</small>
          {document.error_message && <p className="knowledge-error">{document.error_message}</p>}</div>
        {active.editable && <div className="knowledge-document-actions">
          {['failed', 'delete_failed'].includes(document.status) && <button className="button secondary" disabled={busy} onClick={() => { void action(() => knowledgeApi.retry(active.id, document.id)) }}>重试</button>}
          <button className="button secondary" disabled={busy || ['processing', 'deleting'].includes(document.status)}
            onClick={() => { void action(async () => { await knowledgeApi.remove(active.id, document.id); setHits(null) }) }}>删除</button>
        </div>}
      </article>)}</div></section>
      <section className="panel"><h2>检索调试</h2><p className="helper">直接查看向量检索结果，不调用对话模型。相似度是检索分数，不代表答案正确率。</p>
        <form onSubmit={e => {
          e.preventDefault(); setSearching(true); setError(''); setHits(null)
          knowledgeApi.search(active.id, query, limit).then(setHits).catch(e => setError(errorMessage(e))).finally(() => setSearching(false))
        }}><fieldset disabled={searching || busy}><label>问题<input required maxLength={500} value={query}
          placeholder="例如：模具调拨需要经过哪些审批？" onChange={e => setQuery(e.target.value)} /></label>
          <div className="knowledge-search-actions"><label>返回条数<select value={limit} onChange={e => setLimit(Number(e.target.value))}>
            {[3,5,10].map(n => <option key={n} value={n}>{n}</option>)}</select></label>
            <button className="button primary" disabled={!documents.some(document => document.status === 'ready')}>{searching ? '检索中…' : '检索'}</button>
          </div></fieldset></form>
        {hits && !hits.length && <p className="helper">没有检索到可用片段。</p>}
        <div className="knowledge-hits">{hits?.map((hit, index) => <article key={hit.chunkId}>
          <div><strong>{index + 1}. {hit.documentName} · 片段 {hit.ordinal}</strong><span className="tag">相似度 {hit.score.toFixed(4)}</span></div>
          <p>{hit.content}</p><small>{hit.citation}</small>
        </article>)}</div>
      </section>
    </>}
  </div>
}
