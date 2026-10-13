import { useErrorState } from './ErrorNotice'
import { useEffect, useState } from 'react'
import { configApi, errorMessage } from './api'
import type { Skill, SkillInput } from './api'

const empty: SkillInput = { name: '', description: '', instructions: '', enabled: true }
export default function SkillManager() {
  const [skills, setSkills] = useState<Skill[]>([])
  const [editingId, setEditingId] = useState<string | null>(null)
  const [draft, setDraft] = useState<SkillInput>(empty)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useErrorState()
  const [notice, setNotice] = useState('')
  async function load() { setSkills(await configApi.skills()) }
  useEffect(() => { load().catch(e => setError(errorMessage(e))) }, [])
  return <section>
    <div className="page-heading"><div><div className="eyebrow">BUSINESS SKILLS</div><h1>Skills 管理</h1><p>将业务处理方法写成可复用指令，绑定给一个或多个智能体。</p></div></div>
    <form className="panel create-form" onSubmit={async e => {
      e.preventDefault()
      if (busy) return
      setBusy(true); setError(''); setNotice('')
      try {
        await configApi.saveSkill(editingId, draft)
        await load()
        setEditingId(null); setDraft(empty); setNotice('Skill 已保存。')
      } catch (e) { setError(errorMessage(e)) }
      finally { setBusy(false) }
    }}>
      <fieldset disabled={busy}><h2>{editingId ? '修改 Skill' : '创建 Skill'}</h2>
        <label>Skill 名称<input required maxLength={100} value={draft.name} onChange={e => setDraft({ ...draft, name: e.target.value })} /></label>
        <label>用途说明<textarea rows={2} maxLength={1000} value={draft.description} onChange={e => setDraft({ ...draft, description: e.target.value })} /></label>
        <label>Skill 指令（Markdown）<textarea className="code" required rows={10} maxLength={8000} value={draft.instructions}
          placeholder={'# 客户查询\n1. 先确认客户编号。\n2. 调用客户查询工具。\n3. 根据真实结果回答，查询失败时不要编造资料。'}
          onChange={e => setDraft({ ...draft, instructions: e.target.value })} /></label>
        <p className="helper">此版 Skill 是业务指令。绑定后会全文加入系统提示词；执行外部操作仍需绑定工具。修改会在绑定它的智能体下一次请求中生效。</p>
        <label className="tool-choice"><input type="checkbox" checked={draft.enabled} onChange={e => setDraft({ ...draft, enabled: e.target.checked })} /><span>启用 Skill</span></label>
        {error && <p className="error" role="alert">{error}</p>}{notice && <p className="notice" role="status">{notice}</p>}
        <div className="form-actions"><button className="button primary">{busy ? '保存中…' : '保存 Skill'}</button>
          {editingId && <button type="button" className="button secondary" onClick={() => { setEditingId(null); setDraft(empty) }}>取消修改</button>}
        </div>
      </fieldset>
    </form>
    <h2>已有 Skills · {skills.length}</h2>
    <div className="tool-services">{skills.map(skill => <article className="panel" key={skill.id}>
      <h3>{skill.name} <span className="tag">{skill.enabled ? '已启用' : '已停用'}</span></h3><p>{skill.description}</p>
      <details><summary>查看指令</summary><pre>{skill.instructions}</pre></details>
      <button className="button secondary" disabled={busy} onClick={() => { setEditingId(skill.id); setDraft({ name: skill.name, description: skill.description, instructions: skill.instructions, enabled: skill.enabled }); setError(''); setNotice('') }}>编辑 Skill</button>
    </article>)}</div>
  </section>
}
