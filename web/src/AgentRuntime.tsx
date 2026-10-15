import { useErrorState } from './ErrorNotice'
import { useState } from 'react'
import type { FormEvent } from 'react'
import { agentApi, errorMessage } from './api'
import type { Agent } from './api'
import ModelSelect from './ModelSelect'
import ChatWorkspace from './ChatWorkspace'
import AgentKnowledgeBases from './AgentKnowledgeBases'
import MemoryModeSelector from './MemoryModeSelector'
import type { MemoryMode } from './api'
import { AgentMcpServers } from './ToolManager'
import AgentSettings from './AgentSettings'

export default function AgentRuntime({
  agent,
  onBound,
}: {
  agent: Agent
  onBound: (agent: Agent) => void
}) {
  const [modelId, setModelId] = useState(agent.modelId || '')
  const [parameters, setParameters] = useState(JSON.stringify(
    Object.fromEntries(Object.entries(agent.modelConfig).filter(([key]) => key !== 'model')),
    null, 2
  ))
  const [error, setError] = useErrorState()
  const [notice, setNotice] = useState('')
  const [binding, setBinding] = useState(false)
  const [busy, setBusy] = useState(false)
  const [configSaving, setConfigSaving] = useState(false)
  const [memoryMode, setMemoryMode] = useState<MemoryMode | null>(null)
  const [memorySaving, setMemorySaving] = useState(false)

  async function bind(event: FormEvent) {
    event.preventDefault()
    if (binding || busy || configSaving) return
    setError('')
    setNotice('')
    let value: Record<string, unknown>
    try {
      const parsed: unknown = JSON.parse(parameters)
      if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw new Error()
      value = parsed as Record<string, unknown>
    } catch {
      setError('覆盖参数必须为 JSON 对象。')
      return
    }
    setBinding(true)
    try {
      onBound(await agentApi.bind(agent.id, modelId, value))
      setNotice('模型绑定已保存。')
    } catch (exception) {
      setError(errorMessage(exception))
    } finally {
      setBinding(false)
    }
  }

  return <div className="runtime">
    <AgentSettings agent={agent} disabled={binding || busy || configSaving || memorySaving} onSaved={onBound} onSaving={setConfigSaving} />
    <details className="agent-settings" open={!agent.modelId}>
      <summary>调用模型与参数{!agent.modelId && ' · 请先配置'}</summary>
      <form onSubmit={bind}>
        <fieldset disabled={binding || busy || configSaving || memorySaving}>
          <ModelSelect value={modelId} onChange={setModelId} required />
          <label>智能体参数覆盖（JSON）<textarea
            className="code" rows={3} value={parameters}
            onChange={event => setParameters(event.target.value)}
          /></label>
          <button className="button secondary">{binding ? '保存中…' : '保存模型绑定'}</button>
        </fieldset>
      </form>
    </details>
    {error && <div className="error" role="alert">{error}</div>}
    {notice && <div className="notice" role="status">{notice}</div>}
    <AgentMcpServers agentId={agent.id} disabled={binding || busy || configSaving || memorySaving} />
    <AgentKnowledgeBases key={agent.id + '-knowledge'} agentId={agent.id} disabled={binding || busy || configSaving || memorySaving} onSaving={setConfigSaving} />
    <MemoryModeSelector key={agent.id} agentId={agent.id} disabled={binding || busy || configSaving || memorySaving} onChange={setMemoryMode} onSaving={setMemorySaving} />
    <ChatWorkspace agent={agent} memoryMode={memoryMode} disabled={binding || configSaving || memorySaving} onBusy={setBusy} />
  </div>
}
