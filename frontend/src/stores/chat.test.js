import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useChatStore } from './chat'

// 多线程对话历史：按 sessionId 分桶持久化到 localStorage；切线程只换消息。
describe('useChatStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('loadThreads 无历史时建一个空线程并设为活动', () => {
    const c = useChatStore()
    c.loadThreads('sess-1')
    expect(c.threads.length).toBe(1)
    expect(c.activeThreadId).toBe(c.threads[0].id)
    expect(c.messages).toEqual([])
  })

  it('appendMessage 写入当前线程，标题取首条用户消息（截断）', () => {
    const c = useChatStore()
    c.loadThreads('sess-1')
    const long = '把 S2 改得更紧张一些用来测试标题截断逻辑'
    c.appendMessage('assistant', '开场白', true) // seed 不作为标题
    c.appendMessage('user', long)
    expect(c.messages).toHaveLength(2)
    expect(c.activeThread.title).toBe(long.slice(0, 18) + '…')
  })

  it('newThread / switchThread：可来回切换且互不干扰', () => {
    const c = useChatStore()
    c.loadThreads('sess-1')
    const t1 = c.activeThreadId
    c.appendMessage('user', '线程一消息')
    const t2 = c.newThread().id
    c.appendMessage('user', '线程二消息')
    expect(c.messages.map((m) => m.content)).toEqual(['线程二消息'])
    c.switchThread(t1)
    expect(c.activeThreadId).toBe(t1)
    expect(c.messages.map((m) => m.content)).toEqual(['线程一消息'])
    expect(t1).not.toBe(t2)
  })

  it('deleteThread：删除后切到其余线程，删光则自动新建空线程', () => {
    const c = useChatStore()
    c.loadThreads('sess-1')
    const t1 = c.activeThreadId
    const t2 = c.newThread().id
    c.deleteThread(t2)
    expect(c.activeThreadId).toBe(t1)
    expect(c.threads.length).toBe(1)
    c.deleteThread(t1)
    // 删光后保证至少有一个线程
    expect(c.threads.length).toBe(1)
    expect(c.activeThreadId).toBe(c.threads[0].id)
  })

  it('持久化到 localStorage（按 sessionId 分桶），重载可恢复', () => {
    const c = useChatStore()
    c.loadThreads('sess-A')
    c.appendMessage('user', '需要被持久化的消息')
    const raw = localStorage.getItem('sf:chat:sess-A')
    expect(raw).toBeTruthy()
    expect(raw).toContain('需要被持久化的消息')

    // 新的 store 实例（模拟刷新）从同一 sessionId 恢复
    setActivePinia(createPinia())
    const c2 = useChatStore()
    c2.loadThreads('sess-A')
    expect(c2.messages.map((m) => m.content)).toContain('需要被持久化的消息')
  })

  it('不同 sessionId 互相隔离', () => {
    const c = useChatStore()
    c.loadThreads('sess-A')
    c.appendMessage('user', 'A 的消息')
    c.loadThreads('sess-B')
    expect(c.messages.map((m) => m.content)).not.toContain('A 的消息')
  })

  it('无 sessionId（如单测）不落盘但仍可用', () => {
    const c = useChatStore()
    c.loadThreads(null)
    c.appendMessage('user', '临时消息')
    expect(c.messages.map((m) => m.content)).toContain('临时消息')
    expect(localStorage.length).toBe(0)
  })
})
