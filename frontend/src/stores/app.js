import { defineStore } from 'pinia'

/**
 * 应用级状态：当前生成会话与剧本结果。
 *
 * - sessionId / source：首页发起生成后写入，进度页据此打开 SSE；
 * - screenplay：生成完成后写入（也可由工作台用 sessionId 重新拉取），供工作台渲染；
 * - sessionId 持久化到 localStorage，刷新工作台时可凭它重新拉取剧本。
 */
export const useAppStore = defineStore('app', {
  state: () => ({
    sessionId: localStorage.getItem('sf:sessionId') || null,
    source: null, // { name, sampleId, language, model }
    screenplay: null,
  }),
  actions: {
    startSession(sessionId, source) {
      this.sessionId = sessionId
      this.source = source
      this.screenplay = null
      if (sessionId) localStorage.setItem('sf:sessionId', sessionId)
    },
    setScreenplay(sp) {
      this.screenplay = sp
    },
  },
})
