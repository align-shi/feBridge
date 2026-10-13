import { useErrorState } from './ErrorNotice'
import { useEffect, useState } from 'react'
import { toolApi, errorMessage } from './api'
import type { McpServer, McpTool } from './api'
import ServerToolsDialog from './ServerToolsDialog'

export default function ToolManager() {
  const [toolServerId, setToolServerId] = useState<string | null>(null)
  const [servers, setServers] = useState<McpServer[]>([])
  const [tools, setTools] = useState<McpTool[]>([])
  const [editingId, setEditingId] = useState<string | null>(null)
  const [name, setName] = useState('本地客户查询演示')
  const [endpoint, setEndpoint] = useState('http://127.0.0.1:8090/mcp')
  const [token, setToken] = useState('')
  const [authType, setAuthType] = useState<McpServer['authType']>('BEARER')
  const [authHeader, setAuthHeader] = useState('X-API-Key')
  const [error, setError] = useErrorState()
  const [busy, setBusy] = useState(false)
  async function load() {
    const [s, t] = await Promise.all([toolApi.servers(), toolApi.tools()])
    setServers(s); setTools(t)
  }
  useEffect(() => { load().catch(e => setError(errorMessage(e))) }, [])
  async function act(work: () => Promise<unknown>) {
    setBusy(true); setError('')
    try { await work(); await load(); return true }
    catch (e) { setError(errorMessage(e)); return false }
    finally { setBusy(false) }
  }
  const toolServer = servers.find(server => server.id === toolServerId)
  return <section>
    <div className="page-heading"><div><div className="eyebrow">BUSINESS TOOLS</div><h1>MCP 工具管理</h1><p>注册业务服务，检查工具定义，再授权给智能体。</p></div></div>
    {error && <div className="error" role="alert">{error}</div>}
    <form className="panel create-form" onSubmit={e => {
      e.preventDefault()
      void act(async () => {
        const old = servers.find(s => s.id === editingId)
        await toolApi.save(editingId, { name, endpoint, token, authType, authHeader: authType === 'CUSTOM_HEADER' ? authHeader : null, enabled: old?.enabled ?? true, timeoutSeconds: old?.timeoutSeconds ?? 15, resultLimit: old?.resultLimit ?? 16000 })
        setToken(''); setEditingId(null); setAuthType('BEARER'); setAuthHeader('X-API-Key')
      })
    }}>
      <fieldset disabled={busy}><h2>{editingId ? '修改 MCP 服务' : '注册 MCP 服务'}</h2>
        <label>服务名称<input required maxLength={100} value={name} onChange={e => setName(e.target.value)} /></label>
        <label>Streamable HTTP 地址<input required type="url" value={endpoint} onChange={e => setEndpoint(e.target.value)} /></label>
        <label>鉴权方式<select value={authType} onChange={e => setAuthType(e.target.value as McpServer['authType'])}>
          <option value="NONE">无鉴权</option>
          <option value="BEARER">Bearer Token</option>
          <option value="CUSTOM_HEADER">自定义请求头</option>
        </select></label>
        {authType === 'CUSTOM_HEADER' && <label>鉴权 Key（请求头名称）<input required maxLength={100} placeholder="例如 X-API-Key、api-key" value={authHeader} onChange={e => setAuthHeader(e.target.value)} /></label>}
        {authType !== 'NONE' && <label>{authType === 'BEARER' ? 'Bearer Token' : '鉴权 Value'}（修改时留空保留原密钥）<input type="password" maxLength={4096} autoComplete="new-password" value={token} onChange={e => setToken(e.target.value)} /></label>}
        <p className="helper">{authType === 'NONE' ? '不发送鉴权请求头；保存后清除原密钥。' : authType === 'BEARER' ? '发送 Authorization: Bearer [Token]。只需填写 Token。' : '使用填写的 Key 和 Value 发送请求头（Value 首尾空白会去除），不自动添加 Bearer 前缀。'}</p>
        <button className="button primary">{editingId ? '保存修改' : '注册服务'}</button>{editingId && <button type="button" className="button secondary" onClick={() => { setEditingId(null); setToken(''); setAuthType('BEARER'); setAuthHeader('X-API-Key') }}>取消修改</button>}
      </fieldset>
    </form>
    <div className="tool-services">{servers.map(server => <div className="panel" key={server.id}>
      <button className="button secondary" disabled={busy} onClick={() => { setEditingId(server.id); setName(server.name); setEndpoint(server.endpoint); setToken(''); setAuthType(server.authType); setAuthHeader(server.authHeader || 'X-API-Key') }}>修改地址 / 凭证</button>
      <h3>{server.name} · {server.enabled ? '已启用' : '已停用'}</h3>
      <p className="helper">{server.endpoint} · {server.tokenConfigured ? '已配置凭证 · ' + (server.authType === 'CUSTOM_HEADER' ? server.authHeader : 'Bearer Token') : '无凭证'}</p>
      <button className="button secondary" onClick={() => { setError(''); setToolServerId(server.id) }}>查看工具（{tools.filter(tool => tool.serverId === server.id).length}）</button>{' '}
      <button className="button secondary" disabled={busy || !server.enabled} onClick={async () => { if (await act(() => toolApi.discover(server.id))) setToolServerId(server.id) }}>发现 / 同步工具</button>{' '}
      <button className="button secondary" disabled={busy} onClick={() => void act(() => toolApi.save(server.id, { ...server, token: '', enabled: !server.enabled }))}>{server.enabled ? '停用服务' : '启用服务'}</button>
    </div>)}</div>
    {toolServer && <ServerToolsDialog key={toolServer.id} server={toolServer}
      tools={tools.filter(tool => tool.serverId === toolServer.id)} busy={busy} error={error}
      onClose={() => setToolServerId(null)}
      onDiscover={() => void act(() => toolApi.discover(toolServer.id))}
      onPolicy={tool => void act(() => toolApi.policy(tool.id, !tool.enabled))}
    />}
  </section>
}

export function AgentMcpServers({ agentId, disabled }: { agentId: string; disabled: boolean }) {
  const [servers, setServers] = useState<McpServer[]>([])
  const [tools, setTools] = useState<McpTool[]>([])
  const [ids, setIds] = useState<string[]>([])
  const [busy, setBusy] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useErrorState()
  useEffect(() => {
    let live = true
    Promise.all([toolApi.servers(), toolApi.boundServers(agentId), toolApi.tools()]).then(([all, bound, items]) => {
      if (!live) return
      setServers(all); setTools(items); setIds(bound.map(server => server.id)); setLoaded(true)
    }).catch(e => { if (live) setError(errorMessage(e)) })
    return () => { live = false }
  }, [agentId])
  return <details className="agent-settings">
    <summary>绑定 MCP 服务 · {ids.length} 个</summary>
    <p className="helper">绑定服务后可使用该服务下全部已启用工具，新发现的工具自动可用。停用服务或工具后不可调用。</p>
    {loaded && !servers.length && <p>请先在 MCP 工具管理中注册服务。</p>}
    <fieldset disabled={busy || disabled || !loaded}>{servers.map(server => <label className="tool-choice" key={server.id}>
      <input type="checkbox" checked={ids.includes(server.id)} disabled={!server.enabled && !ids.includes(server.id)}
        onChange={event => setIds(old => event.target.checked ? [...old, server.id] : old.filter(id => id !== server.id))} />
      <span>{server.name}{!server.enabled && '（已停用）'} · {tools.filter(tool => tool.serverId === server.id && tool.enabled).length} 个可用工具</span>
    </label>)}
    <button className="button secondary" onClick={async () => {
      setBusy(true); setMessage(''); setError('')
      try { await toolApi.bindServers(agentId, ids); setMessage('MCP 服务绑定已保存。') }
      catch (e) { setError(errorMessage(e)) }
      finally { setBusy(false) }
    }}>保存 MCP 绑定</button></fieldset>
    {error && <div className="error" role="alert">{error}</div>}
    {message && <p role="status">{message}</p>}
  </details>
}
