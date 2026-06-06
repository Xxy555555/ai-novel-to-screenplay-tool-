<script setup>
import { darkTheme } from 'naive-ui'

// 全局主题覆盖：注入琥珀金强调色（对应设计令牌）。
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
        <router-view />
      </n-dialog-provider>
    </n-message-provider>
  </n-config-provider>
</template>
