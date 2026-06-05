<script setup>
import { ref, onMounted } from 'vue'
import { fetchHealth } from '@/api/http'

// 仅用于验证前后端联通的占位首页（非业务页面）。
const backend = ref({ state: 'checking', detail: '正在探测后端…' })

onMounted(async () => {
  try {
    const data = await fetchHealth()
    backend.value = { state: 'up', detail: `后端已连通：${data.app} v${data.version}` }
  } catch (e) {
    backend.value = { state: 'down', detail: '后端未连通（请先启动 backend，端口 8080）' }
  }
})
</script>

<template>
  <div class="home">
    <n-card class="panel" title="脚手架就绪 · Scaffold Ready">
      <p class="muted">前后端框架已搭好，业务代码尚未实现。</p>
      <ul class="checklist">
        <li>✅ 前端：Vue3 + Vite + Pinia + Vue Router + Naive UI（深色主题）</li>
        <li>✅ 后端：Spring Boot 3 + 分层包结构 + CORS/UTF-8 配置</li>
        <li>✅ 设计令牌：docs/UI-Prototype-Design.md 第 2 节</li>
      </ul>

      <n-divider />

      <div class="conn">
        <n-tag :type="backend.state === 'up' ? 'success' : backend.state === 'down' ? 'error' : 'warning'">
          {{ backend.state === 'up' ? '● 已连通' : backend.state === 'down' ? '● 未连通' : '● 探测中' }}
        </n-tag>
        <span class="muted">{{ backend.detail }}</span>
      </div>
    </n-card>
  </div>
</template>

<style scoped>
.home {
  display: flex;
  justify-content: center;
  padding: var(--space-8);
}
.panel {
  max-width: 640px;
  width: 100%;
}
.muted {
  color: var(--text-secondary);
}
.checklist {
  line-height: 2;
  padding-left: var(--space-4);
}
.conn {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
</style>
