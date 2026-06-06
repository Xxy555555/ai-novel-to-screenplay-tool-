import { createRouter, createWebHistory } from 'vue-router'

// 主动线：P1 首页/上传 → P2 生成进度 → P3 工作台。
const routes = [
  { path: '/', name: 'home', component: () => import('@/views/HomeView.vue') },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router
