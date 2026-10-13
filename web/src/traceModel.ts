import type { RunEvent } from './api'

export type NodeStatus = 'running' | 'completed' | 'failed' | 'interrupted'
export interface TokenUsage {
  input: number | null
  output: number | null
  total: number | null
}
export interface TraceNode {
  id: string
  eventIds: string[]
  parentId: string | null
  type: 'agent' | 'context' | 'model' | 'tool' | 'event'
  name: string
  action: string
  status: NodeStatus
  start: number
  end: number | null
  duration: number | null
  usage: TokenUsage
  input: unknown
  output: unknown
  metadata: Record<string, unknown>
}
export interface TraceModel {
  nodes: TraceNode[]
  status: NodeStatus
  duration: number | null
  usage: TokenUsage
  partialUsage: boolean
  modelCalls: number
  toolCalls: number
  latest: string
}
const emptyUsage = (): TokenUsage => ({ input: null, output: null, total: null })
const number = (value: unknown): number | null =>
  typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null
const object = (value: unknown): Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown> : {}
function tokens(value: unknown): TokenUsage {
  const data = object(value)
  const input = number(data.promptTokens)
  const output = number(data.completionTokens)
  return { input, output, total: number(data.totalTokens) ?? (input !== null && output !== null ? input + output : null) }
}
function time(event: RunEvent) {
  const value = Date.parse(event.createdAt)
  return Number.isFinite(value) ? value : 0
}
function sum(values: (number | null)[]) {
  const reported = values.filter((value): value is number => value !== null)
  return reported.length ? reported.reduce((a, b) => a + b, 0) : null
}

// Pair persisted request/response events into stable nodes. Older events without step metadata
// remain readable; tool nodes attach to the model that requested them, not to its time interval.
export function buildTrace(events: RunEvent[], now = Date.now(), active = true): TraceModel {
  const nodes: TraceNode[] = []
  const first = events[0]
  if (!first) return {
    nodes, status: 'running', duration: null, usage: emptyUsage(),
    partialUsage: false, modelCalls: 0, toolCalls: 0, latest: '',
  }
  const root: TraceNode = {
    id: 'run', eventIds: events.map(event => event.id), parentId: null, type: 'agent', name: '智能体执行',
    action: '组装上下文 → 请求模型 → 执行工具 → 生成回答',
    status: 'running', start: time(first), end: null, duration: null,
    usage: emptyUsage(), input: first.details, output: null, metadata: {},
  }
  nodes.push(root)
  let lastModel: TraceNode | undefined
  let modelCalls = 0
  let toolCalls = 0
  const modelByStep = new Map<string, TraceNode>()
  const toolByCall = new Map<string, TraceNode>()
  for (const event of events) {
    const data = event.details
    const timestamp = time(event)
    if (event.kind === 'CONTEXT_PREPARED') {
      const duration = number(data.durationMillis)
      nodes.push({
        id: event.id, eventIds: [event.id], parentId: root.id, type: 'context', name: '准备上下文',
        action: '加载模型配置、组装系统提示词和 Skills、筛选可用工具',
        status: 'completed', start: duration === null ? root.start : timestamp - duration,
        end: timestamp, duration: duration ?? Math.max(0, timestamp - root.start),
        usage: emptyUsage(), input: null, output: data, metadata: data,
      })
      root.metadata = { model: data.model, skills: data.skills, rules: data.rules }
    } else if (event.kind === 'MODEL_REQUEST') {
      modelCalls++
      lastModel = {
        id: event.id, eventIds: [event.id], parentId: root.id, type: 'model',
        name: '模型调用 #' + (data.step ?? modelCalls),
        action: event.summary, status: 'running', start: timestamp, end: null,
        duration: null, usage: emptyUsage(),
        input: data.messages ?? null, output: null, metadata: data,
      }
      nodes.push(lastModel)
      modelByStep.set(String(data.step ?? modelCalls), lastModel)
    } else if (event.kind === 'MODEL_RESPONSE') {
      const node = data.step === undefined ? lastModel : modelByStep.get(String(data.step))
      if (node) {
        node.eventIds.push(event.id)
        node.status = 'completed'
        node.end = timestamp
        node.duration = number(data.durationMillis) ?? Math.max(0, timestamp - node.start)
        node.usage = tokens(data.usage)
        node.action = event.summary
        node.output = { content: data.content, toolCalls: data.calls }
      }
    } else if (event.kind === 'TOOL_REQUEST') {
      toolCalls++
      const parent = data.step === undefined ? lastModel : modelByStep.get(String(data.step))
      const node: TraceNode = {
        id: event.id, eventIds: [event.id], parentId: parent?.id ?? root.id, type: 'tool',
        name: String(data.tool ?? event.summary.replace(/^调用工具：/, '')),
        action: '执行 MCP 工具并将结果回传模型', status: 'running',
        start: timestamp, end: null, duration: null, usage: emptyUsage(),
        input: data.arguments ?? null, output: null, metadata: data,
      }
      nodes.push(node)
      toolByCall.set(String(data.callId ?? event.id), node)
    } else if (event.kind === 'TOOL_RESULT') {
      const node = toolByCall.get(String(data.callId))
      if (node) {
        node.eventIds.push(event.id)
        node.status = data.error === true ? 'failed' : 'completed'
        node.end = timestamp
        node.duration = number(data.durationMillis) ?? Math.max(0, timestamp - node.start)
        node.output = data.result ?? null
        node.action = event.summary
      }
    } else if (event.kind === 'RUN_COMPLETED' || event.kind === 'RUN_FAILED') {
      root.status = event.kind === 'RUN_FAILED' ? 'failed' : 'completed'
      root.end = timestamp
      root.duration = number(data.durationMillis) ?? Math.max(0, timestamp - root.start)
      root.action = event.summary
      root.output = data
      for (const node of nodes) {
        if (node !== root && node.status === 'running') {
          node.status = root.status === 'failed' ? 'failed' : 'interrupted'
          node.end = timestamp
          node.duration = Math.max(0, timestamp - node.start)
          node.action = event.summary
        }
      }
    }
  }
  const models = nodes.filter(node => node.type === 'model')
  const usage = {
    input: sum(models.map(node => node.usage.input)),
    output: sum(models.map(node => node.usage.output)),
    total: sum(models.map(node => node.usage.total)),
  }
  root.usage = usage
  if (root.end === null && !active) {
    root.status = 'interrupted'
    root.action = '会话已结束，但没有完整的结束事件'
    root.end = time(events.at(-1)!)
    root.duration = Math.max(0, root.end - root.start)
    for (const node of nodes) {
      if (node.status === 'running') {
        node.status = 'interrupted'
        node.action = root.action
      }
    }
  }
  if (root.end === null) root.duration = Math.max(0, now - root.start)
  return {
    nodes, status: root.status, duration: root.duration, usage,
    partialUsage: models.some(node => node.usage.total === null || node.usage.input === null || node.usage.output === null),
    modelCalls, toolCalls, latest: events.at(-1)?.summary ?? '',
  }
}
export const durationText = (value: number | null): string =>
  value === null ? '—' : value < 1000 ? Math.round(value) + ' ms' : (value / 1000).toFixed(2) + ' s'
export const tokenText = (value: number | null): string =>
  value === null ? '未返回' : value.toLocaleString('zh-CN')
