import { streamUrl } from './http'

/**
 * 打开生成过程的 SSE 流，把后端事件分发给回调。返回 EventSource（调用方负责在
 * 完成/离开时 close()）。后端事件名与负载（均为 JSON 字符串）：
 *  - stage     { stage, status }
 *  - progress  { percent }
 *  - log       { level, message }
 *  - character { ...Character }     （角色卡 snake_case）
 *  - alias     { alias, into }
 *  - scene     { scene_id, slug }
 *  - complete  { ...Screenplay }    （最终剧本）
 *  - error     { message }
 *
 * @param {string} sessionId
 * @param {object} handlers onStage/onProgress/onLog/onCharacter/onAlias/onScene/onComplete/onError
 * @returns {EventSource}
 */
export function openGeneration(sessionId, handlers = {}) {
  const es = new EventSource(streamUrl(sessionId))

  const bind = (name, fn) => {
    if (!fn) return
    es.addEventListener(name, (e) => {
      let data = {}
      try {
        data = JSON.parse(e.data)
      } catch (_) {
        data = e.data
      }
      fn(data)
    })
  }

  bind('stage', handlers.onStage)
  bind('progress', handlers.onProgress)
  bind('log', handlers.onLog)
  bind('character', handlers.onCharacter)
  bind('alias', handlers.onAlias)
  bind('scene', handlers.onScene)
  bind('complete', (data) => {
    if (handlers.onComplete) handlers.onComplete(data)
    es.close()
  })
  bind('error', (data) => {
    if (handlers.onError) handlers.onError(data)
    es.close()
  })

  // 网络层错误（连接中断等）。
  es.onerror = () => {
    if (handlers.onError) handlers.onError({ message: '与服务器的连接中断' })
    es.close()
  }

  return es
}

/** 解析一个 SSE 帧（event: / data: 多行），分发到回调。 */
function dispatchFrame(frame, handlers) {
  let event = 'message'
  const dataLines = []
  for (const line of frame.split('\n')) {
    const l = line.replace(/\r$/, '')
    if (l.startsWith('event:')) event = l.slice(6).trim()
    else if (l.startsWith('data:')) dataLines.push(l.slice(5).replace(/^ /, ''))
  }
  if (!dataLines.length) return
  let data = {}
  try {
    data = JSON.parse(dataLines.join('\n'))
  } catch (_) {
    data = {}
  }
  if (event === 'token') handlers.onToken && handlers.onToken(data.text || '')
  else if (event === 'done') handlers.onDone && handlers.onDone(data)
  else if (event === 'error') handlers.onError && handlers.onError(data)
}

/**
 * 流式对话精修：POST 大请求体（含整本剧本）并以 fetch + ReadableStream 读取 SSE。
 * 因 EventSource 仅支持 GET，这里用 fetch 流式读取。事件：token{text} / done{ChatResponse} / error{message}。
 *
 * @param {object} payload { screenplay, message, history, language }
 * @param {object} handlers { onToken, onDone, onError }
 */
export async function streamChat(payload, handlers = {}) {
  let resp
  try {
    resp = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
  } catch (e) {
    handlers.onError && handlers.onError({ message: e.message || '连接失败' })
    return
  }
  if (!resp.ok || !resp.body) {
    handlers.onError && handlers.onError({ message: 'HTTP ' + (resp.status || '错误') })
    return
  }
  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buf = ''
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      let idx
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const frame = buf.slice(0, idx)
        buf = buf.slice(idx + 2)
        if (frame.trim()) dispatchFrame(frame, handlers)
      }
    }
    if (buf.trim()) dispatchFrame(buf, handlers)
  } catch (e) {
    handlers.onError && handlers.onError({ message: e.message || '读取中断' })
  }
}
