import { useEffect, useRef, useState } from 'react'
import type { McpServer, McpTool } from './api'

export default function ServerToolsDialog({ server, tools, busy, error, onClose, onDiscover, onPolicy }: {
  server: McpServer
  tools: McpTool[]
  busy: boolean
  error: string
  onClose: () => void
  onDiscover: () => void
  onPolicy: (tool: McpTool) => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [search, setSearch] = useState('')
  const [expanded, setExpanded] = useState<string | null>(null)
  useEffect(() => {
    const element = dialog.current
    element?.showModal()
    return () => element?.close()
  }, [])
  const keyword = search.trim().toLowerCase()
  const filtered = tools.filter(tool => (tool.name + ' ' + tool.description).toLowerCase().includes(keyword))

  return <dialog ref={dialog} className="mcp-tools-dialog" aria-labelledby="mcp-tools-title"
    onClose={event => { if (!event.currentTarget.open) onClose() }}
    onClick={event => {
      if (event.target !== event.currentTarget) return
      const bounds = event.currentTarget.getBoundingClientRect()
      if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) onClose()
    }}>
    <header className="mcp-tools-heading">
      <div><small>服务工具</small><h2 id="mcp-tools-title">{server.name}</h2><p>{tools.length} 个工具 · {tools.filter(tool => tool.enabled).length} 个已启用</p></div>
      <button className="button secondary" onClick={onClose} aria-label="关闭工具弹窗">✕</button>
    </header>
    <div className="mcp-tools-toolbar">
      <input autoFocus aria-label="搜索服务工具" placeholder="搜索工具名称或描述…" value={search} onChange={event => setSearch(event.target.value)} />
      <button className="button secondary" disabled={busy || !server.enabled} onClick={onDiscover}>{busy ? '处理中…' : '发现 / 同步工具'}</button>
    </div>
    <p className="mcp-tools-hint">发现的工具默认启用；可手动禁用，同步工具会保留你的启停设置。</p>
    {!server.enabled && <p className="mcp-tools-hint">服务已停用，请先启用服务。</p>}
    {error && <div className="error" role="alert">{error}</div>}
    <div className="mcp-tools-scroll">
      {!tools.length ? <div className="mcp-tools-empty">尚未发现工具，请先发现 / 同步工具。</div>
        : !filtered.length ? <div className="mcp-tools-empty">没有匹配的工具，请调整关键词。</div>
        : filtered.map(tool => <article className="mcp-tool-row" key={tool.id}>
          <div className="mcp-tool-summary">
            <div className="mcp-tool-info"><h3>{tool.name} <span className="tag">{tool.enabled ? '已启用' : '未启用'}</span></h3>
              <p>{tool.description || '暂无描述'}</p>
            </div>
            <div className="mcp-tool-actions">
              <button className="button secondary" aria-expanded={expanded === tool.id}
                onClick={() => setExpanded(expanded === tool.id ? null : tool.id)}>{expanded === tool.id ? '收起参数' : '查看参数'}</button>
              <button className="button secondary" disabled={busy || !server.enabled} onClick={() => onPolicy(tool)}>
                {tool.enabled ? '禁用' : '启用'}
              </button>
            </div>
          </div>
          {expanded === tool.id && <pre className="mcp-tool-schema">{JSON.stringify(tool.inputSchema, null, 2)}</pre>}
        </article>)}
    </div>
    <footer className="mcp-tools-footer"><span>{keyword ? '匹配 ' + filtered.length + ' / ' + tools.length : '工具仅属于当前服务'}</span>
      <button className="button secondary" onClick={onClose}>关闭</button>
    </footer>
  </dialog>
}
