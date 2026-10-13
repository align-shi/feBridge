import { useEffect, useRef, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import { modelApi, errorMessage } from './api'
import type { Provider, ProviderInput, ModelDefinition, ModelInput, Preset } from './api'
import { useErrorState } from './ErrorNotice'

const providerLabel = { DEEPSEEK: 'DeepSeek', ZHIPU: '智谱' }

function providerFromUrl() {
  return window.location.hash.match(/^#\/models\/([0-9a-f-]{36})$/i)?.[1] || ''
}

function EditorDialog({ title, busy, onClose, children }: {
  title: string; busy: boolean; onClose: () => void; children: ReactNode
}) {
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const element = ref.current
    element?.showModal()
    return () => element?.close()
  }, [])
  return <dialog ref={ref} className="registry-dialog" aria-label={title}
    onCancel={event => { if (busy) event.preventDefault() }}
    onClose={event => { if (!event.currentTarget.open) onClose() }}>
    <header><h2>{title}</h2><button type="button" className="button secondary" disabled={busy} onClick={onClose} aria-label={'关闭' + title}>✕</button></header>
    {children}
  </dialog>
}

export default function ModelManager() {
  const [providers, setProviders] = useState<Provider[]>([])
  const [models, setModels] = useState<ModelDefinition[]>([])
  const [presets, setPresets] = useState<Preset[]>([])
  const [providerId, setProviderId] = useState(providerFromUrl)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useErrorState()
  const [notice, setNotice] = useState('')
  const [providerEditor, setProviderEditor] = useState<Provider | 'new' | null>(null)
  const [modelEditor, setModelEditor] = useState<ModelDefinition | 'new' | null>(null)
  const [testingId, setTestingId] = useState('')
  const [applyingId, setApplyingId] = useState('')
  const [results, setResults] = useState<Record<string, { passed: boolean; message: string; durationMillis: number }>>({})
  const [revision, setRevision] = useState(0)
  const mounted = useRef(true)
  const busy = !!testingId || !!applyingId

  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false }
  }, [])
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    Promise.all([modelApi.providers(controller.signal), modelApi.models(controller.signal), modelApi.presets(controller.signal)])
      .then(([p, m, s]) => {
        if (controller.signal.aborted) return
        setProviders(p); setModels(m); setPresets(s)
      }).catch(e => { if (!controller.signal.aborted) setError(errorMessage(e)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [revision, setError])
  useEffect(() => {
    function sync() {
      setProviderId(providerFromUrl())
      setProviderEditor(null); setModelEditor(null); setNotice('')
    }
    window.addEventListener('popstate', sync)
    window.addEventListener('hashchange', sync)
    return () => {
      window.removeEventListener('popstate', sync)
      window.removeEventListener('hashchange', sync)
    }
  }, [])

  function openProvider(id: string) {
    const hash = id ? '#/models/' + id : '#/models'
    if (window.location.hash !== hash) window.history.pushState(null, '', hash)
    setProviderId(id); setError(''); setNotice(''); setModelEditor(null)
  }
  async function testModel(model: ModelDefinition) {
    if (busy) return
    setTestingId(model.id); setError('')
    setResults(old => { const next = { ...old }; delete next[model.id]; return next })
    try {
      const result = await modelApi.testTools(model.id)
      if (mounted.current) setResults(old => ({ ...old, [model.id]: result }))
    } catch (e) { if (mounted.current) setError(errorMessage(e)) }
    finally { if (mounted.current) setTestingId('') }
  }
  async function applySupport(model: ModelDefinition) {
    setApplyingId(model.id); setError('')
    try {
      const latest = (await modelApi.models()).find(item => item.id === model.id)
      if (!latest) throw new Error('模型不存在，请刷新列表。')
      const input: ModelInput = {
        providerId: latest.providerId, modelCode: latest.modelCode, displayName: latest.displayName,
        defaultParameters: latest.defaultParameters, enabled: latest.enabled, supportsTools: true,
      }
      const saved = await modelApi.updateModel(model.id, input)
      if (mounted.current) {
        setModels(old => old.map(item => item.id === saved.id ? saved : item))
        setNotice('已保存“支持工具调用”标记。')
      }
    } catch (e) { if (mounted.current) setError(errorMessage(e)) }
    finally { if (mounted.current) setApplyingId('') }
  }

  const provider = providers.find(item => item.id === providerId)
  const providerModels = models.filter(model => model.providerId === providerId)
  return <section>
    {providerId && <button className="button secondary registry-back" disabled={busy} onClick={() => openProvider('')}>← 返回供应商列表</button>}
    <div className="page-heading">
      <div><div className="eyebrow">MODEL REGISTRY</div><h1>{provider ? provider.name : providerId ? '供应商详情' : '模型供应商'}</h1>
        <p>{provider ? '管理该供应商的模型定义与工具调用能力。' : '选择供应商，进入详情管理模型和凭证。'}</p></div>
      <div className="registry-actions">
        <button className="button secondary" disabled={loading || busy} onClick={() => { setError(''); setRevision(v => v + 1) }}>刷新</button>
        {!providerId && <button className="button primary" onClick={() => setProviderEditor('new')}>＋ 新增供应商</button>}
      </div>
    </div>
    {error && <div className="error" role="alert">{error}</div>}
    {notice && <div className="notice" role="status">{notice}</div>}
    {!providerId ? <>
      {loading && !providers.length && <p className="helper">正在加载供应商…</p>}
      {!loading && !providers.length && !error && <div className="empty"><h2>添加第一个模型供应商</h2><p>配置 DeepSeek 或智谱的 API Key，再添加模型定义。</p></div>}
      <div className="provider-grid">{providers.map(item => <button className="provider-card" key={item.id} onClick={() => openProvider(item.id)}>
        <div className="provider-card-top"><span className="provider-avatar">{providerLabel[item.providerType].slice(0, 1)}</span><span className={'provider-state ' + (item.enabled ? 'enabled' : '')}>{item.enabled ? '已启用' : '已停用'}</span></div>
        <h2>{item.name}</h2><p>{providerLabel[item.providerType]}</p>
        <div className="provider-card-footer"><span>{models.filter(model => model.providerId === item.id).length} 个模型 · {item.keyConfigured ? '凭证已配置' : '未配置凭证'}</span><span>管理模型 ↗</span></div>
      </button>)}</div>
    </> : provider ? <>
      <div className="panel provider-overview">
        <div><span className="provider-state">{providerLabel[provider.providerType]} · {provider.enabled ? '已启用' : '已停用'}</span><p>{provider.baseUrl}</p><small>{provider.keyConfigured ? 'API Key 已配置并加密保存' : '尚未配置 API Key'}</small></div>
        <button className="button secondary" disabled={busy} onClick={() => setProviderEditor(provider)}>编辑供应商 / 凭证</button>
      </div>
      <div className="provider-model-heading"><div><h2>模型定义 <span>{providerModels.length}</span></h2><p>直接编辑已有模型，或新增属于此供应商的模型。</p></div>
        <button className="button primary" disabled={busy || loading} onClick={() => setModelEditor('new')}>＋ 新增模型</button>
      </div>
      <p className="helper">测试工具调用会使用已保存的模型配置，最多发起两次真实请求，消耗少量 Token；不会调用业务 MCP。</p>
      {!providerModels.length && <div className="empty"><h2>尚未添加模型</h2><p>点击“新增模型”填写厂商模型标识。</p></div>}
      <div className="provider-model-list">{providerModels.map(model => <article className="panel provider-model-row" key={model.id}>
        <div className="provider-model-summary">
          <div><h3>{model.displayName} <span className="tag">{model.enabled ? '已启用' : '已停用'}</span></h3><p>{model.modelCode}</p><small>{model.supportsTools ? '已标记支持工具调用' : '尚未标记支持工具调用'}</small></div>
          <div className="registry-actions">
            <button className="button secondary" disabled={busy} aria-label={'编辑模型 ' + model.displayName} onClick={() => setModelEditor(model)}>编辑</button>
            <button className="button secondary" disabled={busy || !model.enabled || !provider.enabled}
              title={!provider.enabled ? '请先启用供应商' : !model.enabled ? '请先启用模型' : '测试已保存的模型配置'}
              aria-label={'测试工具调用 ' + model.displayName} aria-busy={testingId === model.id} onClick={() => void testModel(model)}>
              {testingId === model.id ? '测试中…' : '测试工具调用'}
            </button>
          </div>
        </div>
        {results[model.id] && <div className={'model-probe-result ' + (results[model.id].passed ? 'notice' : 'error')} role="status">
          <span>{results[model.id].passed ? '测试通过：' : '未通过：'}{results[model.id].message} · {(results[model.id].durationMillis / 1000).toFixed(1)} 秒</span>
          {results[model.id].passed && !model.supportsTools && <button className="button secondary" disabled={busy} onClick={() => void applySupport(model)}>保存支持标记</button>}
        </div>}
      </article>)}</div>
    </> : <p className="panel" role="status">{loading ? '正在加载供应商…' : '未找到该供应商，请返回列表。'}</p>}
    {providerEditor && <ProviderEditor key={typeof providerEditor === 'string' ? 'new' : providerEditor.id}
      provider={providerEditor === 'new' ? null : providerEditor} presets={presets}
      onClose={() => setProviderEditor(null)} onSaved={saved => {
        setProviders(old => old.some(item => item.id === saved.id) ? old.map(item => item.id === saved.id ? saved : item) : [...old, saved])
        setProviderEditor(null); openProvider(saved.id); setNotice('供应商已保存。')
      }} />}
    {modelEditor && provider && <ModelEditor key={typeof modelEditor === 'string' ? 'new' : modelEditor.id}
      provider={provider} model={modelEditor === 'new' ? null : modelEditor} preset={presets.find(item => item.providerType === provider.providerType)}
      onClose={() => setModelEditor(null)} onSaved={saved => {
        setModels(old => old.some(item => item.id === saved.id) ? old.map(item => item.id === saved.id ? saved : item) : [...old, saved])
        setResults(old => { const next = { ...old }; delete next[saved.id]; return next })
        setModelEditor(null); setNotice('模型已保存，可在列表中直接测试工具调用。')
      }} />}
  </section>
}

function ProviderEditor({ provider, presets, onClose, onSaved }: {
  provider: Provider | null; presets: Preset[]; onClose: () => void; onSaved: (provider: Provider) => void
}) {
  const [draft, setDraft] = useState<ProviderInput>({ name: provider?.name || '', providerType: provider?.providerType || 'DEEPSEEK', apiKey: '', enabled: provider?.enabled ?? true })
  const [saving, setSaving] = useState(false)
  const [error, setError] = useErrorState()
  async function save(event: FormEvent) {
    event.preventDefault()
    if (saving) return
    setSaving(true); setError('')
    try {
      const input = { ...draft, name: draft.name.trim() }
      onSaved(provider ? await modelApi.updateProvider(provider.id, input) : await modelApi.createProvider(input))
    } catch (e) { setError(errorMessage(e)) }
    finally { setSaving(false) }
  }
  return <EditorDialog title={provider ? '编辑供应商' : '新增供应商'} busy={saving} onClose={onClose}>
    <form className="create-form registry-editor-form" onSubmit={save}><fieldset disabled={saving}>
      <label>供应商名称<input autoFocus required maxLength={100} value={draft.name} onChange={e => setDraft({ ...draft, name: e.target.value })} /></label>
      <label>厂商<select disabled={!!provider} value={draft.providerType} onChange={e => setDraft({ ...draft, providerType: e.target.value as ProviderInput['providerType'] })}><option value="DEEPSEEK">DeepSeek</option><option value="ZHIPU">智谱</option></select></label>
      <p className="helper">{provider?.baseUrl || presets.find(item => item.providerType === draft.providerType)?.baseUrl}</p>
      <label>API Key<input type="password" required={!provider} maxLength={4096} autoComplete="new-password" value={draft.apiKey} placeholder={provider ? '留空保留已有密钥' : '请输入 API Key'} onChange={e => setDraft({ ...draft, apiKey: e.target.value })} /></label>
      <label className="checkbox"><input type="checkbox" checked={draft.enabled} onChange={e => setDraft({ ...draft, enabled: e.target.checked })} />启用供应商</label>
      {error && <div className="error" role="alert">{error}</div>}
      <div className="form-actions"><button type="button" className="button secondary" onClick={onClose}>取消</button><button className="button primary">{saving ? '保存中…' : '保存供应商'}</button></div>
    </fieldset></form>
  </EditorDialog>
}

function ModelEditor({ provider, model, preset, onClose, onSaved }: {
  provider: Provider; model: ModelDefinition | null; preset?: Preset; onClose: () => void; onSaved: (model: ModelDefinition) => void
}) {
  const [draft, setDraft] = useState<ModelInput>({
    providerId: provider.id, modelCode: model?.modelCode || '',
    displayName: model?.displayName || '', supportsTools: model?.supportsTools ?? true,
    defaultParameters: model?.defaultParameters || {}, enabled: model?.enabled ?? true,
  })
  const [parameters, setParameters] = useState(JSON.stringify(draft.defaultParameters, null, 2))
  const [saving, setSaving] = useState(false)
  const [error, setError] = useErrorState()
  async function save(event: FormEvent) {
    event.preventDefault()
    if (saving) return
    setError('')
    let value: Record<string, unknown>
    try {
      const parsed: unknown = JSON.parse(parameters)
      if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw new Error()
      value = parsed as Record<string, unknown>
    } catch { setError('默认参数必须为 JSON 对象。'); return }
    setSaving(true)
    try {
      const input = { ...draft, providerId: provider.id, modelCode: draft.modelCode.trim(), displayName: draft.displayName.trim(), defaultParameters: value }
      onSaved(model ? await modelApi.updateModel(model.id, input) : await modelApi.createModel(input))
    } catch (e) { setError(errorMessage(e)) }
    finally { setSaving(false) }
  }
  return <EditorDialog title={model ? '编辑模型：' + model.displayName : '新增模型定义'} busy={saving} onClose={onClose}>
    <form className="create-form registry-editor-form" onSubmit={save}><fieldset disabled={saving}>
      <p className="helper">所属供应商：{provider.name} · {providerLabel[provider.providerType]}</p>
      <label>模型标识<input autoFocus required maxLength={100} placeholder={preset?.suggestedModel || '厂商 API 的 model 标识'} value={draft.modelCode} onChange={e => setDraft({ ...draft, modelCode: e.target.value })} /></label>
      <label>显示名称<input required maxLength={100} value={draft.displayName} onChange={e => setDraft({ ...draft, displayName: e.target.value })} /></label>
      <label>默认参数（JSON）<textarea className="code" rows={5} value={parameters} onChange={e => setParameters(e.target.value)} spellCheck={false} /></label>
      <label className="checkbox"><input type="checkbox" checked={draft.supportsTools} onChange={e => setDraft({ ...draft, supportsTools: e.target.checked })} />标记支持工具调用</label>
      <label className="checkbox"><input type="checkbox" checked={draft.enabled} onChange={e => setDraft({ ...draft, enabled: e.target.checked })} />启用模型</label>
      {error && <div className="error" role="alert">{error}</div>}
      <p className="helper">保存后，在该模型的列表行点击“测试工具调用”。</p>
      <div className="form-actions"><button type="button" className="button secondary" onClick={onClose}>取消</button><button className="button primary">{saving ? '保存中…' : '保存模型'}</button></div>
    </fieldset></form>
  </EditorDialog>
}
