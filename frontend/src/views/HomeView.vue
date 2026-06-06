<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { createSession } from '@/api/http'
import { useAppStore } from '@/stores/app'

const router = useRouter()
const appStore = useAppStore()
const message = useMessage()

/* ---------------- 输入选择（上传文件 / 内置示例，二选一） ---------------- */

// 内置示例：data-sample 与后端一致（huozhe / gift）。
const samples = [
  { id: 'huozhe', name: 'huozhe-excerpt.txt', desc: '中文 · 12,480 字 · 检测到 3 章', label: '《活着》节选', tag: '中文·3章' },
  { id: 'gift', name: 'the-gift.txt', desc: 'English · 9,210 words · 3 chapters detected', label: 'The Gift', tag: 'EN·3章' },
]

const selectedSample = ref(null) // 'huozhe' | 'gift' | null
const fileText = ref('') // 上传文件的纯文本内容
const fileName = ref('') // 上传文件名（作为 title）
// 已选中后用于「filled」区展示：{ name, desc, ok, note }
const filled = ref(null)
const zoneError = ref('') // 上传校验红字（仅空态可见，同原型）
const submitError = ref('') // 开始生成失败的红字
const dragging = ref(false)
const submitting = ref(false)

const fileInput = ref(null)

const canStart = computed(() => !!filled.value && !submitting.value)

// 即时 UX 启发式：仅用于展示语言/字数/章节估计，不阻止提交（真正校验在后端）。
function analyzeText(text) {
  const cjk = (text.match(/[一-龥]/g) || []).length
  const isZh = cjk > 0 && cjk > text.length * 0.15
  const count = isZh
    ? text.replace(/\s+/g, '').length
    : text.trim().split(/\s+/).filter(Boolean).length
  const chapterRe = /(^|\n)\s*(第\s*[0-9一二三四五六七八九十百零两]+\s*[章回节卷]|chapter\s+\d+)/gi
  const chapters = (text.match(chapterRe) || []).length
  return { isZh, count, chapters }
}

// 编码自适应读取：优先按 UTF-8 严格解码；中文 txt 常为 GBK/GB2312（非法 UTF-8），回退 GB18030。
async function readTextSmart(file) {
  const buf = await file.arrayBuffer()
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(buf)
  } catch (_) {
    try {
      return new TextDecoder('gb18030').decode(buf)
    } catch (_2) {
      return new TextDecoder('utf-8').decode(buf) // 兜底：非严格 UTF-8
    }
  }
}

async function handleFile(f) {
  submitError.value = ''
  if (!/\.txt$/i.test(f.name)) {
    filled.value = null
    selectedSample.value = null
    zoneError.value = '仅支持 .txt 纯文本文件。'
    return
  }
  let text = ''
  try {
    text = await readTextSmart(f)
  } catch (_) {
    zoneError.value = '读取文件失败，请重试。'
    return
  }
  zoneError.value = ''
  // 上传与示例互斥：选了文件就清掉示例选中态。
  selectedSample.value = null
  fileText.value = text
  fileName.value = f.name

  const { isZh, count, chapters } = analyzeText(text)
  const unit = isZh ? '字' : 'words'
  const lang = isZh ? '中文' : 'English'
  const num = count.toLocaleString('en-US')
  const ok = chapters >= 3
  filled.value = {
    name: f.name,
    desc: `${lang} · ${num} ${unit} · 检测到 ${chapters} 章`,
    ok,
    note: ok ? '章节结构有效' : '已就绪 · 章节结构将由服务器校验',
  }
}

function pickSample(s) {
  submitError.value = ''
  zoneError.value = ''
  selectedSample.value = s.id
  fileText.value = ''
  fileName.value = ''
  if (fileInput.value) fileInput.value.value = ''
  filled.value = { name: s.name, desc: s.desc, ok: true, note: '章节结构有效' }
}

function reset() {
  filled.value = null
  selectedSample.value = null
  fileText.value = ''
  fileName.value = ''
  zoneError.value = ''
  submitError.value = ''
  if (fileInput.value) fileInput.value.value = ''
}

/* ---------------- 拖拽区交互 ---------------- */

function onZoneClick() {
  if (!filled.value && fileInput.value) fileInput.value.click()
}
function onZoneKeydown(e) {
  if ((e.key === 'Enter' || e.key === ' ') && !filled.value) {
    e.preventDefault()
    fileInput.value?.click()
  }
}
function onDragOver() {
  if (!filled.value) dragging.value = true
}
function onDragLeave() {
  dragging.value = false
}
function onDrop(e) {
  dragging.value = false
  const f = e.dataTransfer?.files?.[0]
  if (f) handleFile(f)
}
function onFileChange(e) {
  const f = e.target.files?.[0]
  if (f) handleFile(f)
}

/* ---------------- 模型选择器（展示/演示用途，不发往后端） ---------------- */

const providerOptions = [
  'stub（离线演示·无需 Key）',
  'OpenAI',
  'DeepSeek',
  'Anthropic',
  '本地 Ollama',
]
const BASE = {
  'stub（离线演示·无需 Key）': 'https://api.stub.local/v1',
  OpenAI: 'https://api.openai.com/v1',
  DeepSeek: 'https://api.deepseek.com/v1',
  Anthropic: 'https://api.anthropic.com/v1',
  '本地 Ollama': 'http://localhost:11434/v1',
}
const provider = ref(providerOptions[0])
const baseUrl = ref(BASE[providerOptions[0]])
const modelId = ref('scriptforge-stub-1')
const apiKey = ref('')
const modelOpen = ref(false)
const modelRef = ref(null)

const modelName = computed(() =>
  provider.value.includes('stub') ? 'stub' : provider.value.split('（')[0],
)

watch(provider, (p) => {
  baseUrl.value = BASE[p] || ''
})

function onDocClick(e) {
  if (modelRef.value && !modelRef.value.contains(e.target)) modelOpen.value = false
}
onMounted(() => document.addEventListener('click', onDocClick))
onBeforeUnmount(() => document.removeEventListener('click', onDocClick))

/* ---------------- 语言与开始生成 ---------------- */

const language = ref('auto')

async function onStart() {
  if (!canStart.value) return
  submitError.value = ''
  submitting.value = true
  try {
    let id
    if (selectedSample.value) {
      id = await createSession({ sampleId: selectedSample.value, language: language.value })
    } else {
      id = await createSession({
        text: fileText.value,
        language: language.value,
        title: fileName.value,
      })
    }
    appStore.startSession(id, {
      name: filled.value.name,
      sampleId: selectedSample.value,
      language: language.value,
      model: modelName.value,
    })
    router.push('/progress')
  } catch (err) {
    const msg = err?.response?.data?.message || '创建会话失败，请稍后重试。'
    submitError.value = msg
    message.error(msg)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="home-page">
    <header class="top">
      <div class="brand"><span class="logo">◧</span>ScriptForge<small>小说 → 剧本</small></div>
      <div class="spacer"></div>
      <div ref="modelRef" class="model">
        <button class="model-btn" aria-haspopup="true" @click.stop="modelOpen = !modelOpen">
          <span class="dot"></span>模型 <b>{{ modelName }}</b>
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 9l6 6 6-6" /></svg>
        </button>
        <div class="model-pop" :class="{ open: modelOpen }">
          <h4>通用模型适配器</h4>
          <div class="field">
            <label>Provider</label>
            <select v-model="provider">
              <option v-for="p in providerOptions" :key="p" :value="p">{{ p }}</option>
            </select>
          </div>
          <div class="field"><label>Base URL</label><input v-model="baseUrl" /></div>
          <div class="field"><label>Model</label><input v-model="modelId" /></div>
          <div class="field">
            <label>API Key</label>
            <input v-model="apiKey" type="password" placeholder="sk-····（stub 模式可留空）" />
          </div>
          <p class="pop-note">
            默认 <b style="color: var(--text-2)">stub</b> 模式无需 API Key，即可走完整流程演示。切换 provider
            后会带入对应 Base URL 模板。
          </p>
        </div>
      </div>
    </header>

    <main>
      <div class="eyebrow">AI 小说改编工作台</div>
      <h1>把你的小说，<br />一键变成<span class="hl">可拍摄的剧本</span>初稿</h1>
      <p class="sub">
        小说 → 结构化剧本 (YAML)<span class="pipe">·</span>跨章角色一致<span class="pipe">·</span>场景卡片可视化编辑
      </p>

      <div
        class="zone"
        :class="{ drag: dragging, filled: !!filled, 'err-on': !!zoneError }"
        tabindex="0"
        role="button"
        aria-label="上传小说文件"
        @click="onZoneClick"
        @keydown="onZoneKeydown"
        @dragenter.prevent="onDragOver"
        @dragover.prevent="onDragOver"
        @dragleave.prevent="onDragLeave"
        @drop.prevent="onDrop"
      >
        <div class="empty">
          <div class="ico">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M12 16V4M7 9l5-5 5 5" /><path d="M4 16v3a1 1 0 001 1h14a1 1 0 001-1v-3" /></svg>
          </div>
          <h3>将小说 <u>.txt</u> 拖到这里，或点击上传</h3>
          <p>建议 ≥ 3 章 · 支持中文 / English · 自动按章节标记切分</p>
          <div class="err">{{ zoneError }}</div>
        </div>
        <div class="filemeta">
          <span class="fi">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" /><path d="M14 2v6h6" /></svg>
          </span>
          <div>
            <div class="fn">{{ filled?.name }}</div>
            <div class="fd">{{ filled?.desc }}</div>
            <div class="chk" :class="{ note: filled && !filled.ok }">
              <svg v-if="filled?.ok" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M20 6L9 17l-5-5" /></svg>
              {{ filled?.note }}
            </div>
          </div>
          <button class="reset" @click.stop="reset">更换</button>
        </div>
      </div>
      <input ref="fileInput" type="file" accept=".txt,text/plain" @change="onFileChange" />

      <div class="samples">
        <span class="lbl">或试试内置示例：</span>
        <button
          v-for="s in samples"
          :key="s.id"
          class="chip"
          :class="{ on: selectedSample === s.id }"
          @click="pickSample(s)"
        >
          {{ s.label }} <span class="tag">{{ s.tag }}</span>
        </button>
      </div>

      <div class="actions">
        <div class="lang">
          语言：
          <select v-model="language">
            <option value="auto">自动检测</option>
            <option value="zh">中文</option>
            <option value="en">English</option>
          </select>
        </div>
        <button class="start" :disabled="!canStart" @click="onStart">
          <svg viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z" /></svg>
          {{ submitting ? '正在创建会话…' : '开始生成剧本' }}
        </button>
      </div>
      <p v-if="submitError" class="submit-err">{{ submitError }}</p>

      <div class="feats">
        <div class="feat">
          <div class="ft"><span class="d"></span>过程可见</div>
          <p>章节切分、角色识别、场景生成逐步流式呈现，AI 不是黑盒。</p>
        </div>
        <div class="feat">
          <div class="ft"><span class="d"></span>结构即编辑</div>
          <p>场景卡片与 YAML 双向同步，改任一侧另一侧即时刷新。</p>
        </div>
        <div class="feat">
          <div class="ft"><span class="d"></span>可信可溯源</div>
          <p>每个场景可回看原文片段，质量分量化改编完整度。</p>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.home-page {
  min-height: 100%;
  display: flex;
  flex-direction: column;
  background-image: radial-gradient(1200px 700px at 50% -10%, rgba(232, 179, 73, 0.07), transparent 60%);
}
.top {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px 28px;
  border-bottom: 1px solid var(--border);
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 700;
  letter-spacing: 0.2px;
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
  box-shadow: 0 0 0 1px rgba(232, 179, 73, 0.3);
}
.brand small {
  color: var(--muted);
  font-weight: 500;
  font-size: 12px;
  border-left: 1px solid var(--border);
  padding-left: 10px;
  margin-left: 2px;
}
.spacer {
  flex: 1;
}
.model {
  position: relative;
}
.model-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  background: var(--raised);
  border: 1px solid var(--border);
  color: var(--text-2);
  padding: 8px 12px;
  border-radius: var(--r-ctl);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  transition: 0.15s;
}
.model-btn:hover {
  border-color: var(--border-strong);
  color: var(--text);
}
.model-btn .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 8px var(--accent);
}
.model-btn b {
  color: var(--text);
  font-weight: 600;
}
.model-pop {
  position: absolute;
  right: 0;
  top: 46px;
  width: 320px;
  background: var(--raised);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-card);
  padding: 16px;
  box-shadow: 0 18px 50px rgba(0, 0, 0, 0.55);
  z-index: 40;
  display: none;
  text-align: left;
}
.model-pop.open {
  display: block;
}
.model-pop h4 {
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.14em;
  color: var(--muted);
  margin-bottom: 12px;
  font-weight: 600;
}
.field {
  margin-bottom: 11px;
}
.field label {
  display: block;
  font-size: 11px;
  color: var(--text-2);
  margin-bottom: 5px;
}
.field input,
.field select {
  width: 100%;
  background: var(--inset);
  border: 1px solid var(--border);
  color: var(--text);
  border-radius: var(--r-ctl);
  padding: 8px 10px;
  font: inherit;
  font-size: 13px;
}
.field input:focus,
.field select:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft);
}
.pop-note {
  font-size: 11px;
  color: var(--muted);
  margin-top: 6px;
  line-height: 1.45;
}
main {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 24px 64px;
  text-align: center;
}
.eyebrow {
  font-family: var(--mono);
  font-size: 12px;
  letter-spacing: 0.22em;
  text-transform: uppercase;
  color: var(--accent);
  margin-bottom: 20px;
}
h1 {
  font-size: clamp(30px, 5vw, 52px);
  line-height: 1.08;
  font-weight: 700;
  letter-spacing: -0.02em;
  max-width: 16ch;
  margin: 0 auto;
}
h1 .hl {
  color: var(--accent);
}
.sub {
  margin: 20px auto 0;
  max-width: 54ch;
  color: var(--text-2);
  font-size: clamp(15px, 2vw, 17px);
}
.sub .pipe {
  color: var(--muted);
  margin: 0 8px;
}
.zone {
  width: min(720px, 100%);
  margin: 40px auto 0;
  background: var(--surface);
  border: 1.5px dashed var(--border-strong);
  border-radius: 16px;
  padding: 44px 32px;
  cursor: pointer;
  transition: 0.18s;
  position: relative;
  text-align: center;
}
.zone:hover {
  border-color: var(--accent);
  background: #171d26;
}
.zone.drag {
  border-color: var(--accent);
  background: rgba(232, 179, 73, 0.06);
  box-shadow: 0 0 0 4px var(--accent-soft);
}
.zone.filled {
  border-style: solid;
  border-color: var(--success);
  background: rgba(63, 185, 80, 0.05);
  cursor: default;
}
.zone .ico {
  width: 46px;
  height: 46px;
  margin: 0 auto 16px;
  color: var(--accent);
}
.zone .ico svg {
  width: 100%;
  height: 100%;
}
.zone h3 {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 6px;
}
.zone h3 u {
  color: var(--accent);
  text-decoration: none;
  border-bottom: 1.5px solid var(--accent);
}
.zone p {
  color: var(--muted);
  font-size: 13px;
}
.zone .err {
  color: var(--danger);
  font-size: 13px;
  margin-top: 12px;
  display: none;
}
.zone.err-on .err {
  display: block;
}
.filemeta {
  display: none;
  align-items: center;
  gap: 14px;
  justify-content: center;
  text-align: left;
}
.zone.filled .filemeta {
  display: flex;
}
.zone.filled .empty {
  display: none;
}
.filemeta .fi {
  width: 40px;
  height: 40px;
  border-radius: 9px;
  background: var(--accent-soft);
  display: grid;
  place-items: center;
  color: var(--accent);
  flex: none;
}
.filemeta .fn {
  font-weight: 600;
}
.filemeta .fd {
  color: var(--text-2);
  font-size: 13px;
}
.filemeta .chk {
  color: var(--success);
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 2px;
}
.filemeta .chk.note {
  color: var(--warning);
}
.reset {
  background: none;
  border: 1px solid var(--border);
  color: var(--text-2);
  border-radius: var(--r-ctl);
  padding: 7px 12px;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  margin-left: 6px;
}
.reset:hover {
  border-color: var(--border-strong);
  color: var(--text);
}
.samples {
  margin: 26px auto 0;
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: center;
  align-items: center;
}
.samples .lbl {
  color: var(--muted);
  font-size: 13px;
}
.chip {
  background: var(--raised);
  border: 1px solid var(--border);
  color: var(--text);
  border-radius: 999px;
  padding: 9px 16px;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  transition: 0.15s;
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.chip:hover {
  border-color: var(--accent);
  color: var(--accent);
}
.chip.on {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
}
.chip .tag {
  font-family: var(--mono);
  font-size: 11px;
  color: var(--muted);
}
.chip.on .tag {
  color: var(--accent);
}
.actions {
  margin: 34px auto 0;
  display: flex;
  gap: 14px;
  align-items: center;
  flex-wrap: wrap;
  justify-content: center;
}
.lang {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-2);
  font-size: 13px;
}
.lang select {
  background: var(--raised);
  border: 1px solid var(--border);
  color: var(--text);
  border-radius: var(--r-ctl);
  padding: 9px 12px;
  font: inherit;
  font-size: 13px;
}
.start {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  background: var(--accent);
  color: #0e1116;
  border: none;
  border-radius: var(--r-ctl);
  padding: 13px 26px;
  font: inherit;
  font-size: 15px;
  font-weight: 700;
  cursor: pointer;
  transition: 0.15s;
  box-shadow: 0 8px 24px rgba(232, 179, 73, 0.22);
}
.start:hover {
  background: var(--accent-hover);
}
.start:disabled {
  background: var(--raised);
  color: var(--muted);
  cursor: not-allowed;
  box-shadow: none;
}
.start svg {
  width: 17px;
  height: 17px;
}
.submit-err {
  margin: 14px auto 0;
  color: var(--danger);
  font-size: 13px;
}
.feats {
  display: flex;
  gap: 34px;
  flex-wrap: wrap;
  justify-content: center;
  margin-top: 54px;
  border-top: 1px solid var(--border);
  padding-top: 30px;
  width: min(820px, 100%);
}
.feat {
  max-width: 230px;
  text-align: center;
}
.feat .ft {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 4px;
  display: flex;
  align-items: center;
  gap: 7px;
  justify-content: center;
}
.feat .ft .d {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
}
.feat p {
  color: var(--muted);
  font-size: 12.5px;
  line-height: 1.5;
}
input[type='file'] {
  display: none;
}
@media (max-width: 640px) {
  .top {
    padding: 14px 18px;
  }
  .brand small {
    display: none;
  }
  main {
    padding: 36px 18px 48px;
  }
  .zone {
    padding: 34px 20px;
  }
  .feats {
    gap: 22px;
  }
}
</style>
