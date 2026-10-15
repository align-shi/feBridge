import { useEffect, useState } from 'react'
import type { SkillFile, SkillInput } from './api'
import { downloadFile, readText, textFile, validateSkillFiles, skillDocument } from './skillFiles'

interface Props {
  draft: SkillInput
  folders: string[]
  onFolders: (folders: string[]) => void
  files: SkillFile[]
  selectedPath: string
  onSelect: (path: string) => void
  onDraft: (draft: SkillInput) => void
  onFiles: (files: SkillFile[]) => void
  onError: (message: string) => void
}
interface TreeNode { name: string; path: string; children: TreeNode[]; file?: SkillFile }

function buildTree(files: SkillFile[], folders: string[]): TreeNode[] {
  const roots: TreeNode[] = ["references", "assets", "scripts"].filter(name => folders.includes(name))
    .map(name => ({ name, path: name, children: [] }))
  for (const file of [...files].sort((a, b) => a.path.localeCompare(b.path))) {
    const parts = file.path.split('/')
    let children = roots
    let path = ''
    for (let index = 0; index < parts.length; index++) {
      path = path ? path + '/' + parts[index] : parts[index]
      let node = children.find(item => item.name === parts[index])
      if (!node) {
        node = { name: parts[index], path, children: [] }
        children.push(node)
      }
      if (index === parts.length - 1) node.file = file
      children = node.children
    }
  }
  return roots
}

function FileIcon({ folder = false }: { folder?: boolean }) {
  return <svg viewBox="0 0 20 20" fill="none" aria-hidden="true">
    {folder
      ? <path d="M2.5 5.5h5l2 2h8v8h-15z" />
      : <><path d="M5 2.5h6l4 4v11H5z" /><path d="M11 2.5v4h4M7.5 10h5M7.5 13h4" /></>}
  </svg>
}

function TreeBranch({ nodes, selected, selectedFolder, onSelect, onFolder }: {
  nodes: TreeNode[]; selected: string; selectedFolder: string | null;
  onSelect: (path: string) => void; onFolder: (path: string) => void
}) {
  return <ul className="skill-tree-list">{nodes.map(node => <li key={node.path}>
    {node.file
      ? <button type="button" className={'skill-tree-file ' + (selected === node.path ? 'selected' : '')}
        aria-label={'编辑文件 ' + node.path} aria-current={selected === node.path ? 'page' : undefined}
        title={node.path} onClick={() => onSelect(node.path)}>
        <FileIcon /><span>{node.name}</span>
      </button>
      : <FolderBranch node={node} selected={selected} selectedFolder={selectedFolder}
        onSelect={onSelect} onFolder={onFolder} />}
  </li>)}</ul>
}

function FolderBranch({ node, selected, selectedFolder, onSelect, onFolder }: {
  node: TreeNode; selected: string; selectedFolder: string | null;
  onSelect: (path: string) => void; onFolder: (path: string) => void
}) {
  const [open, setOpen] = useState(true)
  return <div className="skill-folder-branch">
    <div className={'skill-folder-row ' + (selectedFolder === node.path ? 'selected' : '')}>
      <button type="button" className="skill-folder-toggle" aria-label={(open ? '折叠目录 ' : '展开目录 ') + node.path}
        aria-expanded={open} onClick={() => setOpen(!open)}>{open ? '▾' : '▸'}</button>
      <button type="button" className="skill-folder-select" aria-label={'选择文件夹 ' + node.path}
        aria-pressed={selectedFolder === node.path} onClick={() => { setOpen(true); onFolder(node.path) }}>
        <FileIcon folder /><span>{node.name}</span>
      </button>
    </div>
    {open && (node.children.length
      ? <TreeBranch nodes={node.children} selected={selected} selectedFolder={selectedFolder}
        onSelect={onSelect} onFolder={onFolder} />
      : <p className="skill-folder-empty">暂无文件</p>)}
  </div>
}

export default function SkillEditor({
  draft, files, folders, onFolders, selectedPath, onSelect, onDraft, onFiles, onError,
}: Props) {
  const [renaming, setRenaming] = useState(false)
  const [renamePath, setRenamePath] = useState('')
  useEffect(() => { setRenaming(false) }, [selectedPath])
  const [creating, setCreating] = useState(false)
  const [creatingFolder, setCreatingFolder] = useState(false)
  const [newFolder, setNewFolder] = useState('references')
  const [fileName, setFileName] = useState('')
  const [selectedFolder, setSelectedFolder] = useState<string | null>(null)
  const [folderFocused, setFolderFocused] = useState(false)
  const main = selectedPath === 'SKILL.md'
  const file = files.find(item => item.path === selectedPath)
  const text = main ? draft.instructions : file ? readText(file) : null
  const binary = !main && file && text === null

  function apply(next: SkillFile[]) {
    validateSkillFiles(next)
    onFiles(next)
  }
  function createFile() {
    try {
      if (!selectedFolder) throw new Error('请先选择文件夹')
      const name = fileName.trim()
      if (!name || name === '.' || name === '..' || /[\\/:]/.test(name)) {
        throw new Error('请输入文件名，不需要填写目录路径')
      }
      const newPath = selectedFolder + '/' + name
      if (files.some(item => item.path === newPath)) throw new Error('文件已存在，请在目录中选择它')
      apply([...files, textFile(newPath, '')])
      selectFile(newPath)
      setCreating(false)
      setFileName('')
    } catch (error) { onError(error instanceof Error ? error.message : String(error)) }
  }
  function createFolder() {
    if (folders.includes(newFolder) || files.some(file => file.path.startsWith(newFolder + '/'))) {
      onError('该文件夹已存在，请选择其他文件夹')
      return
    }
    onFolders([...folders, newFolder])
    selectFolder(newFolder)
    setCreatingFolder(false)
  }
  function selectFile(path: string) {
    setSelectedFolder(path === 'SKILL.md' ? null : path.slice(0, path.lastIndexOf('/')))
    setFolderFocused(false)
    setCreating(false)
    onSelect(path)
  }
  function selectFolder(path: string) {
    setSelectedFolder(path)
    setFolderFocused(true)
  }
  function renameFile() {
    if (!file) return
    const path = renamePath.trim()
    try {
      if (files.some(item => item.path === path && item.path !== selectedPath)) {
        throw new Error('该路径已有文件，请使用其他名称')
      }
      apply(files.map(item => item.path === selectedPath ? { ...item, path } : item))
      selectFile(path)
      setRenaming(false)
    } catch (error) { onError(error instanceof Error ? error.message : String(error)) }
  }
  function changeText(value: string) {
    if (main) {
      onDraft({ ...draft, instructions: value })
      return
    }
    if (!file) return
    try {
      apply(files.map(item => item.path === selectedPath
        ? { ...textFile(selectedPath, value), mediaType: file.mediaType } : item))
    } catch (error) { onError(error instanceof Error ? error.message : String(error)) }
  }
  const selectedDownload = main
    ? textFile('SKILL.md', skillDocument(draft))
    : file

  return <div className="skill-editor-layout">
    <aside className="skill-file-sidebar" aria-label="Skill 文件目录">
      <div className="skill-directory-heading"><strong>文件目录</strong><span>{files.length + 1}</span></div>
      <div className="skill-directory-create">
        <div className="skill-directory-iconbar" aria-label="目录操作">
          <span className="skill-icon-tip" data-tooltip={selectedFolder ? '新建文件' : '新建文件 · 请先选择文件夹'}>
            <button type="button" className="skill-directory-icon" aria-label="新建文件" disabled={!selectedFolder}
              onClick={() => { setFileName(''); setCreating(!creating); setCreatingFolder(false) }}>
              <svg viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M4 2.5h6l4 4v3M10 2.5v4h4M4 2.5v15h5M14 11v7M10.5 14.5h7" /></svg>
            </button>
          </span>
          <span className="skill-icon-tip" data-tooltip="新建文件夹">
            <button type="button" className="skill-directory-icon" aria-label="新建文件夹"
              onClick={() => { setNewFolder('references'); setCreatingFolder(!creatingFolder); setCreating(false) }}>
              <svg viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M2.5 5h5l2 2h8v3M2.5 5v11h6M14 11v7M10.5 14.5h7" /></svg>
            </button>
          </span>
        </div>
        {creatingFolder && <div className="skill-new-file">
          <label>文件夹类型<select aria-label="新文件夹类型" value={newFolder} onChange={e => setNewFolder(e.target.value)}>
            <option value="references">references · 参考文档</option>
            <option value="assets">assets · 模板附件</option>
            <option value="scripts">scripts · 脚本文件</option>
          </select></label>
          <div className="form-actions">
            <button type="button" className="button primary" onClick={createFolder}>创建文件夹</button>
            <button type="button" className="button secondary" onClick={() => setCreatingFolder(false)}>取消</button>
          </div>
        </div>}
        {creating && <div className="skill-new-file">
          <p className="skill-create-target">创建到 {selectedFolder}/</p>
          <label>文件名<input aria-label="新文件名" autoFocus placeholder="如 query-rules.md" value={fileName} maxLength={160}
            onChange={e => setFileName(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); createFile() } }} /></label>
          <div className="form-actions">
            <button type="button" className="button primary" onClick={createFile}>创建文件</button>
            <button type="button" className="button secondary" onClick={() => setCreating(false)}>取消</button>
          </div>
        </div>}
      </div>
      <div className="skill-directory-root"><FileIcon folder /><span>{draft.code || draft.name || 'untitled-skill'}</span></div>
      <nav className="skill-file-tree" aria-label="选择 Skill 文件">
        <button type="button" className={'skill-tree-file skill-main-file ' + (main && !folderFocused ? 'selected' : '')}
          aria-label="编辑文件 SKILL.md" aria-current={main ? 'page' : undefined}
          onClick={() => selectFile('SKILL.md')}><FileIcon /><span>SKILL.md</span><small>主指令</small></button>
        <TreeBranch nodes={buildTree(files, folders)} selected={folderFocused ? "" : selectedPath}
          selectedFolder={folderFocused ? selectedFolder : null} onSelect={selectFile} onFolder={selectFolder} />
      </nav>
    </aside>

    <div className="skill-editor-right">
      <section className="skill-document-panel" aria-label="文件编辑器">
        <div className="skill-document-toolbar">
          <div><FileIcon /><strong>{selectedPath}</strong><span>{main ? 'Markdown' : binary ? '附件' : '文本'}</span></div>
          <div className="form-actions">
            {!main && file && <button type="button" className="button secondary" onClick={() => { setRenamePath(selectedPath); setRenaming(true) }}>重命名</button>}
            {selectedDownload && <button type="button" className="button secondary" onClick={() => downloadFile(selectedDownload)}>下载文件</button>}
            {!main && file && <button type="button" className="button secondary skill-remove-file" onClick={() => {
              onFiles(files.filter(item => item.path !== selectedPath))
              onSelect('SKILL.md')
            }}>移除文件</button>}
          </div>
        </div>
        {renaming && <div className="skill-rename-bar">
          <label>文件路径<input aria-label="重命名文件路径" autoFocus maxLength={240} value={renamePath}
            onChange={e => setRenamePath(e.target.value)}
            onKeyDown={e => {
              if (e.key === 'Enter') { e.preventDefault(); renameFile() }
              if (e.key === 'Escape') { e.preventDefault(); setRenaming(false) }
            }} /></label>
          <div className="form-actions">
            <button type="button" className="button primary" onClick={renameFile}>确认重命名</button>
            <button type="button" className="button secondary" onClick={() => setRenaming(false)}>取消</button>
          </div>
          <p>重命名后请同步修改指令中引用的路径，再点击“保存 Skill”。</p>
        </div>}
        {binary ? <div className="skill-binary-preview">
          <FileIcon />
          <h3>{file.path.split('/').at(-1)}</h3>
          <p>{file.mediaType} · {(file.sizeBytes / 1024).toFixed(1)} KB</p>
          <p>这是二进制附件，可以下载查看。</p>

        </div> : <textarea className="skill-document-textarea" aria-label={'文件内容 ' + selectedPath}
          spellCheck={false} value={text ?? ''} maxLength={main ? 8000 : undefined}
          placeholder={main ? '# 业务处理步骤\n\n1. 按需读取 references 中的参考文档。\n2. 调用智能体已绑定的 MCP 工具。\n3. 根据真实结果回答。' : '在这里编写文件内容…'}
          onChange={e => changeText(e.target.value)} />}
      </section>
    </div>
  </div>
}
