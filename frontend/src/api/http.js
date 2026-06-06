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
 * @returns {Promise<string>} session_id
 */
export function createSession({ sampleId, text, language, title } = {}) {
  return http
    .post('/generate', { sample_id: sampleId, text, language, title })
    .then((r) => r.data.session_id)
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
