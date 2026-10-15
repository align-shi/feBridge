import test from 'node:test'
import assert from 'node:assert/strict'
import { parseSkillDocument, textFile, readText, encodeFile, validateSkillFiles, skillDocument } from '../src/skillFiles.ts'

test('imports common metadata and keeps instructions separate', () => {
  const skill = parseSkillDocument('---\nname: mould-query\ndescription: >\n  查询模具\n  和转移状态\n---\n# 查询\n读取 references/rules.md', 'folder')
  assert.equal(skill.name, 'mould-query')
  assert.equal(skill.description, '查询模具 和转移状态')
  assert.equal(skill.instructions, '# 查询\n读取 references/rules.md')
})
test('supports quoted metadata, BOM and Windows newlines', () => {
  const skill = parseSkillDocument('\uFEFF---\r\nname: "模具查询"\r\ndescription: \'状态: 查询\'\r\n---\r\n正文', 'folder')
  assert.equal(skill.name, '模具查询')
  assert.equal(skill.description, '状态: 查询')
  assert.equal(skill.instructions, '正文')
})
test('text resources round-trip unicode and binary remains binary', async () => {
  const file = textFile('references/rules.md', '枫桥 🌉')
  assert.equal(readText(file), '枫桥 🌉')
  const binary = await encodeFile(new File([new Uint8Array([255, 0, 1])], 'template.bin'), 'assets/template.bin')
  assert.equal(readText(binary), null)
  assert.equal(binary.contentBase64, '/wAB')
})
test('oversized upload is rejected before reading bytes', async () => {
  await assert.rejects(encodeFile(new File([new Uint8Array(1048577)], 'large.bin'), 'assets/large.bin'))
})

test('resource paths and reference types are checked before saving', () => {
  for (const path of ['../secret', 'other/run.py', 'references/../secret', 'assets//file', 'assets/a:b']) {
    assert.throws(() => validateSkillFiles([textFile(path, 'text')]))
  }
  assert.throws(() => validateSkillFiles([{ path: 'references/binary', contentBase64: '/w==', mediaType: 'application/octet-stream', sizeBytes: 1 }]))
  assert.doesNotThrow(() => validateSkillFiles([{ path: 'assets/binary', contentBase64: '/w==', mediaType: 'application/octet-stream', sizeBytes: 1 }]))
})
test('duplicate paths, count and actual encoded size cannot bypass file limits', () => {
  const file = textFile('references/rules.md', 'text')
  assert.throws(() => validateSkillFiles([file, file]))
  assert.throws(() => validateSkillFiles(Array.from({ length: 33 }, (_, i) => textFile('assets/' + i + '.md', ''))))
  const oversized = textFile('assets/large.txt', 'x'.repeat(1048577))
  oversized.sizeBytes = 0
  assert.throws(() => validateSkillFiles([oversized]))
})
test('combined resources respect the package size limit', () => {
  const files = Array.from({ length: 4 }, (_, i) => textFile('assets/' + i + '.txt', 'x'.repeat(1048576)))
  assert.doesNotThrow(() => validateSkillFiles(files))
  assert.throws(() => validateSkillFiles([...files, textFile('assets/extra.txt', 'x')]))
})

test("code and display name survive document export and import", () => {
  const original = { code: "mould-query", name: "模具查询", description: "业务查询", instructions: "# 查询", enabled: true }
  const imported = parseSkillDocument(skillDocument(original), "folder")
  assert.equal(imported.code, original.code)
  assert.equal(imported.name, original.name)
  assert.equal(imported.description, original.description)
  assert.equal(imported.instructions.trim(), original.instructions)
})

test("script files can be stored as resources without execution", () => {
  assert.doesNotThrow(() => validateSkillFiles([textFile("scripts/demo.py", "print(1)")]))
})
