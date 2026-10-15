import { useErrorState } from './ErrorNotice'
import { useEffect, useRef, useState } from 'react'
import { configApi, errorMessage } from './api'
import SkillEditor from './SkillEditor'
import { encodeFile, parseSkillDocument, validateSkillFiles } from './skillFiles'
import type { SkillFile, Skill, SkillInput } from './api'

const empty: SkillInput = { code: '', name: '', description: '', instructions: '', enabled: true }
const editPattern = /^#\/skills\/([0-9a-f-]{36})$/i

export default function SkillManager() {
  const savedRoute = useRef<string | null>(null)
  const [route, setRoute] = useState(window.location.hash)
  const [skills, setSkills] = useState<Skill[]>([])
  const [draft, setDraft] = useState<SkillInput>(empty)
  const [folders, setFolders] = useState<string[]>([])
  const [files, setFiles] = useState<SkillFile[]>([])
  const [selectedPath, setSelectedPath] = useState('SKILL.md')
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(false)
  const [dirty, setDirty] = useState(false)
  const [editorVersion, setEditorVersion] = useState(0)
  const [search, setSearch] = useState('')
  const [error, setError] = useErrorState()
  const [notice, setNotice] = useState('')
  const editingId = route.match(editPattern)?.[1] ?? null
  const editor = route === '#/skills/new' || editingId !== null

  useEffect(() => {
    const sync = () => setRoute(window.location.hash)
    window.addEventListener('hashchange', sync)
    window.addEventListener('popstate', sync)
    return () => {
      window.removeEventListener('hashchange', sync)
      window.removeEventListener('popstate', sync)
    }
  }, [])
  useEffect(() => {
    if (savedRoute.current === route) { savedRoute.current = null; return }
    let active = true
    setDraft(empty)
    setFiles([])
    setFolders([])
    setError('')
    setNotice('')
    setSelectedPath('SKILL.md')
    setEditorVersion(value => value + 1)
    setDirty(false)
    if (route === '#/skills/new') {
      setDraft(empty)
      setFiles([])
      setLoading(false)
      return
    }
    setLoading(true)
    configApi.skills().then(async items => {
      if (!active) return
      setSkills(items)
      if (editingId) {
        const skill = items.find(item => item.id === editingId)
        if (!skill) throw new Error('Skill 不存在')
        const [resources, directories] = await Promise.all([configApi.skillResources(skill.id), configApi.skillFolders(skill.id)])
        if (!active) return
        setDraft({ code: skill.code || 'skill-' + skill.id.replaceAll('-', ''), name: skill.name,
          description: skill.description, instructions: skill.instructions, enabled: skill.enabled })
        setFiles(resources)
        setFolders(directories)
      }
    }).catch(error => { if (active) setError(errorMessage(error)) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [route])

  function navigate(path: string) {
    window.location.hash = path
  }
  async function importDirectory(input: HTMLInputElement) {
    const selected = Array.from(input.files || [])
    if (!selected.length) return
    setBusy(true)
    setError('')
    setNotice('')
    try {
      const root = selected[0].webkitRelativePath.split('/')[0]
      const main = selected.find(file => file.webkitRelativePath === root + '/SKILL.md')
      if (!main) throw new Error('目录根部需要 SKILL.md')
      if (main.size > 100000) throw new Error('SKILL.md 文件过大')
      const resources = selected.filter(file => file !== main)
      if (resources.some(file => !/^(references|assets|scripts)\//.test(file.webkitRelativePath.slice(root.length + 1)))) {
        throw new Error('此版本只导入 SKILL.md、references、assets 和 scripts；请移除其他文件')
      }
      if (resources.length > 32 || resources.reduce((sum, file) => sum + file.size, 0) > 4 * 1024 * 1024) {
        throw new Error('资源最多 32 个，总计 4 MB')
      }
      const imported = parseSkillDocument(await main.text(), root)
      if (imported.instructions.length > 8000) throw new Error('Skill 正文不能超过 8000 字符')
      const importedFiles = await Promise.all(resources.map(file => encodeFile(file, file.webkitRelativePath.slice(root.length + 1))))
      validateSkillFiles(importedFiles)
      // Stay on the current edit page: import replaces this draft, and requires an explicit save.
      setDraft(imported)
      setFiles(importedFiles)
      setFolders([...new Set(importedFiles.map(file => file.path.split("/")[0]))])
      setDirty(true)
      setSelectedPath('SKILL.md')
      setEditorVersion(value => value + 1)
      setNotice('目录已载入当前草稿，请核对编码、名称、描述和文件内容后保存。')
    } catch (error) { setError(errorMessage(error)) }
    finally { setBusy(false); input.value = '' }
  }
  const filtered = skills.filter(skill =>
    [skill.code, skill.name, skill.description].join(' ').toLowerCase().includes(search.toLowerCase()))

  if (!editor) return <section className="skills-page">
    <div className="page-heading"><div><div className="eyebrow">BUSINESS SKILLS</div><h1>Skills 管理</h1>
      <p>管理可复用的业务指令、参考资料与模板。</p></div>
      <button className="button primary" onClick={() => navigate('#/skills/new')}>＋ 新建 Skill</button></div>
    <div className="toolbar"><label className="search"><span aria-hidden="true">⌕</span>
      <input aria-label="搜索 Skill" placeholder="搜索编码、名称或描述…" value={search} onChange={e => setSearch(e.target.value)} /></label>
      <span className="helper">{filtered.length} 个 Skill</span></div>
    {error && <p className="error" role="alert">{error}</p>}
    {loading && <p className="panel" role="status">正在加载 Skills…</p>}
    {!loading && !error && !filtered.length && <div className="empty"><h2>{skills.length ? '没有匹配的 Skill' : '创建第一个 Skill'}</h2>
      <p>用清晰的指令组织业务能力。</p></div>}
    <div className="skill-library">{filtered.map(skill => <button type="button" key={skill.id}
      className="skill-library-card" onClick={() => navigate('#/skills/' + skill.id)} aria-label={'编辑 Skill ' + skill.name}>
      <div><strong>{skill.name}</strong><span className="tag">{skill.enabled ? '已启用' : '已停用'}</span></div>
      <code className="skill-card-code">{skill.code || 'skill-' + skill.id.replaceAll('-', '')}</code>
      <p>{skill.description || '尚未填写描述'}</p><small>编辑 Skill <span>↗</span></small>
    </button>)}</div>
  </section>

  return <section className="skills-page">
    <div className="page-heading"><div><div className="eyebrow">SKILL WORKSPACE</div><h1>{editingId ? '编辑 Skill' : '新建 Skill'}</h1>
      <p>上方配置基本信息，下方按目录编辑指令与资源。</p></div>
      <button className="button secondary" disabled={busy} onClick={() => navigate('#/skills')}>← 返回 Skills 列表</button></div>
    {loading ? <p className="panel" role="status">正在加载 Skill…</p> : error && !draft.name && editingId
      ? <p className="error" role="alert">{error}</p> : <form className="skill-workbench" onSubmit={async e => {
        e.preventDefault()
        if (busy) return
        setBusy(true)
        setError('')
        setNotice('')
        try {
          if (!draft.instructions.trim()) throw new Error('请填写 SKILL.md 的指令正文')
          validateSkillFiles(files)
          const saved = await configApi.saveSkillPackage(editingId, draft, files, folders)
          setDraft({ code: saved.code || draft.code, name: saved.name, description: saved.description,
            instructions: saved.instructions, enabled: saved.enabled })
          setDirty(false)
          setNotice('Skill 及资源文件已保存。')
          if (!editingId) {
            window.history.replaceState(null, '', '#/skills/' + saved.id)
            savedRoute.current = "#/skills/" + saved.id
            setRoute('#/skills/' + saved.id)
          }
        } catch (error) { setError(errorMessage(error)) }
        finally { setBusy(false) }
      }}>
      <div className="skill-workbench-body" aria-busy={busy} inert={busy}>
        <section className="skill-top-info" aria-label="Skill 基本信息">
          <div className="skill-info-caption"><span>SKILL INFORMATION</span>
            <label className="skill-enabled"><input type="checkbox" checked={draft.enabled}
              onChange={e => { setDraft({ ...draft, enabled: e.target.checked }); setDirty(true) }} /><span>启用 Skill</span></label></div>
          <div className="skill-identity-fields">
            <label>Skill 编码<input aria-label="Skill 编码" required maxLength={64} pattern="[a-z0-9]+(-[a-z0-9]+)*"
              placeholder="如 mould-transfer" value={draft.code || ''} onChange={e => {
                setDraft({ ...draft, code: e.target.value }); setDirty(true); setNotice('')
              }} /><small>小写字母、数字和连字符，全平台唯一。</small></label>
            <label>Skill 名称<input required maxLength={100} value={draft.name} placeholder="如 模具转移查询"
              onChange={e => { setDraft({ ...draft, name: e.target.value }); setDirty(true); setNotice('') }} /></label>
          </div>
          <label>描述与触发条件<textarea rows={2} maxLength={1000} value={draft.description}
            placeholder="描述什么时候应该使用这个 Skill，帮助模型按需选择"
            onChange={e => { setDraft({ ...draft, description: e.target.value }); setDirty(true); setNotice('') }} /></label>
        </section>
        <div className="skill-workbench-header">
          <div><h2>文件工作台</h2><span role="status" title={notice || undefined} className={'skill-save-state ' + (dirty ? 'dirty' : '')}>
            {dirty ? '有未保存的修改' : editingId ? '已保存' : '新草稿'}</span></div>
          <div className="form-actions"><label className="button secondary skill-upload">导入目录<input type="file" multiple
            aria-label="导入 Skill 目录" {...{ webkitdirectory: '' }} onChange={e => void importDirectory(e.currentTarget)} /></label>
            <button className="button primary" disabled={!dirty}>{busy ? '保存中…' : '保存 Skill'}</button></div>
        </div>
        <SkillEditor key={editorVersion} draft={draft} files={files} folders={folders}
          onFolders={next => { setFolders(next); setDirty(true); setNotice("") }} selectedPath={selectedPath}
          onSelect={setSelectedPath} onDraft={next => { setDraft(next); setDirty(true); setNotice('') }}
          onFiles={next => { setFiles(next); setDirty(true); setNotice('') }} onError={setError} />
      </div>
    </form>}
  </section>
}
