import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const { mockReplace, mockPush } = vi.hoisted(() => ({ mockReplace: vi.fn(), mockPush: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ replace: mockReplace, push: mockPush }) }))
const { mockChatRefine } = vi.hoisted(() => ({ mockChatRefine: vi.fn() }))
vi.mock('@/api/http', () => ({
  fetchScreenplay: vi.fn(),
  validateYaml: vi.fn(),
  chatRefine: mockChatRefine,
}))

import WorkbenchView from './WorkbenchView.vue'
import { useAppStore } from '@/stores/app'

function sampleScreenplay(mood = '平静') {
  return {
    meta: { title: '测试剧本', generated_by: 'stub' },
    characters: [{ id: 'C1', name: '林深', role: '主角', aliases: ['林先生'], relations: [] }],
    scenes: [
      {
        id: 'S1',
        chapter: '第1章',
        heading: { int_ext: 'INT', location: '书房', time_of_day: '夜' },
        present_characters: ['C1'],
        elements: [{ type: 'action', text: '林深推门而入。' }],
        mood,
        pacing: '中',
        shots: ['中景'],
        source: '原文片段',
      },
    ],
    report: {
      score: 80, grade: '良好', schema_valid: true, schema_error_count: 0,
      dialogue_attribution_rate: 1, character_consistency_rate: 1,
      scene_heading_completeness_rate: 1, show_vs_tell_ratio: 1,
      scene_count: 1, character_count: 1, avg_elements_per_scene: 1, issues: [],
    },
  }
}

async function mountWorkbench() {
  setActivePinia(createPinia())
  const store = useAppStore()
  store.screenplay = sampleScreenplay()
  store.source = { language: 'zh', requirements: '突出悬疑' }
  const w = mount(WorkbenchView)
  await flushPromises() // 等 onMounted 完成（data 就绪）
  return { w, store }
}

describe('WorkbenchView —— AI 多轮对话精修（Feature 1b）', () => {
  beforeEach(() => {
    mockReplace.mockReset()
    mockPush.mockReset()
    mockChatRefine.mockReset()
  })

  it('「返回首页」按钮跳转到 /（Feature 1b）', async () => {
    const { w } = await mountWorkbench()
    const home = w.find('.home-btn')
    expect(home.exists()).toBe(true)
    await home.trigger('click')
    expect(mockPush).toHaveBeenCalledWith('/')
  })

  it('渲染「AI 对话」标签，并显示回显用户需求的开场白', async () => {
    const { w } = await mountWorkbench()
    const tabs = w.findAll('.tabs button')
    expect(tabs.map((b) => b.text())).toContain('AI 对话')
    await tabs[2].trigger('click') // 切到对话页
    const chat = w.find('.tabpane.chat')
    expect(chat.exists()).toBe(true)
    expect(chat.text()).toContain('突出悬疑') // 开场白回显上传时填写的需求
  })

  it('发送消息 → 调用 chatRefine，回填回复并替换剧本（情绪更新同步到卡片）', async () => {
    const { w } = await mountWorkbench()
    mockChatRefine.mockResolvedValue({
      reply: '已把 S1 改得更紧张。',
      changed: true,
      valid: true,
      screenplay: sampleScreenplay('紧张'),
    })

    await w.findAll('.tabs button')[2].trigger('click')
    await w.find('.chat-input textarea').setValue('把 S1 改得更紧张')
    await w.find('.chat-input .send').trigger('click')
    await flushPromises()

    // 1) 以当前剧本 + 消息 + 语言调用后端
    expect(mockChatRefine).toHaveBeenCalledTimes(1)
    const arg = mockChatRefine.mock.calls[0][0]
    expect(arg.message).toBe('把 S1 改得更紧张')
    expect(arg.language).toBe('zh')
    expect(arg.screenplay.scenes[0].id).toBe('S1')

    // 2) 回复出现在对话区
    expect(w.find('.tabpane.chat').text()).toContain('已把 S1 改得更紧张')

    // 3) 剧本被替换 → 卡片上的情绪标注更新为「紧张」
    expect(w.find('.atag.mood').text()).toContain('紧张')
  })

  it('YAML 视图默认「仅当前场景」，切「完整剧本」显示整部（Feature 1c）', async () => {
    const { w } = await mountWorkbench()
    // 切到 YAML 视图（中心工具栏第二个分段按钮）
    await w.findAll('.ctool .seg button')[1].trigger('click')
    await flushPromises()
    const ta = w.find('textarea.yta')
    expect(ta.exists()).toBe(true)
    // 默认 scene 范围：只含当前场景，不应出现顶层 meta/scenes 键
    expect(ta.element.value).toContain('id: S1')
    expect(ta.element.value).not.toMatch(/^scenes:/m)
    expect(ta.element.value).not.toMatch(/^meta:/m)
    // 切到「完整剧本」：出现顶层 meta/scenes
    const scopeBtns = w.findAll('.yscope button')
    expect(scopeBtns.map((b) => b.text())).toContain('完整剧本')
    await scopeBtns[1].trigger('click')
    await flushPromises()
    const full = w.find('textarea.yta').element.value
    expect(full).toMatch(/^scenes:/m)
    expect(full).toMatch(/^meta:/m)
    expect(full).toContain('title: 测试剧本')
  })

  it('chatRefine 出错时追加错误气泡且不崩溃', async () => {
    const { w } = await mountWorkbench()
    mockChatRefine.mockRejectedValue({ message: '网络中断' })
    await w.findAll('.tabs button')[2].trigger('click')
    await w.find('.chat-input textarea').setValue('随便改改')
    await w.find('.chat-input .send').trigger('click')
    await flushPromises()
    expect(w.find('.tabpane.chat').text()).toContain('网络中断')
  })
})
