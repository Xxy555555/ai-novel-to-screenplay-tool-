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
