<script setup>
import { darkTheme } from 'naive-ui'

// 全局主题覆盖：注入琥珀金强调色（对应设计令牌）
const themeOverrides = {
  common: {
    primaryColor: '#E8B349',
    primaryColorHover: '#F2C45E',
    primaryColorPressed: '#D9A43A',
    bodyColor: '#0E1116',
    cardColor: '#161B22',
    borderColor: '#2A3441',
  },
}
</script>

<template>
  <n-config-provider :theme="darkTheme" :theme-overrides="themeOverrides">
    <n-message-provider>
      <n-dialog-provider>
        <div class="app-shell">
          <header class="app-header">
            <span class="brand">◧ ScriptForge</span>
            <span class="tagline">AI 小说转剧本工具 · 框架骨架</span>
          </header>
          <main class="app-main">
            <router-view />
          </main>
        </div>
      </n-dialog-provider>
    </n-message-provider>
  </n-config-provider>
</template>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.app-header {
  height: 56px;
  display: flex;
  align-items: center;
  gap: var(--space-4);
  padding: 0 var(--space-6);
  border-bottom: 1px solid var(--border-default);
  background: var(--bg-surface);
}
.brand {
  font-weight: 600;
  color: var(--accent-primary);
}
.tagline {
  color: var(--text-secondary);
  font-size: 12px;
}
.app-main {
  flex: 1;
  overflow: auto;
}
</style>
