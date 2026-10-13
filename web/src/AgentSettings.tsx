import { useErrorState } from './ErrorNotice'
import { useEffect, useState } from 'react'
import { configApi, errorMessage } from './api'
import type { Agent, ExecutionRules, Skill } from './api'

export default function AgentSettings({ agent, disabled, onSaved, onSaving }: {
  agent: Agent; disabled: boolean; onSaved: (agent: Agent) => void; onSaving: (busy: boolean) => void
}) {
  const [name, setName] = useState(agent.name)
  const [description, setDescription] = useState(agent.description)
  const [prompt, setPrompt] = useState(agent.systemPrompt)
  const [skills, setSkills] = useState<Skill[]>([])
  const [ids, setIds] = useState<string[]>([])
  const [rules, setRules] = useState<ExecutionRules | null>(null)
  const [error, setError] = useErrorState()
  const [notice, setNotice] = useState('')
  const [loaded, setLoaded] = useState(false)
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    let live = true
    Promise.all([configApi.skills(), configApi.boundSkills(agent.id), configApi.rules(agent.id)])
      .then(([all, bound, rule]) => {
        if (!live) return
        setSkills(all); setIds(bound.map(s => s.id)); setRules(rule); setLoaded(true)
      }).catch(e => { if (live) setError(errorMessage(e)) })
    return () => { live = false }
  }, [agent.id])
  async function save(work: () => Promise<unknown>, message: string) {
    if (busy || disabled) return
    setBusy(true); onSaving(true); setError(''); setNotice('')
    try { await work(); setNotice(message) }
    catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false); onSaving(false) }
  }
  function move(index: number, offset: number) {
    setIds(old => {
      const next = [...old]
      const target = index + offset
      if (target < 0 || target >= next.length) return old
      ;[next[index], next[target]] = [next[target], next[index]]
      return next
    })
  }
  return <div className="configuration-panels">
    <details className="agent-settings"><summary>提示词与基本信息</summary>
      <form onSubmit={e => {
        e.preventDefault()
        void save(async () => onSaved(await configApi.profile(agent.id, { name, description, systemPrompt: prompt })), '提示词与基本信息已保存。')
      }}><fieldset disabled={disabled || busy}>
        <label>智能体名称<input required maxLength={100} value={name} onChange={e => setName(e.target.value)} /></label>
        <label>智能体描述<textarea rows={2} maxLength={1000} value={description} onChange={e => setDescription(e.target.value)} /></label>
        <label>系统提示词<textarea required rows={8} maxLength={100000} value={prompt} onChange={e => setPrompt(e.target.value)} /></label>
        <button className="button secondary">保存提示词</button>
      </fieldset></form>
    </details>
    <details className="agent-settings"><summary>绑定 Skills · {ids.length} 个</summary>
      <p className="helper">最多绑定 8 个，按下列顺序加入系统提示词。停用的 Skill 不参与运行。</p>
      <fieldset disabled={disabled || busy || !loaded}>
        {skills.map(skill => <label className="tool-choice" key={skill.id}>
          <input type="checkbox" checked={ids.includes(skill.id)}
            disabled={!skill.enabled && !ids.includes(skill.id)}
            onChange={e => setIds(old => e.target.checked ? [...old, skill.id] : old.filter(id => id !== skill.id))} />
          <span>{skill.name}{!skill.enabled && '（已停用）'} · {skill.description}</span>
        </label>)}
        {loaded && !skills.length && <p>请先在 Skills 管理中创建业务指令。</p>}
        <ol className="skill-order">{ids.map((id, index) => <li key={id}>
          <span>{skills.find(s => s.id === id)?.name || id}</span>
          <button type="button" className="button secondary" disabled={index === 0} onClick={() => move(index, -1)} aria-label={'上移 ' + (skills.find(s => s.id === id)?.name || id)}>↑</button>
          <button type="button" className="button secondary" disabled={index === ids.length - 1} onClick={() => move(index, 1)} aria-label={'下移 ' + (skills.find(s => s.id === id)?.name || id)}>↓</button>
        </li>)}</ol>
        <button className="button secondary" disabled={ids.length > 8} onClick={() => void save(() => configApi.bindSkills(agent.id, ids), 'Skills 绑定已保存。')}>保存 Skills 绑定</button>
      </fieldset>
    </details>
    <details className="agent-settings"><summary>执行规则</summary>
      {!rules && <p>{error ? '配置读取失败，请刷新页面重试。' : '读取规则中…'}</p>}
      {rules && <form onSubmit={e => { e.preventDefault(); void save(() => configApi.saveRules(agent.id, rules), '执行规则已保存。') }}>
        <fieldset disabled={disabled || busy || !loaded}>
          <div className="rule-grid">
            <label>最大模型调用次数<input type="number" required min={1} max={10} value={rules.maxModelCalls} onChange={e => setRules({ ...rules, maxModelCalls: Number(e.target.value) })} /></label>
            <label>最大工具调用次数<input type="number" required min={0} max={20} value={rules.maxToolCalls} onChange={e => setRules({ ...rules, maxToolCalls: Number(e.target.value) })} /></label>
            <label>任务总超时（秒）<input type="number" required min={10} max={170} value={rules.taskTimeoutSeconds} onChange={e => setRules({ ...rules, taskTimeoutSeconds: Number(e.target.value) })} /></label>
            <label>单次工具超时（秒）<input type="number" required min={1} max={30} value={rules.toolTimeoutSeconds} onChange={e => setRules({ ...rules, toolTimeoutSeconds: Number(e.target.value) })} /></label>
          </div>
          <label>工具失败时<select value={rules.failurePolicy} onChange={e => setRules({ ...rules, failurePolicy: e.target.value as 'CONTINUE' | 'STOP' })}>
            <option value="CONTINUE">将错误交给模型，允许继续判断</option><option value="STOP">立即终止任务</option>
          </select></label>
          <label className="tool-choice"><input type="checkbox" checked={rules.toolsEnabled} onChange={e => setRules({ ...rules, toolsEnabled: e.target.checked })} /><span>允许智能体使用已授权工具</span></label>
          <p className="helper">关闭工具或将工具次数设为 0，会作为普通对话运行。达到执行上限会终止任务。</p>
          <button className="button secondary">保存执行规则</button>
        </fieldset>
      </form>}
    </details>
    {error && <div className="error" role="alert">{error}</div>}
    {notice && <div className="notice" role="status">{notice}</div>}
  </div>
}
