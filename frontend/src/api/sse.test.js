import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { openGeneration } from './sse'

// 用假 EventSource 验证 openGeneration 的事件绑定、JSON 解析与自动 close。
class FakeEventSource {
  constructor(url) {
    this.url = url
    this.listeners = {}
    this.closed = false
    FakeEventSource.last = this
  }
  addEventListener(name, fn) {
    ;(this.listeners[name] ||= []).push(fn)
  }
  emit(name, data) {
    ;(this.listeners[name] || []).forEach((fn) => fn({ data }))
  }
  close() {
    this.closed = true
  }
}

describe('api/sse openGeneration', () => {
  beforeEach(() => {
    globalThis.EventSource = FakeEventSource
  })
  afterEach(() => {
    delete globalThis.EventSource
  })

  it('用 streamUrl 打开连接并把事件分发给回调（JSON 解析）', () => {
    const onStage = vi.fn()
    const onCharacter = vi.fn()
    openGeneration('sess-1', { onStage, onCharacter })
    const es = FakeEventSource.last
    expect(es.url).toBe('/api/generate/sess-1/stream')

    es.emit('stage', JSON.stringify({ stage: 'analyze', status: 'running' }))
    expect(onStage).toHaveBeenCalledWith({ stage: 'analyze', status: 'running' })

    es.emit('character', JSON.stringify({ id: 'C1', name: '福贵' }))
    expect(onCharacter).toHaveBeenCalledWith({ id: 'C1', name: '福贵' })
  })

  it('非 JSON 负载回退为原始字符串', () => {
    const onLog = vi.fn()
    openGeneration('s', { onLog })
    FakeEventSource.last.emit('log', 'plain text')
    expect(onLog).toHaveBeenCalledWith('plain text')
  })

  it('complete 触发回调并关闭连接', () => {
    const onComplete = vi.fn()
    openGeneration('s', { onComplete })
    const es = FakeEventSource.last
    es.emit('complete', JSON.stringify({ meta: { title: 'x' } }))
    expect(onComplete).toHaveBeenCalledWith({ meta: { title: 'x' } })
    expect(es.closed).toBe(true)
  })

  it('error 触发回调并关闭连接', () => {
    const onError = vi.fn()
    openGeneration('s', { onError })
    const es = FakeEventSource.last
    es.emit('error', JSON.stringify({ message: '会话不存在' }))
    expect(onError).toHaveBeenCalledWith({ message: '会话不存在' })
    expect(es.closed).toBe(true)
  })
})
