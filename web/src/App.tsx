import BrandLogo from './BrandLogo'
import { useErrorState } from './ErrorNotice'
import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { agentApi, errorMessage } from './api'
import type { Agent } from './api'
import ModelManager from './ModelManager'
import KnowledgeManager from './KnowledgeManager'
import ToolManager from './ToolManager'
import SkillManager from './SkillManager'
import ModelSelect from './ModelSelect'
import AgentRuntime from './AgentRuntime'
import AuthGate from './AuthGate'
import AccountMenu from './AccountMenu'
import UserManager from './UserManager'
import type { PlatformUser } from './api'

type Page = 'agents' | 'models' | 'tools' | 'skills' | 'users' | 'knowledge'
type Route = { page: Page; agentId?: string }

function readRoute(): Route {
  const hash = window.location.hash
  const detail = hash.match(/^#\/agents\/([0-9a-f-]{36})$/i)
  if (detail) return { page: 'agents', agentId: detail[1] }
  if (/^#\/models(?:\/[0-9a-f-]{36})?$/i.test(hash)) return { page: 'models' }
  if (hash === '#/knowledge') return { page: 'knowledge' }
  if (hash === '#/tools') return { page: 'tools' }
  if (hash === '#/users') return { page: 'users' }
  if (/^#\/skills(?:\/new|\/[0-9a-f-]{36})?$/i.test(hash)) return { page: 'skills' }
  return { page: 'agents' }
}

export default function App() {
  return <AuthGate>{(user, changed, signedOut) =>
    <Workspace key={user.id} user={user} onUserChanged={changed} onSignedOut={signedOut} />
  }</AuthGate>
}
function Workspace({ user, onUserChanged, onSignedOut }: {
  user: PlatformUser; onUserChanged: (user: PlatformUser) => void; onSignedOut: (message?: string) => void
}) {
  const [route, setRoute] = useState<Route>(readRoute)
  const admin = user.role === 'ADMIN'
  const page = !admin && ['users', 'models', 'tools'].includes(route.page) ? 'agents' : route.page
  const [agents, setAgents] = useState<Agent[]>([])
  const [selected, setSelected] = useState<Agent | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useErrorState()
  const [notice, setNotice] = useState('')
  const [refresh, setRefresh] = useState(0)
  const [creating, setCreating] = useState(false)
  const [search, setSearch] = useState('')
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError('')
    agentApi.list(controller.signal).then(setAgents).catch(e => {
      if (!controller.signal.aborted) setError(errorMessage(e))
    }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [refresh])
  useEffect(() => {
    function syncRoute() {
      setRoute(readRoute())
      setCreating(false)
      setError('')
      setNotice('')
    }
    window.addEventListener('popstate', syncRoute)
    window.addEventListener('hashchange', syncRoute)
    return () => {
      window.removeEventListener('popstate', syncRoute)
      window.removeEventListener('hashchange', syncRoute)
    }
  }, [])

  useEffect(() => {
    setSelected(null)
    if (!route.agentId) return
    const controller = new AbortController()
    agentApi.get(route.agentId, controller.signal).then(agent => {
      if (!controller.signal.aborted) setSelected(agent)
    }).catch(exception => {
      if (!controller.signal.aborted) setError(errorMessage(exception))
    })
    return () => controller.abort()
  }, [route.agentId])

  useEffect(() => {
    if (!admin && ['users', 'models', 'tools'].includes(route.page)) {
      window.history.replaceState(null, '', '#/agents')
      setRoute({ page: 'agents' })
    }
  }, [admin, route.page])
  function navigate(page: Page, agentId?: string) {
    const hash = agentId ? '#/agents/' + agentId : '#/' + page
    if (window.location.hash !== hash) {
      window.history.pushState(null, '', hash)
    }
    setRoute({ page, agentId })
    setCreating(false)
    setError('')
    setNotice('')
  }

  function closeAgent() {
    navigate('agents')
  }

  const filtered = agents.filter(a => `${a.name} ${a.description}`.toLowerCase().includes(search.toLowerCase()))
  return <div className="shell">
    <aside className="sidebar">
      <a className="brand" href="/" aria-label="枫桥首页"><BrandLogo /><span className="brand-name">枫桥<small>feBridge</small></span></a>
      <div className="workspace"><span>枫</span><div>开发工作空间<small>让业务能力成为智能体</small></div></div>
      <div className="nav-label">工作台</div>
      <button className={`nav-item ${page==="agents"?"":"inactive"}`} onClick={() => navigate("agents")}><span aria-hidden="true">◈</span> 智能体 <span className="nav-count">{agents.length}</span></button>
      {admin && <><button className={`nav-item ${page === 'models' ? '' : 'inactive'}`} onClick={() => navigate('models')}>◇ 模型管理</button>
        <button className={`nav-item ${page === 'tools' ? '' : 'inactive'}`} onClick={() => navigate('tools')}>◇ MCP 工具管理</button></>}
      <button className={`nav-item ${page === 'skills' ? '' : 'inactive'}`} onClick={() => navigate('skills')}>◇ Skills 管理</button>
      {admin && <button className={`nav-item ${page === 'users' ? '' : 'inactive'}`} onClick={() => navigate('users')}>◇ 用户与角色</button>}
      <button className={`nav-item ${page === 'knowledge' ? '' : 'inactive'}`} onClick={() => navigate('knowledge')}>◇ 知识库</button>
      <div className="roadmap"><small>接下来</small><p>任务恢复与更多连接</p><small>逐步连接知识、工具和业务系统。</small></div>
      <AccountMenu user={user} onSignedOut={onSignedOut} />
    </aside>
    <main><header className="topbar"><span>工作空间 <span className="divider">/</span> {page==="knowledge"?"知识库":page==="users"?"用户与角色":page==="skills"?"Skills 管理":page==="tools"?"MCP 工具管理":page==="models"?"模型管理":"智能体"}</span><span>feBridge · 0.1</span></header>
      <div className="content">{page==="knowledge"?<KnowledgeManager/>:page==="users"?<UserManager currentUser={user} onCurrentChanged={onUserChanged}/>:page==="skills"?<SkillManager/>:page==="tools"?<ToolManager/>:page==="models"?<ModelManager/>:<><div className="page-heading"><div><div className="eyebrow">AGENT STUDIO</div><h1>{creating ? '创建智能体' : selected ? selected.name : '我的智能体'}</h1><p>{creating ? '从角色和提示词开始，定义你的业务助手。' : selected ? selected.description : '把业务经验写进提示词，构建专属智能体。'}</p></div>{!creating && !selected && <button className="button primary" onClick={() => { setCreating(true); setNotice('') }}>＋ 创建智能体</button>}</div>
      {notice && <div className="notice" role="status">{notice}</div>}
      {creating ? <CreateForm onCancel={() => setCreating(false)} onCreated={agent => {
        navigate("agents", agent.id); setSelected(agent); setSearch(''); setNotice('智能体配置已保存。'); setRefresh(v => v + 1)
      }} /> : route.agentId && !selected ? <div className={error ? "error" : "panel"} role={error ? "alert" : "status"}>{error || "正在加载智能体详情…"}</div> : selected ? <AgentDetail id={selected.id} initial={selected} onUpdated={next => { setSelected(next); setAgents(items => items.map(item => item.id === next.id ? next : item)) }} onClose={() => closeAgent()} /> : <>
        <div className="toolbar"><label className="search"><span aria-hidden="true">⌕</span><input aria-label="搜索智能体" placeholder="搜索名称或描述…" value={search} onChange={e => setSearch(e.target.value)}/></label><button className="button secondary" disabled={loading} onClick={() => setRefresh(v => v + 1)}>{loading ? '加载中…' : '刷新列表'}</button></div>
        {error && <div className="error" role="alert">{error}</div>}
        <div className="section-caption"><span>智能体配置</span><span>{loading ? '正在获取' : `最近 ${agents.length} 个`}</span></div>
        {!loading && !error && agents.length === 0 && <div className="empty"><span className="empty-icon">◈</span><h2>从第一个智能体开始</h2><p>给它一个名字，描述职责，再写下清晰的提示词。</p><button className="button primary" onClick={() => setCreating(true)}>创建第一个智能体</button></div>}
        {!loading && agents.length > 0 && filtered.length === 0 && <div className="empty"><h2>没有找到匹配的智能体</h2><p>试试其他名称或关键词。</p></div>}
        <div className="agent-grid">{filtered.map(agent => <button className="agent-card" key={agent.id} onClick={() => navigate("agents", agent.id)}><div className="card-top"><span className="agent-avatar">{agent.name.slice(0, 1)}</span><span className="tag">配置</span></div><h2>{agent.name}</h2><p>{agent.description || '暂无描述'}</p><div className="card-footer"><span>{agent.modelId ? '已绑定模型' : '未绑定模型'}</span><span aria-hidden="true">↗</span></div></button>)}</div>

      </>}
      </>}
      <footer className="page-footer">以清晰的指令连接业务能力。<span>枫桥 feBridge</span></footer></div>
    </main>
  </div>
}
function AgentDetail({ id, initial, onClose, onUpdated }: { id: string; initial: Agent; onClose: () => void; onUpdated: (agent: Agent) => void }) {
  const [agent, setAgent] = useState(initial)
  const [error, setError] = useErrorState()
  useEffect(() => {
    const controller = new AbortController()
    setAgent(initial)
    setError('')
    agentApi.get(id, controller.signal).then(setAgent).catch(exception => {
      if (!controller.signal.aborted) setError(errorMessage(exception))
    })
    return () => controller.abort()
  }, [id, initial])

  return <section className="agent-workbench" aria-label="智能体详情">
    <div className="workbench-heading">
      <span>对话工作台</span>
      <button className="button secondary" onClick={onClose}>← 返回智能体列表</button>
    </div>
    {error && <div className="error" role="alert">{error}</div>}
    <AgentRuntime key={agent.id} agent={agent} onBound={next => { setAgent(next); onUpdated(next) }} />
  </section>
}
function CreateForm({ onCancel, onCreated }: { onCancel: () => void; onCreated: (agent: Agent) => void }) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [prompt, setPrompt] = useState('')
  const [modelId, setModelId] = useState('')
  const [parameters, setParameters] = useState('{}')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useErrorState()
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (saving) return
    setError('')
    if (!name.trim() || !prompt.trim()) { setError('名称和系统提示词不能为空。'); return }
    let config: Record<string, unknown>
    try {
      const value: unknown = JSON.parse(parameters)
      if (value === null || Array.isArray(value) || typeof value !== 'object') throw new Error()
      config = value as Record<string, unknown>

    } catch { setError('模型参数必须是合法的 JSON 对象，例如 {"temperature": 0.2}。'); return }
    setSaving(true)
    try { onCreated(await agentApi.create({ name: name.trim(), description, systemPrompt: prompt, modelConfig: config, modelId })) }
    catch (e) { setError(errorMessage(e)) }
    finally { setSaving(false) }
  }
  return <form className="panel create-form" onSubmit={submit}><fieldset disabled={saving}>
    <div className="form-section"><span className="step">01</span><div><h2>基本信息</h2><p>让团队快速了解智能体的职责。</p></div></div>
    <label>名称 <span className="required">*</span><input required maxLength={100} value={name} onChange={e => setName(e.target.value)} placeholder="例如：客户查询助手" autoFocus/></label>
    <label>描述<textarea maxLength={1000} rows={2} value={description} onChange={e => setDescription(e.target.value)} placeholder="它为谁服务？可以解决什么问题？"/></label>
    <div className="form-section"><span className="step">02</span><div><h2>角色与指令</h2><p>写清角色、处理步骤和期望输出。</p></div></div>
    <label>系统提示词 <span className="required">*</span><textarea required maxLength={100000} rows={7} value={prompt} onChange={e => setPrompt(e.target.value)} placeholder="你是一名销售助手，请根据提供的客户资料分析需求，并给出可执行的跟进建议。"/></label>
    <div className="form-section"><span className="step">03</span><div><h2>模型配置</h2><p>选择已配置的模型，并按需覆盖调用参数。</p></div></div>
    <ModelSelect value={modelId} onChange={setModelId} required/>
    <label>参数（JSON 对象）<textarea className="code" rows={4} value={parameters} onChange={e => setParameters(e.target.value)} spellCheck={false}/></label>
    {error && <div className="error" role="alert">{error}</div>}
    <div className="form-actions"><button className="button secondary" type="button" onClick={onCancel}>取消</button><button className="button primary" type="submit">{saving ? '保存中…' : '保存智能体'}</button></div>
  </fieldset></form>
}