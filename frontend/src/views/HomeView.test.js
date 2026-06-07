import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

// mock 路由 / naive-ui 消息 / http，使组件可在 happy-dom 中独立挂载。
const { mockPush } = vi.hoisted(() => ({ mockPush: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mockPush }) }))
vi.mock('naive-ui', () => ({ useMessage: () => ({ error: vi.fn(), success: vi.fn(), info: vi.fn() }) }))
const { mockCreateSession } = vi.hoisted(() => ({ mockCreateSession: vi.fn() }))
vi.mock('@/api/http', () => ({ createSession: mockCreateSession }))

import HomeView from './HomeView.vue'
import { useAppStore } from '@/stores/app'

describe('HomeView —— 上传时提需求（Feature 1a）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    mockPush.mockReset()
    mockCreateSession.mockReset()
    mockCreateSession.mockResolvedValue('sess-1')
  })

  it('渲染「改编需求」输入框', () => {
    const w = mount(HomeView)
    const ta = w.find('#req')
    expect(ta.exists()).toBe(true)
    expect(ta.attributes('placeholder')).toContain('例如')
  })

  it('选样本 + 填需求 + 开始 → createSession 携带 requirements，并写入 store', async () => {
    const w = mount(HomeView)
    const store = useAppStore()

    // 选第一个内置样本（huozhe），使 canStart 成立
    await w.findAll('.chip')[0].trigger('click')
    // 填写改编需求
    await w.find('#req').setValue('突出悬疑紧张氛围')
    // 点击开始生成
    await w.find('.start').trigger('click')
    await flushPromises()

    expect(mockCreateSession).toHaveBeenCalledTimes(1)
    expect(mockCreateSession).toHaveBeenCalledWith(
      expect.objectContaining({ sampleId: 'huozhe', requirements: '突出悬疑紧张氛围' }),
    )
    // 会话来源中也带上 requirements，供工作台对话开场回显
    expect(store.sessionId).toBe('sess-1')
    expect(store.source.requirements).toBe('突出悬疑紧张氛围')
    expect(mockPush).toHaveBeenCalledWith('/progress')
  })

  it('空需求时 createSession 的 requirements 为 undefined', async () => {
    const w = mount(HomeView)
    await w.findAll('.chip')[0].trigger('click')
    await w.find('.start').trigger('click')
    await flushPromises()
    expect(mockCreateSession).toHaveBeenCalledWith(
      expect.objectContaining({ sampleId: 'huozhe', requirements: undefined }),
    )
  })
})
