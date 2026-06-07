import { describe, it, expect, vi, beforeEach } from 'vitest'

// 用 hoisted mock 捕获 axios 实例的 get/post，验证各 API 的请求路径与负载。
const { mockGet, mockPost } = vi.hoisted(() => ({ mockGet: vi.fn(), mockPost: vi.fn() }))
vi.mock('axios', () => ({
  default: { create: () => ({ get: mockGet, post: mockPost }) },
}))

import { createSession, chatRefine, validateYaml, fetchScreenplay, streamUrl } from './http'

describe('api/http', () => {
  beforeEach(() => {
    mockGet.mockReset()
    mockPost.mockReset()
  })

  it('createSession 提交 snake_case 负载（含 requirements）并返回 session_id', async () => {
    mockPost.mockResolvedValue({ data: { session_id: 'sess-9' } })
    const id = await createSession({
      text: '正文',
      language: 'zh',
      title: 'novel.txt',
      requirements: '突出悬疑',
    })
    expect(id).toBe('sess-9')
    expect(mockPost).toHaveBeenCalledWith('/generate', {
      sample_id: undefined,
      text: '正文',
      language: 'zh',
      title: 'novel.txt',
      requirements: '突出悬疑',
    })
  })

  it('chatRefine 向 /chat 提交 screenplay/message/history/language 并返回响应体', async () => {
    const resp = { reply: '好的', screenplay: { meta: {} }, changed: true, valid: true }
    mockPost.mockResolvedValue({ data: resp })
    const sp = { meta: { title: 'x' }, scenes: [] }
    const out = await chatRefine({
      screenplay: sp,
      message: '把 S2 改得更紧张',
      history: [{ role: 'assistant', content: 'hi' }],
      language: 'zh',
    })
    expect(out).toEqual(resp)
    expect(mockPost).toHaveBeenCalledWith(
      '/chat',
      {
        screenplay: sp,
        message: '把 S2 改得更紧张',
        history: [{ role: 'assistant', content: 'hi' }],
        language: 'zh',
      },
      { timeout: 300000 },
    )
  })

  it('validateYaml 向 /validate 提交 yaml', async () => {
    mockPost.mockResolvedValue({ data: { valid: true } })
    const r = await validateYaml('meta:\n  title: x')
    expect(r.valid).toBe(true)
    expect(mockPost).toHaveBeenCalledWith('/validate', { yaml: 'meta:\n  title: x' })
  })

  it('fetchScreenplay 走 /screenplay/{id}', async () => {
    mockGet.mockResolvedValue({ data: { meta: {} } })
    await fetchScreenplay('abc')
    expect(mockGet).toHaveBeenCalledWith('/screenplay/abc')
  })

  it('streamUrl 拼出 SSE 地址', () => {
    expect(streamUrl('abc')).toBe('/api/generate/abc/stream')
  })
})
