import { useErrorState } from './ErrorNotice'
import { useState } from 'react'
import { toolApi, errorMessage } from './api'
import type { ToolAuditRecord } from './api'
export default function ToolAudit({ agentId, requestId }: { agentId: string; requestId: string }) {
  const [items, setItems] = useState<ToolAuditRecord[] | null>(null)
  const [error, setError] = useErrorState()
  return <details className="tool-audit" onToggle={e => {
    if (!e.currentTarget.open) return
    toolApi.audits(agentId, requestId).then(setItems).catch(e => setError(errorMessage(e)))
  }}>
    <summary>查看工具执行记录</summary>
    {error && <p>{error}</p>}
    {items === null && !error && <p>读取中…</p>}
    {items?.length === 0 && <p>本轮没有调用工具。</p>}
    {items?.map(item => <p key={item.id}>{item.toolName} · {({ completed: '成功', failed: '失败', denied: '拒绝', running: '执行中' } as Record<string,string>)[item.status]} · {item.durationMillis} ms{item.errorCode && ' · ' + item.errorCode}</p>)}
  </details>
}
