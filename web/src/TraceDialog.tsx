import { useEffect, useRef, useState } from 'react'
import type { RunEvent } from './api'
import { durationText, tokenText } from './traceModel'
import type { TraceModel, TraceNode } from './traceModel'

const statusLabel = { running: '执行中', completed: '成功', failed: '失败', interrupted: '已中断' }
const typeLabel = { agent: 'AGENT', context: 'CONTEXT', model: 'LLM', tool: 'TOOL', event: 'EVENT', memory: 'MEMORY' }
function JsonView({ value, empty }: { value: unknown; empty: string }) {
  return value === null || value === undefined
    ? <p className="trace-empty">{empty}</p>
    : <pre className="trace-json">{JSON.stringify(value, null, 2)}</pre>
}
function NodeDetails({ node, events }: { node: TraceNode; events: RunEvent[] }) {
  const [tab, setTab] = useState<'input' | 'output' | 'metadata' | 'events'>('output')
  return <section className="trace-inspector">
    <div className="trace-inspector-title"><span className={'trace-type ' + node.type}>{typeLabel[node.type]}</span>
      <h3>{node.name}</h3><span className={'trace-status ' + node.status}>{statusLabel[node.status]}</span></div>
    <p className="trace-action">{node.action}</p>
    <div className="trace-node-metrics">
      <div><small>耗时</small><strong>{durationText(node.duration)}</strong></div>
      <div><small>输入 Token</small><strong>{node.type === 'model' || node.type === 'memory' || node.type === 'agent' ? tokenText(node.usage.input) : '—'}</strong></div>
      <div><small>输出 Token</small><strong>{node.type === 'model' || node.type === 'memory' || node.type === 'agent' ? tokenText(node.usage.output) : '—'}</strong></div>
      <div><small>总 Token</small><strong>{node.type === 'model' || node.type === 'memory' || node.type === 'agent' ? tokenText(node.usage.total) : '—'}</strong></div>
    </div>
    <dl className="trace-node-info">
      <dt>节点 ID</dt><dd>{node.id}</dd>
      <dt>开始时间</dt><dd>{new Date(node.start).toLocaleString('zh-CN', { hour12: false })}</dd>
      <dt>结束时间</dt><dd>{node.end === null ? '尚未结束' : new Date(node.end).toLocaleString('zh-CN', { hour12: false })}</dd>
      {node.type === 'tool' && <><dt>关联关系</dt><dd>由父级模型调用发起；工具执行时间单独计算。</dd></>}
    </dl>
    <div className="trace-tabs" role="tablist" aria-label="节点详情">
      {([['input','输入'],['output','输出'],['metadata','配置与元数据'],['events','原始事件']] as const).map(([id, label]) =>
        <button key={id} type="button" role="tab" aria-selected={tab === id}
          onClick={() => setTab(id)}>{label}</button>)}
    </div>
    <div role="tabpanel" className="trace-tab-content">
      {tab === 'input' && <JsonView value={node.input} empty="此节点没有保存输入快照；旧链路仅记录调用摘要。" />}
      {tab === 'output' && <JsonView value={node.output} empty="此节点尚无输出。" />}
      {tab === 'metadata' && <JsonView value={node.metadata} empty="暂无配置。" />}
      {tab === 'events' && <JsonView value={events} empty="暂无事件。" />}
    </div>
  </section>
}

export default function TraceDialog({ requestId, trace, events, error, onClose }: {
  requestId: string; trace: TraceModel; events: RunEvent[]; error: string; onClose: () => void
}) {
  const ref = useRef<HTMLDialogElement>(null)
  const [selected, setSelected] = useState('run')
  const [filter, setFilter] = useState<'all' | 'model' | 'tool' | 'failed'>('all')
  const node = trace.nodes.find(item => item.id === selected) ?? trace.nodes[0]
  const root = trace.nodes[0]
  const span = Math.max(1, (root?.end ?? Date.now()) - (root?.start ?? Date.now()))
  const selectedEvents = events.filter(event => node?.eventIds.includes(event.id))
  useEffect(() => {
    const element = ref.current
    element?.showModal()
    return () => element?.close()
  }, [])
  return <dialog ref={ref} className="trace-dialog" aria-label="调用轨迹"
    onClose={event => { if (!event.currentTarget.open) onClose() }}>
    <header className="trace-dialog-header">
      <div><small>OBSERVABILITY / TRACE</small><h2>调用轨迹 <span className={'trace-status ' + trace.status}>{statusLabel[trace.status]}</span></h2>
        <p>Trace ID · <code>{requestId}</code></p></div>
      <button type="button" className="button secondary" onClick={onClose} aria-label="关闭调用轨迹">✕</button>
    </header>
    <div className="trace-overview">
      <div><small>总耗时</small><strong>{durationText(trace.duration)}</strong></div>
      <div><small>总 Token{trace.partialUsage ? ' · 已知部分' : ''}</small><strong>{tokenText(trace.usage.total)}</strong></div>
      <div><small>输入 / 输出 Token</small><strong>{tokenText(trace.usage.input)} <span>/</span> {tokenText(trace.usage.output)}</strong></div>
      <div><small>模型 / 工具调用</small><strong>{trace.modelCalls} <span>/</span> {trace.toolCalls}</strong></div>
    </div>
    {error && <p role="alert" className="trace-dialog-error">{error}</p>}
    <div className="trace-dialog-body">
      <aside className="trace-tree-panel">
        <div className="trace-tree-toolbar"><h3>执行节点 <small>{trace.nodes.length}</small></h3>
          <select aria-label="筛选轨迹节点" value={filter} onChange={e => setFilter(e.target.value as typeof filter)}>
            <option value="all">全部节点</option><option value="model">模型调用</option>
            <option value="tool">工具调用</option><option value="failed">失败节点</option>
          </select></div>
        <div className="trace-timeline-label"><span>调用树</span><span>时间分布</span></div>
        <div className="trace-node-list">
          {trace.nodes.filter(item => filter === 'all' || (filter === 'failed' ? item.status === 'failed' : (item.type === filter || (filter === 'model' && item.type === 'memory')))).map(item => {
            const offset = Math.min(100, Math.max(0, (item.start - (root?.start ?? item.start)) / span * 100))
            const width = Math.min(100 - offset, Math.max(1, ((item.end ?? Date.now()) - item.start) / span * 100))
            return <button type="button" key={item.id} className={'trace-node-row ' + (selected === item.id ? 'selected ' : '') + item.status}
              style={{ paddingLeft: item.type === 'tool' ? 34 : item.type === 'agent' ? 12 : 22 }}
              aria-pressed={selected === item.id} onClick={() => setSelected(item.id)}>
              <div className="trace-node-name"><span className={'trace-type ' + item.type}>{typeLabel[item.type]}</span><strong>{item.name}</strong></div>
              <div className="trace-node-subtitle"><span>{statusLabel[item.status]}</span><span>{durationText(item.duration)}</span>
                {(item.type === 'model' || item.type === 'memory') && <span>{tokenText(item.usage.total)} Token</span>}</div>
              <div className="trace-time-track"><span style={{ marginLeft: offset + '%', width: width + '%' }} /></div>
            </button>
          })}
        </div>
      </aside>
      {node ? <NodeDetails key={node.id} node={node} events={selectedEvents} /> : <p className="trace-empty">暂无轨迹节点。</p>}
    </div>
    <footer className="trace-dialog-footer">Token 来自模型返回的 usage，仅对模型响应汇总一次；未返回的用量不会估算。详情经过字段脱敏与长度限制，不包含内部推理。</footer>
  </dialog>
}
