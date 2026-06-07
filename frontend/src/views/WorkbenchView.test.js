import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const { mockReplace, mockPush } = vi.hoisted(() => ({ mockReplace: vi.fn(), mockPush: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ replace: mockReplace, push: mockPush }) }))
const { mockChatRefine, mockEvaluate } = vi.hoisted(() => ({ mockChatRefine: vi.fn(), mockEvaluate: vi.fn() }))
vi.mock('@/api/http', () => ({
  fetchScreenplay: vi.fn(),
  validateYaml: vi.fn(),
  chatRefine: mockChatRefine,
  evaluateQuality: mockEvaluate,
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
    mockEvaluate.mockReset()
    localStorage.clear()
  })

  it('AI 评测：点「开始评测」展示评分/评价/建议（Feature 4）', async () => {
    const { w, store } = await mountWorkbench()
    store.sessionId = 'sess-1' // runEval 需要会话以取原著
    mockEvaluate.mockResolvedValue({
      score: 78,
      assessment: '改编较忠实于原著，戏剧张力可再加强。',
      suggestions: ['强化主角动机', '精简冗长动作描写'],
      ai_evaluated: true,
    })
    await w.findAll('.tabs button')[1].trigger('click') // 质量报告页
    await w.find('.ae-run').trigger('click')
    await flushPromises()

    expect(mockEvaluate).toHaveBeenCalledTimes(1)
    const arg = mockEvaluate.mock.calls[0][0]
    expect(arg.sessionId).toBe('sess-1')
    expect(arg.screenplay.scenes[0].id).toBe('S1')

    const ae = w.find('.ai-eval')
    expect(ae.text()).toContain('78')
    expect(ae.text()).toContain('改编较忠实于原著')
    expect(ae.text()).toContain('强化主角动机')
  })

  it('多线程历史：新建对话与原线程互不干扰、可切回（Feature 3）', async () => {
    const { w } = await mountWorkbench()
    await w.findAll('.tabs button')[2].trigger('click') // 切到对话页
    mockChatRefine.mockResolvedValue({ reply: '好的', changed: false })
    await w.find('.chat-input textarea').setValue('测试消息一')
    await w.find('.chat-input .send').trigger('click')
    await flushPromises()
    expect(w.find('.tabpane.chat').text()).toContain('测试消息一')

    // 新建对话：新线程只有开场白，不含上一线程的消息
    await w.find('.th-new').trigger('click')
    await flushPromises()
    expect(w.find('.tabpane.chat').text()).not.toContain('测试消息一')
    expect(w.find('.chat-msgs').text()).toContain('剧本精修助手')

    // 展开历史应有 2 条线程；点回原线程恢复其消息
    await w.find('.th-toggle').trigger('click')
    const threads = w.findAll('.thread')
    expect(threads.length).toBe(2)
    await threads[1].trigger('click') // 最旧的在下方（newest 置顶）
    await flushPromises()
    expect(w.find('.tabpane.chat').text()).toContain('测试消息一')
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

  it('发送消息 → 调用 chatRefine，不自动应用而是出现 diff，采纳后才同步到卡片（Feature: diff）', async () => {
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
    expect(arg.screenplay.scenes[0].id).toBe('S1')

    // 2) 回复出现在对话区
    expect(w.find('.tabpane.chat').text()).toContain('已把 S1 改得更紧张')

    // 3) 不自动应用：出现 diff 待确认，剧本尚未应用（情绪仍是「平静」）
    expect(w.find('.diffpane').exists()).toBe(true)
    expect(w.find('.atag.mood').text()).toContain('平静')

    // 4) 点「采纳」后才同步到卡片：情绪变「紧张」
    await w.find('.diffbar .accept').trigger('click')
    await flushPromises()
    expect(w.find('.diffpane').exists()).toBe(false)
    expect(w.find('.atag.mood').text()).toContain('紧张')
  })

  it('对话改动后点「拒绝」保持原剧本不变（Feature: diff）', async () => {
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
    expect(w.find('.diffpane').exists()).toBe(true)

    await w.find('.diffbar .reject').trigger('click')
    await flushPromises()
    expect(w.find('.diffpane').exists()).toBe(false)
    // 拒绝后保持原情绪「平静」
    expect(w.find('.atag.mood').text()).toContain('平静')
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

  it('changed=false 时不替换剧本（保持原内容）（Feature 2）', async () => {
    const { w } = await mountWorkbench()
    mockChatRefine.mockResolvedValue({
      reply: '没有需要修改的地方。',
      changed: false,
      valid: true,
      screenplay: sampleScreenplay('紧张'),
    })
    await w.findAll('.tabs button')[2].trigger('click')
    await w.find('.chat-input textarea').setValue('保持不变')
    await w.find('.chat-input .send').trigger('click')
    await flushPromises()
    expect(w.find('.tabpane.chat').text()).toContain('没有需要修改的地方')
    // 未发生改动：卡片情绪仍为初始「平静」，未被 changed=false 的返回剧本替换
    expect(w.find('.atag.mood').text()).toContain('平静')
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
