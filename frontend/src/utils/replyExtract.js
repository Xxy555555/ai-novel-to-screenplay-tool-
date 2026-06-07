// 从流式累积的「{reply, screenplay} 信封原始文本」里增量提取 reply 字段的值。
// 用于流式对话：模型逐 token 吐出整个信封 JSON，但前端只想实时显示其中的 reply。
// 兼容 reply 尚未闭合（仍在生成）的情况——返回到目前为止已解码的内容。

const HEX = /[0-9a-fA-F]/

export function extractReplyFromPartial(raw) {
  if (!raw) return ''
  const key = '"reply"'
  let i = raw.indexOf(key)
  if (i < 0) return ''
  i += key.length
  // 跳过空白与冒号
  while (i < raw.length && (raw[i] === ' ' || raw[i] === ':' || raw[i] === '\t' || raw[i] === '\n')) i++
  if (raw[i] !== '"') return '' // 还没到字符串起始引号
  i++ // 跳过起始引号
  let out = ''
  while (i < raw.length) {
    const c = raw[i]
    if (c === '\\') {
      const n = raw[i + 1]
      if (n === undefined) break // 转义未完整，停在此处
      if (n === 'n') out += '\n'
      else if (n === 't') out += '\t'
      else if (n === 'r') out += '\r'
      else if (n === 'b') out += '\b'
      else if (n === 'f') out += '\f'
      else if (n === '/') out += '/'
      else if (n === '"') out += '"'
      else if (n === '\\') out += '\\'
      else if (n === 'u') {
        const hex = raw.slice(i + 2, i + 6)
        if (hex.length === 4 && [...hex].every((h) => HEX.test(h))) {
          out += String.fromCharCode(parseInt(hex, 16))
          i += 6
          continue
        }
        break // \u 序列未完整
      } else out += n
      i += 2
      continue
    }
    if (c === '"') break // 字符串结束（reply 已闭合）
    out += c
    i++
  }
  return out
}
