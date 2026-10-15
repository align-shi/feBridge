import type { ConversationMemoryView } from './api'

export default function ConversationMemory({ memory }: { memory: ConversationMemoryView | null }) {
  const content = memory?.content
  const sections = [
    ['已确认事实', content?.confirmedFacts ?? []],
    ['待确认事项', content?.pendingQuestions ?? []],
    ['编号与引用', content?.references ?? []],
  ] as const
  return <details className="conversation-memory">
    <summary><span className="memory-indicator" /><strong>会话记忆</strong>
      <span>{memory?.version ? '已整理 ' + memory.coveredTurns + ' 轮历史 · v' + memory.version : '尚未生成摘要'}</span>
      {!!memory?.pendingTurns && <small>{memory.pendingTurns} 轮待整理</small>}
    </summary>
    <div className="memory-content">
      <p className="memory-description">只用于当前会话。较早的成功对话会整理为摘要，再与近期消息一起提供给模型。</p>
      {memory?.version ? <>
        <div className="memory-goal"><small>会话目标</small><p>{content?.goal || '尚无明确目标'}</p></div>
        <div className="memory-sections">{sections.map(([title, items]) => <section key={title}>
          <h4>{title}</h4>{items.length ? <ul>{items.map((item, i) => <li key={i}>{item}</li>)}</ul> : <p className="memory-empty">暂无记录</p>}
        </section>)}</div>
        <div className="memory-meta"><span>更新于 {memory.updatedAt ? new Date(memory.updatedAt).toLocaleString('zh-CN') : '—'}</span>
          <span>模型 {memory.model || '—'}</span><span>本次摘要 {memory.usage.totalTokens ?? '未返回'} Token</span></div>
        {memory.inputTruncated && <p className="memory-warning">部分历史输入经过截断，摘要只能保留可见的信息。原始消息仍然保留。</p>}
      </> : <p className="memory-empty">会话较短时直接使用近期消息；历史超出上下文窗口后，在下一次发送时自动生成摘要。</p>}
      {!!memory?.pendingTurns && <p className="memory-warning">还有 {memory.pendingTurns} 轮较早历史待整理，将在后续发送时分批更新。查看本面板不会调用模型。</p>}
    </div>
  </details>
}
