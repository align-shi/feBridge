export interface Agent {
  id: string
  name: string
  description: string
  systemPrompt: string
  modelConfig: Record<string, unknown>
  modelId: string | null
  createdAt: string
}
export type CreateAgent = Pick<Agent, 'name' | 'description' | 'systemPrompt' | 'modelConfig'> & { modelId?: string }

export class HttpError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}
let csrf: { token: string; headerName: string } | null = null
let csrfLoading: Promise<{ token: string; headerName: string }> | null = null
export function clearCsrf() {
  csrf = null
  csrfLoading = null
}
async function csrfToken() {
  if (csrf) return csrf
  if (!csrfLoading) {
    csrfLoading = fetch('/api/auth/csrf', { credentials: 'same-origin', signal: AbortSignal.timeout(10000) })
      .then(async response => {
        if (!response.ok) throw new HttpError(response.status, '登录验证信息读取失败，请刷新页面。')
        return response.json() as Promise<{ token: string; headerName: string }>
      })
      .then(value => { csrf = value; return value })
      .finally(() => { csrfLoading = null })
  }
  return csrfLoading
}

async function request<T>(path: string, options: RequestInit = {}, timeoutMs=15000): Promise<T> {
  const signal = options.signal ? AbortSignal.any([options.signal, AbortSignal.timeout(timeoutMs)]) : AbortSignal.timeout(timeoutMs)
  const headers = new Headers(options.headers)
  headers.set('Content-Type', 'application/json')
  if (!['GET', 'HEAD', 'OPTIONS'].includes(options.method ?? 'GET')) {
    const token = await csrfToken()
    headers.set(token.headerName, token.token)
  }
  const response = await fetch(path, { ...options, credentials: 'same-origin', signal, headers })
  if (!response.ok) {
    let message = `请求失败（HTTP ${response.status}）`
    try {
      const body = await response.json()
      if (typeof body.detail === 'string') message += `：${body.detail}`
      else if (typeof body.message === 'string') message += `：${body.message}`
    } catch { /* Proxy errors may not contain JSON. */ }
    let expired = response.status === 401
    if (response.status === 403 && path !== '/api/auth/login') {
      // Session expiry can surface as a CSRF rejection. Check login state without retrying a mutation.
      try {
        const current = await fetch('/api/auth/me', { credentials: 'same-origin', signal: AbortSignal.timeout(5000) })
        expired = current.status === 401
      } catch { /* Preserve the original error when the backend cannot be reached. */ }
    }
    if (expired && path !== '/api/auth/me' && path !== '/api/auth/login') {
      clearCsrf()
      window.dispatchEvent(new Event('febridge:auth-expired'))
    }
    throw new HttpError(response.status, message)
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>
}
export const agentApi = {
  bind: (id:string,modelId:string,parameters:Record<string,unknown>) => request<Agent>(`/api/agents/${encodeURIComponent(id)}/model`,{method:"PUT",body:JSON.stringify({modelId,parameters})}),
  chat: (id:string,message:string) => request<ChatAnswer>(`/api/agents/${encodeURIComponent(id)}/chat`,{method:"POST",body:JSON.stringify({message})},210000),
  list: (signal?: AbortSignal) => request<Agent[]>('/api/agents', { signal }),
  get: (id: string, signal?: AbortSignal) => request<Agent>(`/api/agents/${encodeURIComponent(id)}`, { signal }),
  create: (input: CreateAgent) => request<Agent>('/api/agents', { method: 'POST', body: JSON.stringify(input) }),
}
export function errorMessage(error: unknown): string {
  if (error instanceof Error && error.name === 'TimeoutError') return '请求超时，请检查后端是否正常运行。'
  if (error instanceof TypeError) return '无法连接后端，请确认 Java 服务已启动并检查代理地址。'
  return error instanceof Error ? error.message : '请求失败，请稍后重试。'
}
export type ProviderType='DEEPSEEK'|'ZHIPU'
export interface Provider { id:string;name:string;providerType:ProviderType;baseUrl:string;keyConfigured:boolean;enabled:boolean;createdAt:string }
export interface ProviderInput {name:string;providerType:ProviderType;apiKey:string;enabled:boolean}
export interface ModelDefinition {id:string;providerId:string;modelCode:string;displayName:string;supportsTools:boolean;defaultParameters:Record<string,unknown>;enabled:boolean;createdAt:string}
export type ModelInput=Omit<ModelDefinition,'id'|'createdAt'>
export interface Preset {providerType:ProviderType;baseUrl:string;suggestedModel:string}
export interface ChatAnswer {content:string;model:string;provider:string;usage:{promptTokens:number|null;completionTokens:number|null;totalTokens:number|null};durationMillis:number}
export const modelApi={
  testTools:(id:string)=>request<{passed:boolean;message:string;durationMillis:number}>('/api/models/'+id+'/test-tool-calling',{method:'POST'},75000),
  providers:(signal?:AbortSignal)=>request<Provider[]>('/api/model-providers',{signal}),
  models:(signal?:AbortSignal)=>request<ModelDefinition[]>('/api/models',{signal}),
  presets:(signal?:AbortSignal)=>request<Preset[]>('/api/model-providers/presets',{signal}),
  createProvider:(input:ProviderInput)=>request<Provider>('/api/model-providers',{method:'POST',body:JSON.stringify(input)}),
  updateProvider:(id:string,input:ProviderInput)=>request<Provider>(`/api/model-providers/${id}`,{method:'PUT',body:JSON.stringify(input)}),
  createModel:(input:ModelInput)=>request<ModelDefinition>('/api/models',{method:'POST',body:JSON.stringify(input)}),
  updateModel:(id:string,input:ModelInput)=>request<ModelDefinition>(`/api/models/${id}`,{method:'PUT',body:JSON.stringify(input)}),
}


export interface Conversation {
  id: string
  agentId: string
  title: string
  createdAt: string
  updatedAt: string
  generating: boolean
}
export interface ConversationMessage {
  id: string
  requestId: string
  role: 'user' | 'assistant'
  content: string
  status: 'pending' | 'completed' | 'failed'
  error: string | null
  createdAt: string
  answer: ChatAnswer | null
}
export interface ConversationHistory {
  session: Conversation
  messages: ConversationMessage[]
}
export const conversationApi = {
  list: (agentId: string, signal?: AbortSignal) =>
    request<Conversation[]>(`/api/agents/${agentId}/conversations`, { signal }),
  create: (agentId: string) =>
    request<Conversation>(`/api/agents/${agentId}/conversations`, { method: 'POST' }),
  history: (agentId: string, sessionId: string, signal?: AbortSignal) =>
    request<ConversationHistory>(
      `/api/agents/${agentId}/conversations/${sessionId}/messages`, { signal }
    ),
  send: (agentId: string, sessionId: string, requestId: string, message: string) =>
    request<{ requestId: string; answer: ChatAnswer }>(
      `/api/agents/${agentId}/conversations/${sessionId}/messages`,
      { method: 'POST', body: JSON.stringify({ requestId, message }) },
      210000
    ),
}


export interface McpServer { id:string; name:string; endpoint:string; tokenConfigured:boolean; enabled:boolean; timeoutSeconds:number; resultLimit:number; authType: 'NONE'|'BEARER'|'CUSTOM_HEADER'; authHeader:string|null }
export type McpServerInput = Omit<McpServer,'id'|'tokenConfigured'> & {token:string}
export interface McpTool { id:string; serverId:string; serverName:string; name:string; description:string; inputSchema:Record<string,unknown>; readOnly:boolean; enabled:boolean }
export interface ToolAuditRecord { id:string; toolName:string; status:string; durationMillis:number; errorCode:string|null }
export const toolApi = {
  servers:()=>request<McpServer[]>('/api/mcp/servers'),
  tools:()=>request<McpTool[]>('/api/mcp/tools'),
  save:(id:string|null,input:McpServerInput)=>request<McpServer>(id ? '/api/mcp/servers/'+id : '/api/mcp/servers',{method:id?'PUT':'POST',body:JSON.stringify(input)}),
  discover:(id:string)=>request<McpTool[]>('/api/mcp/servers/'+id+'/discover',{method:'POST'},40000),
  policy:(id:string,enabled:boolean)=>request<McpTool>('/api/mcp/tools/'+id+'/policy',{method:'PUT',body:JSON.stringify({enabled})}),
  boundServers:(id:string)=>request<McpServer[]>('/api/agents/'+id+'/mcp-servers'),
  bindServers:(id:string,serverIds:string[])=>request<McpServer[]>('/api/agents/'+id+'/mcp-servers',{method:'PUT',body:JSON.stringify({serverIds})}),
  audits:(id:string,requestId:string)=>request<ToolAuditRecord[]>('/api/agents/'+id+'/tool-audits?requestId='+requestId),
}

export interface SkillInput { name:string; description:string; instructions:string; enabled:boolean }
export interface Skill extends SkillInput { id:string }
export interface ExecutionRules { maxModelCalls:number; maxToolCalls:number; taskTimeoutSeconds:number; toolTimeoutSeconds:number; toolsEnabled:boolean; failurePolicy:'CONTINUE'|'STOP' }
export const configApi = {
  profile:(id:string,input:Pick<Agent,'name'|'description'|'systemPrompt'>)=>request<Agent>('/api/agents/'+id+'/profile',{method:'PUT',body:JSON.stringify(input)}),
  skills:()=>request<Skill[]>('/api/skills'),
  saveSkill:(id:string|null,input:SkillInput)=>request<Skill>(id?'/api/skills/'+id:'/api/skills',{method:id?'PUT':'POST',body:JSON.stringify(input)}),
  boundSkills:(id:string)=>request<Skill[]>('/api/agents/'+id+'/skills'),
  bindSkills:(id:string,skillIds:string[])=>request<Skill[]>('/api/agents/'+id+'/skills',{method:'PUT',body:JSON.stringify({skillIds})}),
  rules:(id:string)=>request<ExecutionRules>('/api/agents/'+id+'/execution-rules'),
  saveRules:(id:string,input:ExecutionRules)=>request<ExecutionRules>('/api/agents/'+id+'/execution-rules',{method:'PUT',body:JSON.stringify(input)}),
}

export interface RunEvent { id:string; kind:string; summary:string; details:Record<string,unknown>; createdAt:string }
export const runApi = { events:(agentId:string,requestId:string,signal?:AbortSignal)=>request<RunEvent[]>('/api/agents/'+agentId+'/runs/'+requestId+'/events',{signal}) }

export type UserRole = 'ADMIN' | 'USER'
export interface PlatformUser {
  id: string
  username: string
  displayName: string
  role: UserRole
  enabled: boolean
  createdAt: string
}
export interface UserInput { username: string; displayName: string; password: string; role: UserRole }
export const authApi = {
  me: (signal?: AbortSignal) => request<PlatformUser>('/api/auth/me', { signal }),
  login: async (username: string, password: string) => {
    const user = await request<PlatformUser>('/api/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) })
    clearCsrf()
    return user
  },
  logout: async () => {
    await request('/api/auth/logout', { method: 'POST' })
    clearCsrf()
  },
  password: async (currentPassword: string, newPassword: string) => {
    await request('/api/auth/password', { method: 'POST', body: JSON.stringify({ currentPassword, newPassword }) })
    clearCsrf()
  },
}
export const userApi = {
  list: (signal?: AbortSignal) => request<PlatformUser[]>('/api/users', { signal }),
  create: (input: UserInput) => request<PlatformUser>('/api/users', { method: 'POST', body: JSON.stringify(input) }),
  update: (id: string, input: Pick<PlatformUser, 'displayName' | 'role' | 'enabled'>) =>
    request<PlatformUser>('/api/users/' + id, { method: 'PUT', body: JSON.stringify(input) }),
  reset: (id: string, password: string) =>
    request('/api/users/' + id + '/password', { method: 'POST', body: JSON.stringify({ password }) }),
}
