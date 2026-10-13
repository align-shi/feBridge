import { useErrorState } from './ErrorNotice'
import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { conversationApi, errorMessage } from './api'
import ExecutionTrace from './ExecutionTrace'
import type { Agent, Conversation, ConversationHistory } from './api'

export default function ChatWorkspace({
  agent,
  disabled,
  onBusy,
}: {
  agent: Agent
  disabled: boolean
  onBusy: (busy: boolean) => void
}) {
  const storageKey = `febridge:last-session:${agent.id}`
  const [sessions, setSessions] = useState<Conversation[]>([])
  const [activeId, setActiveId] = useState('')
  const [history, setHistory] = useState<ConversationHistory | null>(null)
  const [loading, setLoading] = useState(true)
  const [historyLoading, setHistoryLoading] = useState(false)
  const [sending, setSending] = useState(false)
  const [creating, setCreating] = useState(false)
  const [draft, setDraft] = useState('')
  const [error, setError] = useErrorState()
  const [revision, setRevision] = useState(0)
  const messageList = useRef<HTMLDivElement>(null)
  const pendingRequest = useRef<string | null>(null)
  const active = useRef('')
  const mounted = useRef(true)

  useEffect(() => {
    mounted.current = true
    const controller = new AbortController()
    conversationApi.list(agent.id, controller.signal)
      .then(items => {
        setSessions(items)
        let saved = ''
        try { saved = localStorage.getItem(storageKey) || '' } catch { /* Storage is optional. */ }
        const id = items.some(item => item.id === saved) ? saved : items[0]?.id || ''
        setActiveId(id)
      })
      .catch(e => {
        if (!controller.signal.aborted) setError(errorMessage(e))
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => {
      mounted.current = false
      controller.abort()
    }
  }, [agent.id, storageKey])

  useEffect(() => {
    active.current = activeId
    if (!activeId) return
    try { localStorage.setItem(storageKey, activeId) } catch { /* Storage is optional. */ }
    const controller = new AbortController()
    setHistoryLoading(true)
    conversationApi.history(agent.id, activeId, controller.signal)
      .then(next => {
        setHistory(previous => {
          const pending = pendingRequest.current
          if (pending && previous?.session.id === activeId && !next.messages.some(item => item.requestId === pending)) {
            return previous
          }
          return next
        })
      })
      .catch(e => {
        if (!controller.signal.aborted) setError(errorMessage(e))
      })
      .finally(() => {
        if (!controller.signal.aborted) setHistoryLoading(false)
      })
    return () => controller.abort()
  }, [agent.id, activeId, storageKey, revision])

  const currentHistory = history?.session.id === activeId ? history : null
  const messages = currentHistory?.messages || []
  const generating = currentHistory?.session.generating || false
  const busy = sending || creating || generating

  useEffect(() => {
    onBusy(busy)
    return () => onBusy(false)
  }, [busy, onBusy])

  useEffect(() => {
    if (!generating) return
    const timer = setTimeout(() => setRevision(value => value + 1), 2000)
    return () => clearTimeout(timer)
  }, [generating, revision, history])

  useEffect(() => {
    if (messageList.current) {
      messageList.current.scrollTop = messageList.current.scrollHeight
    }
  }, [messages.length, sending])

  async function reload(id: string) {
    const [items, nextHistory] = await Promise.all([
      conversationApi.list(agent.id),
      conversationApi.history(agent.id, id),
    ])
    if (!mounted.current) return
    setSessions(items)
    if (active.current === id) setHistory(nextHistory)
  }

  async function newSession() {
    if (busy || disabled || loading) return
    setCreating(true)
    setError('')
    try {
      const session = await conversationApi.create(agent.id)
      if (!mounted.current) return
      setSessions(items => [session, ...items].slice(0, 100))
      active.current = session.id
      setActiveId(session.id)
      setDraft('')
    } catch (e) {
      if (mounted.current) setError(errorMessage(e))
    } finally {
      if (mounted.current) setCreating(false)
    }
  }

  async function send(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy || disabled || historyLoading || !agent.modelId || !draft.trim()) return
    const question = draft
    const requestId = crypto.randomUUID()
    pendingRequest.current = requestId
    setSending(true)
    setError('')
    let id = activeId
    try {
      if (!id) {
        const session = await conversationApi.create(agent.id)
        if (!mounted.current) return
        id = session.id
        active.current = id
        setSessions(items => [session, ...items].slice(0, 100))
        setActiveId(id)
      }
      const now = new Date().toISOString()
      setHistory(previous => ({
        session: {
          ...(previous?.session.id === id
            ? previous.session
            : { id, agentId: agent.id, title: question.slice(0, 40), createdAt: now, updatedAt: now }),
          generating: true,
        },
        messages: [
          ...(previous?.session.id === id ? previous.messages : []),
          { id: requestId + ':user', requestId, role: 'user', content: question, status: 'completed', error: null, createdAt: now, answer: null },
          { id: requestId + ':assistant', requestId, role: 'assistant', content: '', status: 'pending', error: null, createdAt: now, answer: null },
        ],
      }))
      await conversationApi.send(agent.id, id, requestId, question)
      if (!mounted.current) return
      setDraft('')
      await reload(id)
    } catch (e) {
      if (!mounted.current) return
      setError(errorMessage(e))
      if (id) {
        try { await reload(id) } catch { /* Keep the original failure visible. */ }
      }
    } finally {
      pendingRequest.current = null
      if (mounted.current) setSending(false)
    }
  }

  return <section className="chat-workspace" aria-label="会话工作台">
    <aside className="conversation-sidebar">
      <div className="conversation-heading"><h3>会话记录</h3><span>{sessions.length}</span></div>
      <button className="button secondary new-session" disabled={busy || disabled || loading} onClick={newSession}>
        {creating ? '创建中…' : '＋ 新建会话'}
      </button>
      <div className="session-list">
        {loading && <p className="helper">正在读取会话…</p>}
        {!loading && sessions.length === 0 && <p className="helper">还没有会话。发送问题即可开始。</p>}
        {sessions.map(session => <button
          key={session.id}
          aria-pressed={activeId === session.id}
          className={`session-item ${activeId === session.id ? 'selected' : ''}`}
          disabled={busy}
          onClick={() => {
            setError('')
            setActiveId(session.id)
            setDraft('')
          }}
        >
          <strong>{session.title}</strong>
          <small>{new Date(session.updatedAt).toLocaleDateString('zh-CN')}</small>
        </button>)}
      </div>
      <p className="session-note">会话独立保存，新会话不携带其他会话的上下文。</p>
    </aside>
    <div className="chat-main">
      <div className="chat-heading">
        <div><h3>{currentHistory?.session.title || '开始一段对话'}</h3><small>与 {agent.name} 对话</small></div>
        <button className="button secondary" disabled={!activeId || sending} onClick={() => {
          setError('')
          setRevision(value => value + 1)
        }}>刷新记录</button>
      </div>
      <div ref={messageList} className="message-list" aria-label="对话消息" aria-live="polite">
        {historyLoading && messages.length === 0 && <p className="helper">正在加载消息…</p>}
        {!historyLoading && messages.length === 0 && <div className="chat-empty"><span>◈</span><h3>你好，有什么可以帮你？</h3><p>输入问题开始对话，消息会自动保存。</p></div>}
        {messages.map(message => <article key={message.id} className={`chat-message ${message.role}`}>
          <div className="message-avatar">{message.role === 'user' ? '我' : '桥'}</div>
          <div className="message-body">
            <div className="message-label">{message.role === 'user' ? '你' : agent.name}</div>
            {message.status === 'pending' ? <div className="message-bubble pending">正在生成回答…</div>
              : message.status === 'failed' ? <div className="message-bubble failed">{message.error || '生成失败，请重新发送。'}</div>
              : <div className="message-bubble">{message.content}</div>}
            {message.role === 'assistant' && <ExecutionTrace agentId={agent.id} requestId={message.requestId} pending={message.status === 'pending'} />}<div className="message-meta">
              {new Date(message.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}
              {message.answer && <> · {message.answer.model} · {(message.answer.durationMillis / 1000).toFixed(1)} 秒 · Token {message.answer.usage.totalTokens ?? '未知'}</>}
            </div>
          </div>
        </article>)}

      </div>
      {error && <div className="error chat-error" role="alert">{error}</div>}
      <form className="chat-composer" onSubmit={send}>
        <textarea
          aria-label="输入对话消息"
          placeholder={agent.modelId ? '输入消息，Ctrl + Enter 发送…' : '请先绑定调用模型'}
          maxLength={32000}
          rows={3}
          value={draft}
          disabled={busy || disabled || !agent.modelId || loading}
          onChange={event => setDraft(event.target.value)}
          onKeyDown={event => {
            if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !event.nativeEvent.isComposing) {
              event.preventDefault()
              event.currentTarget.form?.requestSubmit()
            }
          }}
        />
        <div className="composer-footer"><small>上下文携带最近 10 轮成功对话，历史内容最多 24,000 字符。</small><button
          className="button primary"
          disabled={busy || disabled || !agent.modelId || !draft.trim() || loading || historyLoading}
        >{sending || generating ? '生成中…' : '发送消息 ↑'}</button></div>
      </form>
    </div>
  </section>
}
