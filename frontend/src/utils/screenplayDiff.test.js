import { describe, it, expect } from 'vitest'
import { diffScreenplay } from './screenplayDiff'

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
