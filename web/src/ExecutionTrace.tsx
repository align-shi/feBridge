import { useEffect, useRef, useState } from 'react'
import { runApi, errorMessage } from './api'
import type { RunEvent } from './api'
import { buildTrace, durationText, tokenText } from './traceModel'
import TraceDialog from './TraceDialog'

export default function ExecutionTrace({ agentId, requestId, pending }: {
  agentId: string; requestId: string; pending: boolean
}) {
  const element = useRef<HTMLDetailsElement>(null)
  const [visible, setVisible] = useState(false)
  const [open, setOpen] = useState(false)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [events, setEvents] = useState<RunEvent[] | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    const observer = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) setVisible(true)
    })
    if (element.current) observer.observe(element.current)
    return () => observer.disconnect()
  }, [])
  useEffect(() => {
    if (!visible && !open && !dialogOpen) return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined
    async function load() {
      try {
        const items = await runApi.events(agentId, requestId, controller.signal)
        if (controller.signal.aborted) return
        setEvents(items)
        setError('')
        const terminal = items.some(item => item.kind === 'RUN_COMPLETED' || item.kind === 'RUN_FAILED')
        if (pending && !terminal) timer = setTimeout(() => void load(), 2000)
      } catch (e) {
        if (!controller.signal.aborted) setError(errorMessage(e))
      }
    }
    void load()
    return () => {
      controller.abort()
      if (timer) clearTimeout(timer)
    }
  }, [agentId, requestId, pending, visible, open, dialogOpen])
  const trace = buildTrace(events ?? [], Date.now(), pending)
  return <>
    <details ref={element} className="execution-trace" onToggle={event => setOpen(event.currentTarget.open)}>
      <summary><span className="trace-label">调用轨迹</span><span className={trace.status === 'failed' ? 'trace-failed' : ''}>
        {error ? '链路读取失败' : trace.latest || (pending ? '等待执行进度…' : events === null ? '读取链路…' : '暂无历史轨迹')}
      </span><small>{events?.length ? durationText(trace.duration) + ' · ' + tokenText(trace.usage.total) + ' Token' : ''}</small></summary>
      {error && <p className="trace-note">{error}，可收起后重新展开。</p>}
      {events?.length === 0 && <p className="trace-note">{pending ? '请求正在开始，请稍候。' : '这条对话没有已保存的执行事件；新发送的对话会记录调用轨迹。'}</p>}
      {!!events?.length && <div className="trace-preview">
        <div><strong>{trace.modelCalls} 次模型调用 · {trace.toolCalls} 次工具调用</strong>
          <p>输入 {tokenText(trace.usage.input)} / 输出 {tokenText(trace.usage.output)} Token{trace.partialUsage ? '（部分用量未返回）' : ''}</p></div>
        <button type="button" className="button secondary" onClick={() => setDialogOpen(true)}>查看完整轨迹 ↗</button>
      </div>}
    </details>
    {dialogOpen && <TraceDialog requestId={requestId} trace={trace} events={events ?? []} error={error} onClose={() => setDialogOpen(false)} />}
  </>
}
