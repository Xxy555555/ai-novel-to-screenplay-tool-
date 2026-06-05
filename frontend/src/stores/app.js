import { defineStore } from 'pinia'

/**
 * 应用级状态骨架（业务实现时填充：当前剧本、生成进度、质量报告等）。
 */
export const useAppStore = defineStore('app', {
  state: () => ({
    // screenplay: null,
    // progress: null,
    // qualityReport: null,
  }),
  actions: {},
})
