import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { openGeneration, streamChat } from './sse'

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

describe('streamChat（fetch 流式读取 SSE）', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('解析 token/done 事件，token 拼成完整回复', async () => {
    const frames =
      'event:token\ndata:{"text":"已把"}\n\n' +
      'event:token\ndata:{"text":"S1 改紧张"}\n\n' +
      'event:done\ndata:{"reply":"已把S1改紧张","changed":true,"screenplay":{"meta":{}}}\n\n'
    const enc = new TextEncoder()
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      body: new ReadableStream({
        start(c) {
          c.enqueue(enc.encode(frames))
          c.close()
        },
      }),
    })
    const tokens = []
    let done = null
    await streamChat({ message: 'x' }, { onToken: (t) => tokens.push(t), onDone: (d) => (done = d) })
    expect(tokens.join('')).toBe('已把S1 改紧张')
    expect(done).toMatchObject({ reply: '已把S1改紧张', changed: true })
    expect(global.fetch).toHaveBeenCalledWith('/api/chat/stream', expect.objectContaining({ method: 'POST' }))
  })

  it('HTTP 错误时回调 onError', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: false, status: 502, body: null })
    const onError = vi.fn()
    await streamChat({ message: 'x' }, { onError })
    expect(onError).toHaveBeenCalled()
  })
})
