import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAppStore } from './app'

// Pinia 应用级状态：会话/来源/剧本，及 sessionId 的 localStorage 持久化。
describe('useAppStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('startSession 写入 sessionId/source 并持久化到 localStorage', () => {
    const store = useAppStore()
    const source = { name: 'huozhe.txt', sampleId: 'huozhe', language: 'zh', model: 'stub', requirements: '突出悬疑' }
    store.startSession('sess-1', source)
    expect(store.sessionId).toBe('sess-1')
    expect(store.source).toEqual(source)
    expect(store.source.requirements).toBe('突出悬疑')
    expect(store.screenplay).toBeNull()
    expect(localStorage.getItem('sf:sessionId')).toBe('sess-1')
  })

  it('startSession 会清空上一次的 screenplay', () => {
    const store = useAppStore()
    store.setScreenplay({ meta: { title: 'x' } })
    expect(store.screenplay).not.toBeNull()
    store.startSession('sess-2', { name: 'a' })
    expect(store.screenplay).toBeNull()
  })

  it('setScreenplay 设置剧本对象', () => {
    const store = useAppStore()
    const sp = { meta: { title: '《活着》改编' }, scenes: [] }
    store.setScreenplay(sp)
    // Pinia 会把对象包成 reactive 代理，故用深比较而非引用相等。
    expect(store.screenplay).toEqual(sp)
  })
})
