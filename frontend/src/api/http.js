import axios from 'axios'

// Axios 基础实例（开发期 /api 由 Vite 代理到后端 8080）。
const http = axios.create({
  baseURL: '/api',
  timeout: 180000,
})

// 框架自检：探测后端健康端点。
export function fetchHealth() {
  return http.get('/health').then((r) => r.data)
}

/** 内置示例列表。 */
export function fetchSamples() {
  return http.get('/samples').then((r) => r.data)
}

/**
 * 创建生成会话（不立即生成）。请求体为 snake_case，与后端 Jackson 一致。
 * @param {object} p
 * @param {string} [p.requirements] 用户上传时提出的改编需求（自由文本，可空）
 * @returns {Promise<string>} session_id
 */
export function createSession({ sampleId, text, language, title, requirements } = {}) {
  return http
    .post('/generate', { sample_id: sampleId, text, language, title, requirements })
    .then((r) => r.data.session_id)
}

/**
 * 多轮对话精修：把当前剧本 + 本轮消息 + 历史发给后端，返回
 * { reply, screenplay, changed, valid, error_count, errors }。
 * @param {object} p
 * @param {object} p.screenplay 当前工作区剧本（snake_case 对象）
 * @param {string} p.message    本轮用户消息
 * @param {Array<{role:string,content:string}>} [p.history] 历史（不含本轮）
 * @param {string} [p.language] 语言
 */
export function chatRefine({ screenplay, message, history, language } = {}) {
  return http.post('/chat', { screenplay, message, history, language }).then((r) => r.data)
}

/** SSE 流地址（交给 EventSource / openGeneration 使用，走 Vite 代理）。 */
export function streamUrl(sessionId) {
  return `/api/generate/${sessionId}/stream`
}

/** 取最终剧本（生成完成后可用）。 */
export function fetchScreenplay(sessionId) {
  return http.get(`/screenplay/${sessionId}`).then((r) => r.data)
}

/** 取剧本 YAML 文本。 */
export function fetchYaml(sessionId) {
  return http.get(`/screenplay/${sessionId}/yaml`, { responseType: 'text' }).then((r) => r.data)
}

/** 取 Fountain 风格剧本文本。 */
export function fetchFountain(sessionId) {
  return http.get(`/screenplay/${sessionId}/fountain`, { responseType: 'text' }).then((r) => r.data)
}

/** 重校验：把当前 YAML 发给后端，返回 { valid, error_count, errors, report }。 */
export function validateYaml(yaml) {
  return http.post('/validate', { yaml }).then((r) => r.data)
}

export default http
