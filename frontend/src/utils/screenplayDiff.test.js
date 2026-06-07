import { describe, it, expect } from 'vitest'
import { diffScreenplay, applyDiffChange } from './screenplayDiff'

// 一份基准剧本：1 角色 + 2 场景（snake_case，与后端/前端一致）。
function base() {
  return {
    meta: { title: '测试剧本', language: 'zh' },
    characters: [{ id: 'C1', name: '林深', role: '主角' }],
    scenes: [
      {
        id: 'S1',
        heading: { int_ext: 'INT', location: '书房', time_of_day: '夜' },
        present_characters: ['C1'],
        elements: [
          { type: 'action', text: '林深推门而入。' },
          { type: 'dialogue', character: 'C1', line: '我回来了。' },
        ],
        mood: '平静',
      },
      {
        id: 'S2',
        heading: { int_ext: 'INT', location: '客厅', time_of_day: '夜' },
        present_characters: ['C1'],
        elements: [{ type: 'action', text: '屋里一片漆黑。' }],
        mood: '平静',
      },
    ],
  }
}

const clone = (o) => JSON.parse(JSON.stringify(o))

describe('diffScreenplay', () => {
  it('无变化时 changed=false', () => {
    const d = diffScreenplay(base(), base())
    expect(d.changed).toBe(false)
  })

  it('标题变更记入 meta', () => {
    const nw = clone(base())
    nw.meta.title = '群山回唱'
    const d = diffScreenplay(base(), nw)
    expect(d.changed).toBe(true)
    expect(d.meta.find((m) => m.field === 'title')).toMatchObject({ before: '测试剧本', after: '群山回唱' })
  })

  it('新增场景标记为 added', () => {
    const nw = clone(base())
    nw.scenes.push({ id: 'S3', heading: { int_ext: 'EXT', location: '街道', time_of_day: '日' }, present_characters: [], elements: [], mood: '' })
    const d = diffScreenplay(base(), nw)
    expect(d.scenes.find((s) => s.id === 'S3').op).toBe('added')
  })

  it('删除场景标记为 removed', () => {
    const nw = clone(base())
    nw.scenes = nw.scenes.filter((s) => s.id !== 'S2')
    const d = diffScreenplay(base(), nw)
    expect(d.scenes.find((s) => s.id === 'S2').op).toBe('removed')
  })

  it('场景内新增元素：该场景 changed，且元素 diff 含 add', () => {
    const nw = clone(base())
    nw.scenes[0].elements.push({ type: 'voiceover', character: 'C1', line: '此刻心绪难平。' })
    const d = diffScreenplay(base(), nw)
    const s1 = d.scenes.find((s) => s.id === 'S1')
    expect(s1.op).toBe('changed')
    const ops = s1.elements.map((e) => e.op)
    expect(ops.filter((o) => o === 'add').length).toBe(1)
    expect(ops.filter((o) => o === 'same').length).toBe(2)
  })

  it('修改对白文本：旧元素 del、新元素 add', () => {
    const nw = clone(base())
    nw.scenes[0].elements[1].line = '我终于回来了。'
    const d = diffScreenplay(base(), nw)
    const s1 = d.scenes.find((s) => s.id === 'S1')
    expect(s1.op).toBe('changed')
    expect(s1.elements.some((e) => e.op === 'del')).toBe(true)
    expect(s1.elements.some((e) => e.op === 'add')).toBe(true)
  })

  it('新增角色记入 characters.added', () => {
    const nw = clone(base())
    nw.characters.push({ id: 'C2', name: '家珍', role: '女主' })
    const d = diffScreenplay(base(), nw)
    expect(d.characters.added.map((c) => c.id)).toContain('C2')
  })

  it('提供可读的改动摘要', () => {
    const nw = clone(base())
    nw.meta.title = '群山回唱'
    const d = diffScreenplay(base(), nw)
    expect(typeof d.summary).toBe('string')
    expect(d.summary.length).toBeGreaterThan(0)
  })
})

// 逐行采纳：把单个改动从「目标剧本」应用到「当前剧本」，产出新剧本（纯函数、不改输入）。
describe('applyDiffChange（逐行采纳的单条合并）', () => {
  // 找到某场景元素 diff 中第一个指定 op 的行下标（与渲染顺序一致）。
  function elRowIndex(oldSp, newSp, sceneId, op) {
    const d = diffScreenplay(oldSp, newSp)
    const s = d.scenes.find((x) => x.id === sceneId)
    return s.elements.findIndex((e) => e.op === op)
  }

  it('不改动输入对象', () => {
    const oldSp = base()
    const newSp = clone(base())
    newSp.meta.title = '群山回唱'
    const snapshot = JSON.stringify(oldSp)
    applyDiffChange(oldSp, newSp, { kind: 'meta', field: 'title' })
    expect(JSON.stringify(oldSp)).toBe(snapshot)
  })

  it('meta：只应用该字段，其它保持当前值', () => {
    const oldSp = base()
    const newSp = clone(base())
    newSp.meta.title = '群山回唱'
    newSp.meta.language = 'en'
    const out = applyDiffChange(oldSp, newSp, { kind: 'meta', field: 'title' })
    expect(out.meta.title).toBe('群山回唱')
    expect(out.meta.language).toBe('zh') // 未采纳的字段不变
  })

  it('element add：在正确位置插入新元素', () => {
    const oldSp = base()
    const newSp = clone(base())
    // 在 S1 中间插入一条画外音
    newSp.scenes[0].elements.splice(1, 0, { type: 'voiceover', character: 'C1', line: '此刻心绪难平。' })
    const idx = elRowIndex(oldSp, newSp, 'S1', 'add')
    const out = applyDiffChange(oldSp, newSp, { kind: 'element', id: 'S1', rowIndex: idx })
    const els = out.scenes.find((s) => s.id === 'S1').elements
    expect(els.map((e) => e.line || e.text)).toEqual(['林深推门而入。', '此刻心绪难平。', '我回来了。'])
  })

  it('element del：移除被删除的元素', () => {
    const oldSp = base()
    const newSp = clone(base())
    newSp.scenes[0].elements.splice(1, 1) // 删掉对白
    const idx = elRowIndex(oldSp, newSp, 'S1', 'del')
    const out = applyDiffChange(oldSp, newSp, { kind: 'element', id: 'S1', rowIndex: idx })
    const els = out.scenes.find((s) => s.id === 'S1').elements
    expect(els.map((e) => e.line || e.text)).toEqual(['林深推门而入。'])
  })

  it('scene-field：应用场景普通字段（情绪）', () => {
    const oldSp = base()
    const newSp = clone(base())
    newSp.scenes[1].mood = '紧张'
    const out = applyDiffChange(oldSp, newSp, { kind: 'scene-field', id: 'S2', field: 'mood' })
    expect(out.scenes.find((s) => s.id === 'S2').mood).toBe('紧张')
  })

  it('scene-field：应用场景头字段（heading.location）', () => {
    const oldSp = base()
    const newSp = clone(base())
    newSp.scenes[0].heading.location = '阁楼'
    const out = applyDiffChange(oldSp, newSp, { kind: 'scene-field', id: 'S1', field: 'heading.location' })
    expect(out.scenes.find((s) => s.id === 'S1').heading.location).toBe('阁楼')
  })

  it('char-add / char-del / char-change', () => {
    const oldSp = base()
    const added = clone(base())
    added.characters.push({ id: 'C2', name: '家珍', role: '女主' })
    expect(applyDiffChange(oldSp, added, { kind: 'char-add', id: 'C2' }).characters.map((c) => c.id)).toContain('C2')

    const removed = clone(base())
    removed.characters = []
    expect(applyDiffChange(oldSp, removed, { kind: 'char-del', id: 'C1' }).characters).toHaveLength(0)

    const changed = clone(base())
    changed.characters[0].name = '林川'
    expect(applyDiffChange(oldSp, changed, { kind: 'char-change', id: 'C1' }).characters[0].name).toBe('林川')
  })

  it('scene-add / scene-del / scene-replace', () => {
    const oldSp = base()
    const added = clone(base())
    added.scenes.push({ id: 'S3', heading: { int_ext: 'EXT', location: '街道', time_of_day: '日' }, present_characters: [], elements: [], mood: '' })
    expect(applyDiffChange(oldSp, added, { kind: 'scene-add', id: 'S3' }).scenes.map((s) => s.id)).toEqual(['S1', 'S2', 'S3'])

    const removed = clone(base())
    removed.scenes = removed.scenes.filter((s) => s.id !== 'S2')
    expect(applyDiffChange(oldSp, removed, { kind: 'scene-del', id: 'S2' }).scenes.map((s) => s.id)).toEqual(['S1'])

    const replaced = clone(base())
    replaced.scenes[0].mood = '压抑'
    replaced.scenes[0].elements.push({ type: 'action', text: '灯忽然灭了。' })
    const out = applyDiffChange(oldSp, replaced, { kind: 'scene-replace', id: 'S1' })
    expect(out.scenes.find((s) => s.id === 'S1')).toEqual(replaced.scenes[0])
  })

  it('采纳唯一改动后，结果与目标剧本一致（diff 收敛为空）', () => {
    const oldSp = base()
    const newSp = clone(base())
    newSp.scenes[1].mood = '紧张'
    const out = applyDiffChange(oldSp, newSp, { kind: 'scene-field', id: 'S2', field: 'mood' })
    expect(diffScreenplay(out, newSp).changed).toBe(false)
  })
})
