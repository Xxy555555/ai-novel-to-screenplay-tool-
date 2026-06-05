import { createRouter, createWebHistory } from 'vue-router'

// 路由骨架：业务页面（P2 进度 / P3 工作台 等）实现时在此扩展。
const routes = [
  {
    path: '/',
    name: 'home',
    component: () => import('@/views/HomeView.vue'),
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router
