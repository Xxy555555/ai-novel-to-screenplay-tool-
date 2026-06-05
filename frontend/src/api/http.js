import axios from 'axios'

// Axios 基础实例（开发期 /api 由 Vite 代理到后端 8080）。
const http = axios.create({
  baseURL: '/api',
  timeout: 180000,
})

// 框架自检：探测后端健康端点。业务接口实现时在此模块扩展。
export function fetchHealth() {
  return http.get('/health').then((r) => r.data)
}

export default http
