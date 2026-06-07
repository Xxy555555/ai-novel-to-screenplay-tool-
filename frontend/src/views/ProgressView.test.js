import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'

const { mockPush, mockReplace } = vi.hoisted(() => ({ mockPush: vi.fn(), mockReplace: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mockPush, replace: mockReplace }) }))
vi.mock('naive-ui', () => ({ useMessage: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }) }))
const { mockOpen } = vi.hoisted(() => ({ mockOpen: vi.fn() }))
vi.mock('@/api/sse', () => ({ openGeneration: mockOpen }))

import ProgressView from './ProgressView.vue'
import { useAppStore } from '@/stores/app'

describe('ProgressView —— SSE 实时生成进度', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    mockPush.mockReset()
    mockReplace.mockReset()
    mockOpen.mockReset()
    mockOpen.mockReturnValue({ close: vi.fn() })
  })

  it('无 sessionId 时跳回首页', () => {
    mount(ProgressView)
    expect(mockReplace).toHaveBeenCalledWith('/')
    expect(mockOpen).not.toHaveBeenCalled()
  })

  it('SSE 事件驱动进度/阶段/角色/场景，complete 写入剧本', async () => {
    const store = useAppStore()
    store.startSession('sess-1', { name: 'huozhe.txt' })
    const w = mount(ProgressView)

    expect(mockOpen).toHaveBeenCalledWith('sess-1', expect.any(Object))
    const handlers = mockOpen.mock.calls[0][1]

    handlers.onProgress({ percent: 56 })
    handlers.onStage({ stage: 'analyze', status: 'running' })
    handlers.onCharacter({ id: 'C1', name: '福贵', aliases: ['我', '老爷'] })
    handlers.onScene({ scene_id: 'S1', slug: '内·书房·夜' })
    handlers.onScene({ scene_id: 'S1', slug: '内·书房·夜' }) // 重复 → 去重
    await nextTick()

    expect(w.find('.pct').text()).toBe('56%')
    expect(w.find('.eta').text()).toContain('1 角色')
    expect(w.find('.eta').text()).toContain('1 场景')
    // 第二步「理解」进行中；第一步被兜底标记完成
    const steps = w.findAll('.step')
    expect(steps[0].classes()).toContain('done')
    expect(steps[1].classes()).toContain('active')

    handlers.onComplete({ meta: { title: '《活着》改编' }, scenes: [] })
    await flushPromises()
    expect(store.screenplay).toEqual({ meta: { title: '《活着》改编' }, scenes: [] })
    expect(w.find('.head h1').text()).toContain('生成完成')
  })

  it('error 事件标红并提示返回', async () => {
    const store = useAppStore()
    store.startSession('sess-2', { name: 'x.txt' })
    const w = mount(ProgressView)
    const handlers = mockOpen.mock.calls[0][1]
    handlers.onError({ message: '会话不存在' })
    await nextTick()
    expect(w.find('.head h1').text()).toContain('生成出错')
    expect(w.text()).toContain('生成中断')
  })
})
