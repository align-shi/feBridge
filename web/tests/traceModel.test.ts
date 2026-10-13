import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildTrace } from '../src/traceModel.ts'
import type { RunEvent } from '../src/api.ts'

const event = (id: number, kind: string, details: Record<string, unknown> = {}): RunEvent => ({
  id: String(id), kind, summary: kind, details,
  createdAt: new Date(1700000000000 + id * 100).toISOString(),
})
test('pairs model and tool nodes, preserves causal parent and counts usage once', () => {
  const trace = buildTrace([
    event(1, 'RUN_STARTED'), event(2, 'CONTEXT_PREPARED', { model: 'glm' }),
    event(3, 'MODEL_REQUEST', { step: 1, model: 'glm' }),
    event(4, 'MODEL_RESPONSE', { step: 1, durationMillis: 80, usage: { promptTokens: 10, completionTokens: 2, totalTokens: 12 } }),
    event(5, 'TOOL_REQUEST', { step: 1, callId: 'a', arguments: { mouldCode: 'M001' } }),
    event(6, 'TOOL_RESULT', { callId: 'a', durationMillis: 25, result: { found: true } }),
    event(7, 'MODEL_REQUEST', { step: 2 }),
    event(8, 'MODEL_RESPONSE', { step: 2, usage: { promptTokens: 20, completionTokens: 3, totalTokens: 23 } }),
    event(9, 'RUN_COMPLETED', { durationMillis: 800, usage: { totalTokens: 35 } }),
  ])
  assert.equal(trace.nodes.length, 5)
  assert.equal(trace.toolCalls, 1)
  assert.equal(trace.modelCalls, 2)
  assert.deepEqual(trace.usage, { input: 30, output: 5, total: 35 })
  const tool = trace.nodes.find(node => node.type === 'tool')!
  assert.equal(tool.parentId, '3')
  assert.equal(tool.duration, 25)
  assert.deepEqual(tool.eventIds, ['5', '6'])
  assert.deepEqual(tool.input, { mouldCode: 'M001' })
  assert.deepEqual(tool.output, { found: true })
  assert.equal(trace.duration, 800)
})
test('failed run retains earlier token usage and fails the outstanding model', () => {
  const trace = buildTrace([
    event(1, 'RUN_STARTED'), event(2, 'MODEL_REQUEST', { step: 1 }),
    event(3, 'MODEL_RESPONSE', { step: 1, usage: { promptTokens: 100, completionTokens: 0, totalTokens: 100 } }),
    event(4, 'MODEL_REQUEST', { step: 2 }), event(5, 'RUN_FAILED'),
  ])
  assert.equal(trace.status, 'failed')
  assert.equal(trace.nodes.at(-1)?.status, 'failed')
  assert.equal(trace.usage.total, 100)
  assert.equal(trace.usage.output, 0)
  assert.equal(trace.partialUsage, true)
})
test('missing provider usage remains unknown; legacy events still pair', () => {
  const trace = buildTrace([
    event(1, 'RUN_STARTED'), event(2, 'MODEL_REQUEST'),
    event(3, 'MODEL_RESPONSE', { content: 'ok' }), event(4, 'RUN_COMPLETED'),
  ])
  assert.equal(trace.usage.total, null)
  assert.equal(trace.partialUsage, true)
  assert.equal(trace.nodes[1].status, 'completed')
  assert.deepEqual(trace.nodes[1].eventIds, ['2', '3'])
})
test('parallel tool identifiers pair independently, including a recoverable tool failure', () => {
  const trace = buildTrace([
    event(1, 'RUN_STARTED'), event(2, 'MODEL_REQUEST', { step: 1 }),
    event(3, 'MODEL_RESPONSE', { step: 1 }),
    event(4, 'TOOL_REQUEST', { step: 1, callId: 'a' }),
    event(5, 'TOOL_REQUEST', { step: 1, callId: 'b' }),
    event(6, 'TOOL_RESULT', { callId: 'b', result: 'b', error: true }),
    event(7, 'TOOL_RESULT', { callId: 'a', result: 'a' }),
    event(8, 'RUN_COMPLETED'),
  ])
  assert.equal(trace.status, 'completed')
  assert.equal(trace.nodes.find(node => node.id === '4')?.output, 'a')
  assert.equal(trace.nodes.find(node => node.id === '5')?.status, 'failed')
})
test('pending request has no invented usage and keeps a stable node identifier', () => {
  const trace = buildTrace([event(1, 'RUN_STARTED'), event(2, 'MODEL_REQUEST')], 1700000001000)
  assert.equal(trace.status, 'running')
  assert.equal(trace.nodes[1].end, null)
  assert.equal(trace.nodes[1].id, '2')
  assert.equal(trace.duration, 900)
})

test('ended conversation with no terminal event is interrupted, not running forever', () => {
  const trace = buildTrace([event(1, 'RUN_STARTED'), event(2, 'MODEL_REQUEST')], 1700000010000, false)
  assert.equal(trace.status, 'interrupted')
  assert.equal(trace.duration, 100)
  assert.equal(trace.nodes[1].status, 'interrupted')
})
