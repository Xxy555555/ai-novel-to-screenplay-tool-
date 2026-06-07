import { describe, it, expect } from 'vitest'
import { extractReplyFromPartial } from './replyExtract'

// 从流式累积的「信封原始文本」里增量提取 reply 字段（可能尚未闭合）。
describe('extractReplyFromPartial', () => {
  it('完整信封：提取完整 reply', () => {
    const raw = '{"reply":"已把 S1 改得更紧张。","screenplay":{"meta":{}}}'
    expect(extractReplyFromPartial(raw)).toBe('已把 S1 改得更紧张。')
  })

  it('未闭合：提取到目前为止的 reply', () => {
    const raw = '{"reply":"已把 S1 改'
    expect(extractReplyFromPartial(raw)).toBe('已把 S1 改')
  })

  it('reply 内含转义引号正确解码', () => {
    const raw = '{"reply":"改了\\"标题\\"。","screenplay":{}}'
    expect(extractReplyFromPartial(raw)).toBe('改了"标题"。')
  })

  it('reply 内含换行转义解码', () => {
    const raw = '{"reply":"第一行\\n第二行"}'
    expect(extractReplyFromPartial(raw)).toBe('第一行\n第二行')
  })

  it('尚无 reply 字段时返回空串', () => {
    expect(extractReplyFromPartial('{"scr')).toBe('')
    expect(extractReplyFromPartial('')).toBe('')
  })
})
