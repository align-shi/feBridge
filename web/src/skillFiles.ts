import type { SkillFile, SkillInput } from './api'

export async function encodeFile(file: File, path: string): Promise<SkillFile> {
  if (file.size > 1024 * 1024) throw new Error('单个资源不能超过 1 MB')
  const bytes = new Uint8Array(await file.arrayBuffer())
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return { path, contentBase64: btoa(binary), mediaType: file.type || 'application/octet-stream', sizeBytes: file.size }
}
export function textFile(path: string, text: string): SkillFile {
  const bytes = new TextEncoder().encode(text)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return { path, contentBase64: btoa(binary), mediaType: 'text/plain', sizeBytes: bytes.length }
}
export function readText(file: SkillFile): string | null {
  try {
    const text = new TextDecoder('utf-8', { fatal: true }).decode(Uint8Array.from(atob(file.contentBase64), c => c.charCodeAt(0)))
    return text.includes('\0') ? null : text
  } catch { return null }
}
export function downloadFile(file: SkillFile) {
  const bytes = Uint8Array.from(atob(file.contentBase64), c => c.charCodeAt(0))
  const url = URL.createObjectURL(new Blob([bytes], { type: 'application/octet-stream' }))
  const link = document.createElement('a')
  link.href = url
  link.download = file.path.split('/').at(-1) || 'resource'
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
export function parseSkillDocument(document: string, fallbackName: string): SkillInput {
  const normalized = document.replace(/^\uFEFF/, '').replace(/\r\n/g, '\n')
  const match = normalized.match(/^---\n([\s\S]*?)\n---(?:\n|$)/)
  let name = fallbackName
  let description = ''
  if (match) {
    // Import common scalar metadata; all fields remain editable before saving.
    const scalar = (key: string) => {
      const value = match[1].match(new RegExp('^' + key + ':\\s*(.*)$', 'm'))?.[1]?.trim() || ''
      if (value.startsWith('"')) {
        try { return JSON.parse(value) as string } catch { return '' }
      }
      if (value.startsWith("'") && value.endsWith("'")) return value.slice(1, -1).replace(/''/g, "'")
      if (/^[>|]/.test(value)) {
        const lines = match[1].split('\n')
        const index = lines.findIndex(line => line.startsWith(key + ':'))
        const collected: string[] = []
        for (let i = index + 1; i < lines.length && /^\s/.test(lines[i]); i++) collected.push(lines[i].trim())
        return collected.join(value.startsWith('>') ? ' ' : '\n')
      }
      return value
    }
    name = scalar('name') || name
    description = scalar('description')
  }
  const code = /^[a-z0-9]+(-[a-z0-9]+)*$/.test(name) ? name : fallbackName.toLowerCase().replace(/[^a-z0-9-]/g, '').slice(0, 64)
  const displayName = match?.[1].match(/^  display_name: (".*")$/m)?.[1]
  if (displayName) {
    try { name = JSON.parse(displayName) as string } catch { /* Keep the imported name. */ }
  }
  return { code, name, description, instructions: match ? normalized.slice(match[0].length) : normalized, enabled: true }
}

export function validateSkillFiles(files: SkillFile[]) {
  if (files.length > 32) throw new Error('每个 Skill 最多 32 个资源文件')
  const paths = new Set<string>()
  let total = 0
  for (const file of files) {
    if (file.path.length > 240 || !/^(references|assets|scripts)\//.test(file.path) ||
      file.path.includes('\\') || file.path.includes(':') || /[\x00-\x1f\x7f]/.test(file.path) ||
      file.path.split('/').some(part => !part.trim() || part === '.' || part === '..')) {
      throw new Error('文件路径必须位于 references/、assets/ 或 scripts/，不能包含相对跳转')
    }
    if (paths.has(file.path)) throw new Error('资源路径不能重复')
    paths.add(file.path)
    const size = atob(file.contentBase64).length
    if (size > 1024 * 1024) throw new Error('单个资源不能超过 1 MB')
    total += size
    if (total > 4 * 1024 * 1024) throw new Error('资源总大小不能超过 4 MB')
    if (file.path.startsWith('references/') && readText(file) === null) {
      throw new Error('references 必须为 UTF-8 文本，二进制附件请上传到 assets')
    }
  }
}

export function skillDocument(skill: SkillInput): string {
  return "---\nname: " + JSON.stringify(skill.code || skill.name) +
    "\ndescription: " + JSON.stringify(skill.description) +
    "\nmetadata:\n  display_name: " + JSON.stringify(skill.name) + "\n---\n\n" + skill.instructions
}
