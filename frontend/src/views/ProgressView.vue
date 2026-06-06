<script setup>
import { ref, computed, reactive, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { useAppStore } from '@/stores/app'
import { openGeneration } from '@/api/sse'

const router = useRouter()
const store = useAppStore()
const message = useMessage()

// ---- 顶栏：作品名（来自上传时写入的 source） -------------------------------
const projectLabel = computed(() => store.source?.name || '剧本生成')

// ---- 总进度 ---------------------------------------------------------------
const pct = ref(0)
function setPct(p) {
  pct.value = Math.min(100, Math.max(0, Math.round(Number(p) || 0)))
}

// ---- 四阶段 StageStepper ---------------------------------------------------
// stage(split|analyze|compose|validate) → 0..3
const STAGE_INDEX = { split: 0, analyze: 1, compose: 2, validate: 3 }
const steps = reactive([
  { title: '章节切分', status: 'pending' },
  { title: '理解 · 角色圣经', status: 'pending' },
  { title: '生成剧本', status: 'pending' },
  { title: '质量校验', status: 'pending' },
])
function setStep(k, status) {
  if (k < 0 || k >= steps.length) return
  // 进入某步（进行中/完成/失败）时，把它之前尚未结束的步骤兜底标记为完成，
  // 即使个别 done 事件丢失，StageStepper 也不会停留在错误状态。
  if (status === 'active' || status === 'done' || status === 'failed') {
    for (let i = 0; i < k; i++) {
      if (steps[i].status === 'pending' || steps[i].status === 'active') steps[i].status = 'done'
    }
  }
  steps[k].status = status
}

// ---- 实时日志 -------------------------------------------------------------
let logId = 0
const logs = ref([])
const logBox = ref(null)
const LOG_KINDS = new Set(['ok', 'run', 'info', 'warn'])
function pushLog(level, parts) {
  const kind = LOG_KINDS.has(level) ? level : 'info'
  logs.value.push({
    id: ++logId,
    kind,
    parts: Array.isArray(parts) ? parts : [{ t: String(parts ?? ''), cls: '' }],
  })
  nextTick(() => {
    const el = logBox.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

// ---- 已发现角色（角色圣经实时增长，按 id 去重） ----------------------------
const chars = ref([])
const charIndex = new Map()
function aliasText(c) {
  if (Array.isArray(c.aliases) && c.aliases.length) return '别名：' + c.aliases.join(' / ')
  return c.role || c.description || ''
}
function upsertChar(c) {
  if (!c) return
  const key = c.id ?? c.name
  if (key == null) return
  const card = {
    key,
    name: c.name || String(key),
    alias: aliasText(c),
  }
  card.initial = card.name.charAt(0)
  if (charIndex.has(key)) {
    chars.value[charIndex.get(key)] = card
  } else {
    charIndex.set(key, chars.value.length)
    chars.value.push(card)
  }
}
const nChar = computed(() => chars.value.length)

// ---- 已发现场景计数（按 scene_id 去重） -----------------------------------
const sceneIds = new Set()
const nScene = ref(0)

// ---- 页面阶段状态 ----------------------------------------------------------
const finished = ref(false) // 生成完成
const errored = ref(false) // 出错
const headTitle = computed(() =>
  errored.value ? '生成出错' : finished.value ? '生成完成' : '正在生成剧本…',
)

// ---- SSE 生命周期 ----------------------------------------------------------
let es = null
let navTimer = null
let alive = true

const handlers = {
  onStage({ stage, status }) {
    const k = STAGE_INDEX[stage]
    if (k == null) return
    if (status === 'running') setStep(k, 'active')
    else if (status === 'done') setStep(k, 'done')
    else if (status === 'failed') setStep(k, 'failed')
  },
  onProgress({ percent }) {
    setPct(percent)
  },
  onLog({ level, message: msg }) {
    pushLog(level, msg)
  },
  onCharacter(c) {
    upsertChar(c)
  },
  onAlias({ alias, into }) {
    pushLog('info', [
      { t: '归并别名 ', cls: '' },
      { t: alias, cls: 'mono' },
      { t: ' → ', cls: '' },
      { t: into, cls: 'mono' },
    ])
  },
  onScene({ scene_id, slug }) {
    if (scene_id != null && sceneIds.has(scene_id)) return
    if (scene_id != null) sceneIds.add(scene_id)
    nScene.value = scene_id != null ? sceneIds.size : nScene.value + 1
    if (slug) {
      pushLog('ok', [{ t: '切出场景 ', cls: '' }, { t: slug, cls: 'mono' }])
    }
  },
  onComplete(screenplay) {
    if (!alive) return
    setPct(100)
    steps.forEach((s) => {
      if (s.status !== 'failed') s.status = 'done'
    })
    store.setScreenplay(screenplay)
    finished.value = true
    pushLog('ok', '生成完成 · 即将进入工作台')
    message.success('生成完成')
    navTimer = setTimeout(() => {
      if (alive) router.push('/workbench')
    }, 800)
  },
  onError({ message: msg }) {
    if (!alive) return
    errored.value = true
    // 把当前进行中的阶段标红（若无进行中阶段，则标红第一个未完成阶段）。
    let k = steps.findIndex((s) => s.status === 'active')
    if (k < 0) k = steps.findIndex((s) => s.status !== 'done')
    if (k >= 0) steps[k].status = 'failed'
    pushLog('warn', msg || '生成出错')
    message.error(msg || '生成出错')
  },
}

onMounted(() => {
  const sessionId = store.sessionId
  if (!sessionId) {
    router.replace('/')
    return
  }
  es = openGeneration(sessionId, handlers)
})

onBeforeUnmount(() => {
  alive = false
  if (navTimer) clearTimeout(navTimer)
  // openGeneration 在 complete/error 已自动 close，这里兜底确保连接关闭。
  if (es) es.close()
})

// ---- 操作 ------------------------------------------------------------------
function cancel() {
  alive = false
  if (navTimer) clearTimeout(navTimer)
  if (es) es.close()
  router.push('/')
}
function goWorkbench() {
  if (navTimer) clearTimeout(navTimer)
  router.push('/workbench')
}
function backHome() {
  if (es) es.close()
  router.push('/')
}
</script>

<template>
  <div class="progress-page">
    <header class="top">
      <div class="brand">
        <span class="logo">◧</span>ScriptForge<small>{{ projectLabel }}</small>
      </div>
      <div class="spacer"></div>
      <button class="cancel" @click="cancel">取消生成</button>
    </header>

    <main>
      <div class="head">
        <h1>{{ headTitle }}</h1>
        <span class="pct">{{ pct }}%</span>
      </div>
      <div class="bar"><div class="fill" :style="{ width: pct + '%' }"></div></div>
      <div class="eta">已发现 {{ nChar }} 角色 · {{ nScene }} 场景</div>

      <!-- StageStepper -->
      <div class="steps">
        <div
          v-for="(s, i) in steps"
          :key="i"
          class="step"
          :class="{ active: s.status === 'active', done: s.status === 'done', failed: s.status === 'failed' }"
        >
          <div class="n">
            <span class="badge">{{ i + 1 }}</span>
            <span class="ti">{{ s.title }}</span>
          </div>
          <div class="st">
            <template v-if="s.status === 'active'"><span class="spin"></span> 进行中</template>
            <template v-else-if="s.status === 'done'">✓ 完成</template>
            <template v-else-if="s.status === 'failed'">✗ 失败</template>
            <template v-else>待处理</template>
          </div>
          <div class="line" :style="{ width: s.status === 'done' ? '100%' : '0' }"></div>
        </div>
      </div>

      <!-- 双面板：实时日志 / 已发现角色 -->
      <div class="panels">
        <div class="panel">
          <h3>实时日志 <span class="c">SSE</span></h3>
          <div class="log" ref="logBox">
            <div v-for="l in logs" :key="l.id" class="logrow" :class="l.kind">
              <span class="ic">
                <span v-if="l.kind === 'run'" class="spin"></span>
                <svg
                  v-else-if="l.kind === 'ok'"
                  width="15"
                  height="15"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2.3"
                >
                  <path d="M20 6L9 17l-5-5" />
                </svg>
                <svg
                  v-else
                  width="15"
                  height="15"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                >
                  <circle cx="12" cy="12" r="9" />
                  <path d="M12 8v4l3 2" />
                </svg>
              </span>
              <span class="tx">
                <template v-for="(p, i) in l.parts" :key="i">
                  <b v-if="p.cls === 'b'">{{ p.t }}</b>
                  <span v-else-if="p.cls === 'mono'" class="mono">{{ p.t }}</span>
                  <template v-else>{{ p.t }}</template>
                </template>
              </span>
            </div>
          </div>
        </div>

        <div class="panel">
          <h3>已发现角色 <span class="c">{{ nChar }}</span></h3>
          <div class="chars">
            <div v-for="c in chars" :key="c.key" class="charrow">
              <span class="av">{{ c.initial }}</span>
              <div>
                <div class="nm">{{ c.name }}</div>
                <div class="al">{{ c.alias }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 底部状态 / 操作 -->
      <div class="foot">
        <template v-if="errored">
          <span class="err">生成中断，请返回重试。</span>
          <button class="go-btn" @click="backHome">返回上传</button>
        </template>
        <template v-else-if="finished">
          <span>生成完成！正在进入工作台…</span>
          <a class="go show" @click="goWorkbench"
            >进入工作台
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M5 12h14M13 6l6 6-6 6" />
            </svg>
          </a>
        </template>
        <template v-else>
          <span>完成后将自动进入工作台 · 你也可以稍候手动进入</span>
        </template>
      </div>
    </main>
  </div>
</template>

<style scoped>
.progress-page {
  min-height: 100%;
  display: flex;
  flex-direction: column;
}
.top {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 28px;
  border-bottom: 1px solid var(--border);
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 700;
}
.logo {
  width: 26px;
  height: 26px;
  border-radius: 7px;
  background: linear-gradient(135deg, var(--accent), #b07d1e);
  display: grid;
  place-items: center;
  color: #0e1116;
  font-weight: 800;
  font-size: 15px;
}
.brand small {
  color: var(--muted);
  font-weight: 500;
  font-size: 12px;
  border-left: 1px solid var(--border);
  padding-left: 10px;
}
.top .spacer {
  flex: 1;
}
.cancel {
  background: none;
  border: 1px solid var(--border);
  color: var(--text-2);
  border-radius: 6px;
  padding: 8px 14px;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
.cancel:hover {
  border-color: var(--danger);
  color: var(--danger);
}
main {
  flex: 1;
  width: min(1080px, 100%);
  margin: 0 auto;
  padding: 40px 28px 56px;
}
.head {
  display: flex;
  align-items: baseline;
  gap: 14px;
  flex-wrap: wrap;
}
.head h1 {
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.01em;
}
.head .pct {
  font-family: var(--mono);
  font-size: 24px;
  color: var(--accent);
  font-weight: 500;
  margin-left: auto;
}
.bar {
  height: 8px;
  background: var(--inset);
  border-radius: 999px;
  margin: 18px 0 6px;
  overflow: hidden;
  border: 1px solid var(--border);
}
.bar .fill {
  height: 100%;
  width: 0;
  background: linear-gradient(90deg, #b07d1e, var(--accent));
  border-radius: 999px;
  transition: width 0.5s ease;
}
.eta {
  font-size: 12px;
  color: var(--muted);
  font-family: var(--mono);
}
/* stepper */
.steps {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin: 30px 0;
}
.step {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 16px 16px 15px;
  position: relative;
  overflow: hidden;
  transition: 0.25s;
}
.step.active {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft);
}
.step.done {
  border-color: rgba(63, 185, 80, 0.4);
}
.step.failed {
  border-color: rgba(248, 81, 73, 0.5);
  box-shadow: 0 0 0 3px rgba(248, 81, 73, 0.12);
}
.step .n {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-bottom: 9px;
}
.step .badge {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 12px;
  font-weight: 700;
  font-family: var(--mono);
  background: var(--raised);
  color: var(--muted);
  border: 1px solid var(--border);
  flex: none;
}
.step.active .badge {
  background: var(--accent);
  color: #0e1116;
  border-color: var(--accent);
}
.step.done .badge {
  background: rgba(63, 185, 80, 0.16);
  color: var(--success);
  border-color: rgba(63, 185, 80, 0.4);
}
.step.failed .badge {
  background: rgba(248, 81, 73, 0.16);
  color: var(--danger);
  border-color: rgba(248, 81, 73, 0.5);
}
.step .ti {
  font-weight: 600;
  font-size: 14px;
}
.step .st {
  font-size: 12px;
  color: var(--muted);
  display: flex;
  align-items: center;
  gap: 6px;
}
.step.active .st {
  color: var(--accent);
}
.step.done .st {
  color: var(--success);
}
.step.failed .st {
  color: var(--danger);
}
.spin {
  width: 11px;
  height: 11px;
  border: 2px solid var(--accent);
  border-top-color: transparent;
  border-radius: 50%;
  animation: sp 0.7s linear infinite;
  display: inline-block;
}
@keyframes sp {
  to {
    transform: rotate(360deg);
  }
}
.step .line {
  position: absolute;
  left: 0;
  bottom: 0;
  height: 2px;
  width: 0;
  background: var(--accent);
  transition: width 0.4s;
}
.step.failed .line {
  background: var(--danger);
}
/* panels */
.panels {
  display: grid;
  grid-template-columns: 1.5fr 1fr;
  gap: 18px;
}
.panel {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  min-height: 340px;
}
.panel h3 {
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.12em;
  color: var(--text-2);
  padding: 14px 16px;
  border-bottom: 1px solid var(--border);
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
}
.panel h3 .c {
  font-family: var(--mono);
  color: var(--muted);
  margin-left: auto;
  font-size: 12px;
}
.log {
  padding: 8px 0;
  overflow-y: auto;
  flex: 1;
  max-height: 360px;
}
.logrow {
  display: flex;
  gap: 10px;
  padding: 7px 16px;
  font-size: 13px;
  animation: in 0.3s ease;
  align-items: flex-start;
}
@keyframes in {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
.logrow .ic {
  flex: none;
  margin-top: 1px;
  display: inline-flex;
}
.logrow.ok .ic {
  color: var(--success);
}
.logrow.run .ic {
  color: var(--accent);
}
.logrow.info .ic {
  color: var(--info);
}
.logrow.warn .ic {
  color: var(--warning);
}
.logrow .tx {
  color: var(--text-2);
}
.logrow .tx b {
  color: var(--text);
  font-weight: 600;
}
.logrow .tx .mono {
  font-family: var(--mono);
  color: var(--accent);
  font-size: 12px;
}
.chars {
  padding: 8px;
  overflow-y: auto;
  flex: 1;
  max-height: 360px;
}
.charrow {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 9px 10px;
  border-radius: 8px;
  animation: in 0.35s ease;
}
.charrow:hover {
  background: var(--raised);
}
.av {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-weight: 700;
  font-size: 13px;
  flex: none;
  background: var(--accent-soft);
  color: var(--accent);
  border: 1px solid rgba(232, 179, 73, 0.3);
}
.charrow .nm {
  font-weight: 600;
  font-size: 14px;
}
.charrow .al {
  font-size: 12px;
  color: var(--muted);
}
.foot {
  text-align: center;
  margin-top: 30px;
  color: var(--muted);
  font-size: 13px;
}
.foot .err {
  color: var(--danger);
  margin-right: 12px;
}
.foot .go {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--accent);
  font-weight: 600;
  cursor: pointer;
  margin-left: 12px;
  animation: in 0.4s;
}
.foot .go-btn {
  background: none;
  border: 1px solid var(--border);
  color: var(--accent);
  border-radius: 6px;
  padding: 7px 14px;
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.foot .go-btn:hover {
  border-color: var(--accent);
}
@media (max-width: 860px) {
  .steps {
    grid-template-columns: repeat(2, 1fr);
  }
  .panels {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 560px) {
  .top {
    padding: 14px 18px;
  }
  main {
    padding: 28px 18px 40px;
  }
  .head h1 {
    font-size: 20px;
  }
}
</style>
