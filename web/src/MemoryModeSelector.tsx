import { useEffect, useState } from 'react'
import { errorMessage, agentMemoryApi } from './api'
import type { MemoryMode } from './api'
import { useErrorState } from './ErrorNotice'

const options: { value: MemoryMode; name: string; description: string }[] = [
  { value: 'NONE', name: '无记忆', description: '仅发送本次问题，不带入历史或长期记忆。' },
  { value: 'CONTEXT', name: '上下文记忆', description: '使用当前会话的历史与摘要，不跨会话。' },
  { value: 'CROSS_SESSION', name: '跨会话记忆', description: '使用当前会话上下文，并记录和召回长期记忆。' },
]
export default function MemoryModeSelector({ agentId, disabled, onChange, onSaving }: {
  agentId: string; disabled: boolean; onChange: (mode: MemoryMode) => void; onSaving: (busy: boolean) => void
}) {
  const [mode, setMode] = useState<MemoryMode | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useErrorState()
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    setError('')
    agentMemoryApi.get(agentId, controller.signal).then(view => {
      setMode(view.mode); onChange(view.mode)
    }).catch(e => { if (!controller.signal.aborted) setError(errorMessage(e)) })
    return () => controller.abort()
  }, [agentId, retry, onChange])
  async function change(next: MemoryMode) {
    if (disabled || busy || next === mode) return
    setBusy(true); onSaving(true); setError('')
    try {
      await agentMemoryApi.set(agentId, next)
      setMode(next); onChange(next)
    } catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false); onSaving(false) }
  }
  return <details className="agent-settings memory-mode-settings">
    <summary>记忆模式 · {options.find(option => option.value === mode)?.name ?? '读取中…'}</summary>
    <div className="memory-mode-selector" aria-label="记忆模式">
    <div className="memory-mode-heading"><strong>记忆模式</strong><small>{busy ? '正在保存…' : mode ? '已保存 · 应用于此智能体' : '正在读取…'}</small></div>
    <div className="memory-mode-options" role="group" aria-label="选择记忆模式">
      {options.map(option => <button type="button" key={option.value}
        className={'memory-mode-option' + (mode === option.value ? ' selected' : '')}
        aria-pressed={mode === option.value} disabled={disabled || busy || !mode}
        onClick={() => { void change(option.value) }}>
        <strong>{option.name}</strong><span>{option.description}</span>
      </button>)}
    </div>
    {error && <p role="alert">{error} <button type="button" className="button secondary" disabled={busy} onClick={() => setRetry(n => n + 1)}>重新读取</button></p>}
    <p className="helper">模式应用于此智能体的所有用户；会话和长期记忆内容按用户隔离。</p>
    </div>
  </details>
}
