import { defineStore } from 'pinia'

// 多线程对话历史：按 sessionId 分桶持久化到 localStorage（后端无库，前端存储最合适）。
// 所有线程共用工作台当前剧本，切线程只换消息记录，不动剧本本身。
const keyOf = (sid) => `sf:chat:${sid}`

// 优先用 crypto.randomUUID，环境缺失时退回时间戳+随机串（happy-dom 测试兼容）。
function uid() {
  try {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID()
  } catch (_) {
    /* ignore */
  }
  return 't' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8)
}

function loadBucket(sid) {
  if (!sid) return { activeThreadId: null, threads: [] }
  try {
    const raw = localStorage.getItem(keyOf(sid))
    if (raw) {
      const parsed = JSON.parse(raw)
      if (parsed && Array.isArray(parsed.threads)) {
        return { activeThreadId: parsed.activeThreadId || null, threads: parsed.threads }
      }
    }
  } catch (_) {
    /* 损坏数据按空处理 */
  }
  return { activeThreadId: null, threads: [] }
}

export const useChatStore = defineStore('chat', {
  state: () => ({
    sessionId: null,
    threads: [], // [{ id, title, messages:[{role,content,seed?}], createdAt, updatedAt }]
    activeThreadId: null,
  }),
  getters: {
    activeThread: (s) => s.threads.find((t) => t.id === s.activeThreadId) || null,
    // 当前线程的消息列表（无活动线程时为空数组，供模板安全 v-for）。
    messages: (s) => {
      const t = s.threads.find((x) => x.id === s.activeThreadId)
      return t ? t.messages : []
    },
  },
  actions: {
    persist() {
      if (!this.sessionId) return // 无会话（如单测）不落盘
      try {
        localStorage.setItem(
          keyOf(this.sessionId),
          JSON.stringify({ activeThreadId: this.activeThreadId, threads: this.threads }),
        )
      } catch (_) {
        /* 配额/隐私模式失败时忽略 */
      }
    },
    // 进入工作台时按 sessionId 载入历史；无历史则建一个空线程。返回活动线程。
    loadThreads(sessionId) {
      this.sessionId = sessionId || null
      const b = loadBucket(this.sessionId)
      this.threads = b.threads
      const ok = b.activeThreadId && this.threads.some((t) => t.id === b.activeThreadId)
      this.activeThreadId = ok ? b.activeThreadId : this.threads[0]?.id || null
      if (!this.activeThreadId) this.newThread()
      return this.activeThread
    },
    newThread(title) {
      const now = Date.now()
      const t = { id: uid(), title: title || '新对话', messages: [], createdAt: now, updatedAt: now }
      this.threads.unshift(t) // 最新置顶
      this.activeThreadId = t.id
      this.persist()
      return t
    },
    switchThread(id) {
      if (this.threads.some((t) => t.id === id)) {
        this.activeThreadId = id
        this.persist()
      }
    },
    deleteThread(id) {
      const i = this.threads.findIndex((t) => t.id === id)
      if (i < 0) return
      this.threads.splice(i, 1)
      if (this.activeThreadId === id) this.activeThreadId = this.threads[0]?.id || null
      if (!this.activeThreadId) this.newThread()
      else this.persist()
    },
    appendMessage(role, content, seed = false) {
      const t = this.activeThread
      if (!t) return
      t.messages.push({ role, content, seed })
      t.updatedAt = Date.now()
      // 线程标题默认取首条用户消息（截断）。
      if (role === 'user' && (!t.title || t.title === '新对话')) {
        const s = (content || '').trim()
        t.title = s.slice(0, 18) + (s.length > 18 ? '…' : '')
      }
      this.persist()
    },
    // 更新当前线程最后一条 assistant 消息内容（流式逐字刷新用）。
    updateLastAssistant(content) {
      const t = this.activeThread
      if (!t || !t.messages.length) return
      const last = t.messages[t.messages.length - 1]
      if (last.role === 'assistant') {
        last.content = content
        t.updatedAt = Date.now()
        this.persist()
      }
    },
    renameThread(id, title) {
      const t = this.threads.find((x) => x.id === id)
      if (t) {
        t.title = title
        this.persist()
      }
    },
  },
})
