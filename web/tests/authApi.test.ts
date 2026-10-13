import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { agentApi, authApi, clearCsrf, HttpError, userApi } from '../src/api.ts'

const events = new EventTarget()
Object.defineProperty(globalThis, 'window', { value: events, configurable: true })
beforeEach(() => clearCsrf())
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), {
  status, headers: { 'Content-Type': 'application/json' },
})

test('mutations send CSRF and cookies, then login refreshes the token', async () => {
  const calls: { path: string; options?: RequestInit }[] = []
  let csrfReads = 0
  globalThis.fetch = async (path, options) => {
    calls.push({ path: String(path), options })
    if (path === '/api/auth/csrf') return json({ token: 'csrf-' + ++csrfReads, headerName: 'X-CSRF-TOKEN' })
    return json({ id: 'user', username: 'demo', role: 'USER' })
  }
  await authApi.login('demo', 'Test-only-password')
  await agentApi.create({ name: 'test', description: '', systemPrompt: 'test', modelConfig: {} })
  assert.equal(csrfReads, 2)
  const login = calls.find(call => call.path === '/api/auth/login')!
  const create = calls.find(call => call.path === '/api/agents')!
  assert.equal(new Headers(login.options?.headers).get('X-CSRF-TOKEN'), 'csrf-1')
  assert.equal(new Headers(create.options?.headers).get('X-CSRF-TOKEN'), 'csrf-2')
  assert.equal(create.options?.credentials, 'same-origin')
})

test('expired session reported as CSRF 403 signs out without replaying the write', async () => {
  const paths: string[] = []
  let expired = 0
  const listener = () => expired++
  events.addEventListener('febridge:auth-expired', listener)
  globalThis.fetch = async path => {
    paths.push(String(path))
    if (path === '/api/auth/csrf') return json({ token: 'csrf', headerName: 'X-CSRF-TOKEN' })
    if (path === '/api/auth/me') return json({ detail: '请先登录' }, 401)
    return json({ detail: '验证过期' }, 403)
  }
  try {
    await assert.rejects(userApi.reset('user-id', 'Test-only-password'), (error: unknown) =>
      error instanceof HttpError && error.status === 403)
    assert.equal(expired, 1)
    assert.equal(paths.filter(path => path === '/api/users/user-id/password').length, 1)
  } finally { events.removeEventListener('febridge:auth-expired', listener) }
})

test('ordinary authorization rejection does not erase a valid login', async () => {
  let expired = 0
  const listener = () => expired++
  events.addEventListener('febridge:auth-expired', listener)
  globalThis.fetch = async path => path === '/api/auth/me'
    ? json({ role: 'USER' }) : json({ detail: '无权操作' }, 403)
  try {
    await assert.rejects(userApi.list(), HttpError)
    assert.equal(expired, 0)
  } finally { events.removeEventListener('febridge:auth-expired', listener) }
})
