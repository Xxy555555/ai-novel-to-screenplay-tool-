<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import jsyaml from 'js-yaml'
import { useAppStore } from '@/stores/app'
import { fetchScreenplay, validateYaml, chatRefine } from '@/api/http'

const router = useRouter()
const store = useAppStore()

const TYPE_LABEL = { action: '动作', dialogue: '对白', voiceover: '画外音 V.O.', transition: '转场', montage: '蒙太奇' }
const TYPE_VAR = { action: 'el-action', dialogue: 'el-dialogue', voiceover: 'el-voiceover', transition: 'el-transition', montage: 'el-montage' }
const EL_TYPES = ['action', 'dialogue', 'voiceover', 'transition', 'montage']

const data = ref(null) // 响应式剧本（后端 snake_case 形状）
const viewMode = ref('cards') // cards | yaml
const yamlText = ref('')
const selScene = ref('')
const activeTab = ref('char') // char | qual | chat
const charSearch = ref('')
const dimChar = ref('') // 点击角色高亮其对白
const valid = ref(true)
const validText = ref('合法')
const drawerOpen = ref(false)
const exportOpen = ref(false)
const modelOpen = ref(false)
const leftOpen = ref(false) // 窄屏：场景大纲抽屉
const rightOpen = ref(false) // 窄屏：角色/质量抽屉

// ───────── AI 多轮对话精修 ─────────
const chatMessages = ref([]) // { role:'user'|'assistant', content, seed? }
const chatInput = ref('')
const chatBusy = ref(false)
const chatScroll = ref(null)

// ───────── 载入 ─────────
onMounted(async () => {
  let sp = store.screenplay
  if (!sp && store.sessionId) {
    try {
      sp = await fetchScreenplay(store.sessionId)
    } catch (_) {
      sp = null
    }
  }
  if (!sp) {
    router.replace('/')
    return
  }
  data.value = reactive(normalize(sp))
  selScene.value = data.value.scenes[0]?.id || ''
  seedChat()
  document.addEventListener('click', onDocClick)
})

// 对话开场白：介绍能力，并在用户上传时填过需求时回显之。
function seedChat() {
  const req = store.source?.requirements
  let hello = '你好！我是剧本精修助手。直接用自然语言告诉我想怎么改，例如：'
    + '「把 S2 改得更紧张」「给主角加一句画外音」「标题改为《活着》」「删除 S4」。'
    + '我会修改剧本、自动校验，并同步到左侧卡片与 YAML。'
  if (req && req.trim()) {
    hello = '已收到你在上传时填写的改编需求：「' + req.trim() + '」。\n\n' + hello
  }
  chatMessages.value = [{ role: 'assistant', content: hello, seed: true }]
}
onBeforeUnmount(() => document.removeEventListener('click', onDocClick))

function normalize(sp) {
  // 深拷贝并补齐可能缺失的集合，避免模板空引用。
  const clone = JSON.parse(JSON.stringify(sp))
  clone.meta = clone.meta || {}
  clone.characters = clone.characters || []
  clone.scenes = (clone.scenes || []).map((s) => ({
    id: s.id,
    chapter: s.chapter || '',
    heading: s.heading || { int_ext: 'INT', location: '', time_of_day: '' },
    present_characters: s.present_characters || [],
    elements: (s.elements || []).map((e) => ({ ...e })),
    mood: s.mood || '',
    pacing: s.pacing || '',
    shots: s.shots || [],
    source: s.source || '',
  }))
  clone.report = clone.report || null
  return clone
}

// ───────── 计算属性 ─────────
const charMap = computed(() => {
  const m = {}
  for (const c of data.value?.characters || []) m[c.id] = c
  return m
})
const filteredChars = computed(() => {
  const q = charSearch.value.trim()
  const list = data.value?.characters || []
  if (!q) return list
  return list.filter((c) => c.name.includes(q) || (c.aliases || []).join('').includes(q))
})
const report = computed(() => data.value?.report || {})
const score = computed(() => report.value.score ?? 0)
const scoreColor = computed(() => (score.value >= 80 ? 'var(--success)' : score.value >= 60 ? 'var(--accent)' : 'var(--danger)'))
const gutter = computed(() => {
  const n = yamlText.value.split('\n').length
  let g = ''
  for (let i = 1; i <= n; i++) g += i + '\n'
  return g
})
// 中心区只显示「当前选中场景」（对应模板设计）。
const curScene = computed(() => {
  const list = data.value?.scenes || []
  return list.find((s) => s.id === selScene.value) || list[0] || null
})
const sceneIndex = computed(() => {
  const list = data.value?.scenes || []
  return list.findIndex((s) => s.id === (curScene.value?.id)) + 1
})

function charName(id) {
  return charMap.value[id]?.name || id
}
function sceneWarn(s) {
  return !s.heading?.int_ext || !s.heading?.location || !s.heading?.time_of_day
}
function pct(x) {
  return Math.round((x ?? 0) * 100)
}
function isSpoken(e) {
  return e.type === 'dialogue' || e.type === 'voiceover'
}

// ───────── 视图切换 + 双向同步（js-yaml） ─────────
function switchView(v) {
  viewMode.value = v
  if (v === 'yaml') syncYamlFromModel()
}
function syncYamlFromModel() {
  try {
    yamlText.value = jsyaml.dump(plainScreenplay(), { indent: 2, lineWidth: -1, noRefs: true, skipInvalid: true })
  } catch (e) {
    yamlText.value = '# 序列化失败：' + e.message
  }
}
function plainScreenplay() {
  return JSON.parse(JSON.stringify(data.value))
}
let yTimer = null
function onYamlInput() {
  clearTimeout(yTimer)
  yTimer = setTimeout(() => {
    try {
      const obj = jsyaml.load(yamlText.value)
      if (obj && typeof obj === 'object') {
        if (obj.meta) data.value.meta = obj.meta
        if (obj.characters) data.value.characters = obj.characters
        if (Array.isArray(obj.scenes)) data.value.scenes = normalize({ scenes: obj.scenes }).scenes
        if (obj.report) data.value.report = obj.report
        if (!data.value.scenes.find((s) => s.id === selScene.value)) selScene.value = data.value.scenes[0]?.id || ''
        setValid(true)
      } else {
        setValid(false)
      }
    } catch (e) {
      setValid(false)
    }
  }, 380)
}
function setValid(ok) {
  valid.value = ok
  validText.value = ok ? '合法' : '解析失败'
}

// ───────── 卡片编辑 ─────────
function addElement(scene, type) {
  if (type === 'dialogue' || type === 'voiceover') {
    scene.elements.push({ type, character: scene.present_characters[0] || '', line: '（新台词）' })
  } else {
    scene.elements.push({ type, text: type === 'transition' ? 'CUT TO:' : '（新' + TYPE_LABEL[type] + '）' })
  }
}
function dupElement(scene, i) {
  scene.elements.splice(i + 1, 0, JSON.parse(JSON.stringify(scene.elements[i])))
}
function delElement(scene, i) {
  scene.elements.splice(i, 1)
}
function addScene() {
  const ids = data.value.scenes.map((s) => parseInt(String(s.id).replace(/\D/g, ''), 10)).filter((n) => !isNaN(n))
  const next = (ids.length ? Math.max(...ids) : 0) + 1
  const id = 'S' + next
  data.value.scenes.push({
    id,
    chapter: '',
    heading: { int_ext: 'INT', location: '', time_of_day: '' },
    present_characters: [],
    elements: [],
    mood: '',
    pacing: '',
    shots: [],
    source: '',
  })
  selectScene(id)
  toast('已新增场景 ' + id)
}
// AI 建议标注：为当前场景补全镜头/情绪/节奏（演示用，客户端填充）。
function aiSuggest(s) {
  if (!s.shots.length) s.shots = ['中景']
  s.mood = s.mood || '中性'
  s.pacing = s.pacing || '中'
  toast('已为 ' + s.id + ' 生成镜头 / 情绪建议')
}

// 返回初始页面（首页）。
function goHome() {
  router.push('/')
}

// ───────── 联动 ─────────
function selectScene(id) {
  selScene.value = id
  dimChar.value = ''
  if (window.innerWidth <= 900) leftOpen.value = false
  nextTick(() => {
    const c = document.querySelector('.center')
    if (c) c.scrollTop = 0
  })
}
function toggleChar(id) {
  const want = dimChar.value === id ? '' : id
  dimChar.value = want
  if (!want) return
  if (viewMode.value !== 'cards') switchView('cards')
  // 当前场景若无此角色，跳到首个包含该角色的场景。
  const cur = curScene.value
  if (!cur || !cur.present_characters.includes(id)) {
    const hit = data.value.scenes.find((s) => s.present_characters.includes(id))
    if (hit) selScene.value = hit.id
  }
  if (window.innerWidth <= 1200) rightOpen.value = false
}
function locate(sceneId) {
  if (!sceneId) return
  if (viewMode.value !== 'cards') switchView('cards')
  selectScene(sceneId)
  rightOpen.value = false
  toast('已定位到 ' + sceneId)
}

// ───────── 重校验 ─────────
async function revalidate() {
  try {
    const yaml = viewMode.value === 'yaml' ? yamlText.value : jsyaml.dump(plainScreenplay(), { indent: 2, lineWidth: -1, noRefs: true, skipInvalid: true })
    const r = await validateYaml(yaml)
    setValid(r.valid)
    if (r.report) data.value.report = r.report
    toast(r.valid ? '已重校验 · Schema 合法' : '仍有 ' + r.error_count + ' 处错误')
  } catch (e) {
    toast('重校验失败：' + (e.response?.data?.message || e.message))
  }
}

// ───────── AI 对话精修 ─────────
function chatScrollToBottom() {
  nextTick(() => {
    const el = chatScroll.value
    if (el) el.scrollTop = el.scrollHeight
  })
}
async function sendChat() {
  const msg = chatInput.value.trim()
  if (!msg || chatBusy.value) return
  // 历史 = 已有真实对话（排除开场白 seed），不含本轮。
  const history = chatMessages.value
    .filter((m) => !m.seed)
    .map((m) => ({ role: m.role, content: m.content }))
  chatMessages.value.push({ role: 'user', content: msg })
  chatInput.value = ''
  chatBusy.value = true
  chatScrollToBottom()
  try {
    const resp = await chatRefine({
      screenplay: plainScreenplay(),
      message: msg,
      history,
      language: store.source?.language || 'auto',
    })
    chatMessages.value.push({ role: 'assistant', content: resp.reply || '（无回复）' })
    if (resp.changed && resp.screenplay) {
      applyRefined(resp.screenplay)
      setValid(resp.valid !== false)
      toast('AI 已更新剧本' + (resp.valid === false ? '（仍有校验问题）' : ' · Schema 合法'))
    }
  } catch (e) {
    const m = e.response?.data?.message || e.message || '对话失败'
    chatMessages.value.push({ role: 'assistant', content: '出错了：' + m })
    toast('对话失败：' + m)
  } finally {
    chatBusy.value = false
    chatScrollToBottom()
  }
}
// 用 AI 返回的剧本替换工作区，并保持选中场景与视图同步。
function applyRefined(sp) {
  const keep = selScene.value
  data.value = reactive(normalize(sp))
  selScene.value = data.value.scenes.find((s) => s.id === keep)?.id || data.value.scenes[0]?.id || ''
  if (viewMode.value === 'yaml') syncYamlFromModel()
}

// ───────── Fountain 预览（客户端渲染，反映当前编辑） ─────────
const fountainHtml = computed(() => buildFountain())
function buildFountain() {
  const sp = data.value
  if (!sp) return ''
  let html = `<div class="title-pg"><div class="t">${esc(sp.meta.title || '未命名剧本')}</div>`
  const by = sp.meta.source_title || sp.meta.author
  if (by) html += `<div class="by">${esc(by)}</div>`
  html += '</div>'
  for (const s of sp.scenes) {
    const h = s.heading || {}
    html += `<div class="sh">${h.int_ext === 'EXT' ? 'EXT.' : 'INT.'} ${esc(h.location || '?')} - ${esc(h.time_of_day || '?')}</div>`
    for (const e of s.elements) {
      if (e.type === 'action' || e.type === 'montage') html += `<div class="ac">${esc(e.text)}</div>`
      else if (e.type === 'transition') html += `<div class="tr">${esc(e.text)}</div>`
      else {
        let nm = charName(e.character)
        if (e.type === 'voiceover') nm += ' (V.O.)'
        html += `<div class="cue">${esc(nm)}</div>` + (e.parenthetical ? `<div class="par">(${esc(e.parenthetical)})</div>` : '') + `<div class="dlg">${esc(e.line)}</div>`
      }
    }
  }
  return html
}
function fountainText() {
  const sp = data.value
  let t = `Title: ${sp.meta.title || ''}\n`
  if (sp.meta.source_title) t += `Credit: 改编自\nAuthor: ${sp.meta.source_title}\n`
  t += '\n'
  for (const s of sp.scenes) {
    const h = s.heading || {}
    t += `${h.int_ext === 'EXT' ? 'EXT.' : 'INT.'} ${h.location || '?'} - ${h.time_of_day || '?'}\n\n`
    for (const e of s.elements) {
      if (e.type === 'action' || e.type === 'montage') t += e.text + '\n\n'
      else if (e.type === 'transition') t += '> ' + e.text + '\n\n'
      else {
        let nm = charName(e.character)
        if (e.type === 'voiceover') nm += ' (V.O.)'
        t += nm.toUpperCase() + '\n' + (e.parenthetical ? '(' + e.parenthetical + ')\n' : '') + e.line + '\n\n'
      }
    }
  }
  return t
}

// ───────── 导出 ─────────
function currentYaml() {
  return jsyaml.dump(plainScreenplay(), { indent: 2, lineWidth: -1, noRefs: true, skipInvalid: true })
}
function download(name, content) {
  const b = new Blob([content], { type: 'text/plain;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(b)
  a.download = name
  a.click()
  setTimeout(() => URL.revokeObjectURL(a.href), 1000)
}
function copy(t) {
  try {
    navigator.clipboard.writeText(t)
  } catch (_) {
    const ta = document.createElement('textarea')
    ta.value = t
    document.body.appendChild(ta)
    ta.select()
    try {
      document.execCommand('copy')
    } catch (e) {}
    document.body.removeChild(ta)
  }
}
function doExport(kind) {
  exportOpen.value = false
  const base = (data.value.meta.title || 'screenplay').replace(/[《》\s]/g, '') || 'screenplay'
  if (kind === 'yaml') {
    download(base + '.yaml', currentYaml())
    toast('已导出 ' + base + '.yaml')
  } else if (kind === 'fountain') {
    download(base + '.fountain', fountainText())
    toast('已导出 ' + base + '.fountain')
  } else if (kind === 'copy') {
    copy(currentYaml())
    toast('YAML 已复制到剪贴板')
  } else if (kind === 'pdf') {
    drawerOpen.value = true
    toast('PDF：在预览中使用浏览器「打印 / 另存为 PDF」')
  }
}

// ───────── 杂项 ─────────
let toastTimer = null
const toastMsg = ref('')
const toastShow = ref(false)
function toast(m) {
  toastMsg.value = m
  toastShow.value = true
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => (toastShow.value = false), 2200)
}
function esc(s) {
  return (s == null ? '' : String(s)).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}
function onDocClick(e) {
  if (!e.target.closest('.menu')) {
    exportOpen.value = false
    modelOpen.value = false
  }
}
</script>

<template>
  <div class="wb" v-if="data">
    <!-- TOP BAR -->
    <header class="top">
      <button class="pane-toggle left-t" @click="leftOpen = !leftOpen" aria-label="场景大纲">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 6h16M4 12h16M4 18h16" /></svg>
      </button>
      <div class="brand"><span class="logo">◧</span>ScriptForge</div>
      <button class="tb home-btn" @click="goHome" title="返回初始页面">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 11l9-8 9 8M5 10v10a1 1 0 001 1h3v-6h6v6h3a1 1 0 001-1V10" /></svg><span class="lbl">首页</span>
      </button>
      <div class="proj"><b>{{ data.meta.title || '未命名剧本' }}</b> <span class="v">v1</span></div>
      <div class="spacer"></div>

      <div class="menu">
        <button class="tb" @click.stop="modelOpen = !modelOpen">
          <span class="dot"></span><span class="lbl">模型</span> <b>{{ data.meta.generated_by || 'stub' }}</b>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 9l6 6 6-6" /></svg>
        </button>
        <div class="dd model-pop" :class="{ open: modelOpen }">
          <h4>通用模型适配器</h4>
          <div class="field"><label>当前模型</label><input :value="data.meta.generated_by || 'stub'" readonly /></div>
          <p class="pop-note">切换模型只需改后端 <code>scriptforge.llm.*</code>（provider / base-url / model / api-key）或 <code>SCRIPTFORGE_LLM_*</code> 环境变量，支持 OpenAI / DeepSeek / Kimi / GLM / 通义 / 本地 Ollama / 聚合网关，不绑定厂商。</p>
        </div>
      </div>

      <button class="tb" @click="revalidate" title="重校验">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12a9 9 0 11-2.6-6.4M21 3v6h-6" /></svg><span class="lbl">重校验</span>
      </button>
      <button class="tb" :class="{ ok: valid }" :style="!valid ? 'color:var(--danger)' : ''">
        <svg v-if="valid" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M20 6L9 17l-5-5" /></svg>
        <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M12 8v5M12 17h.01M10.3 3.9L2 18a2 2 0 001.7 3h16.6a2 2 0 001.7-3L13.7 3.9a2 2 0 00-3.4 0z" /></svg>
        {{ validText }}
      </button>
      <button class="tb" @click="drawerOpen = true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z" /><circle cx="12" cy="12" r="3" /></svg><span class="lbl">预览</span>
      </button>
      <div class="menu">
        <button class="tb primary" @click.stop="exportOpen = !exportOpen">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 3v12M7 10l5 5 5-5" /><path d="M5 21h14" /></svg>导出
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 9l6 6 6-6" /></svg>
        </button>
        <div class="dd" :class="{ open: exportOpen }">
          <button class="it" @click="doExport('yaml')">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M4 7h16M4 12h16M4 17h10" /></svg>
            <span>YAML（.yaml）<small>结构化、可再编辑</small></span>
          </button>
          <button class="it" @click="doExport('fountain')">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z" /><path d="M14 2v6h6" /></svg>
            <span>剧本文本（.fountain）<small>行业标准排版</small></span>
          </button>
          <button class="it" @click="doExport('pdf')">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M6 2h9l5 5v15H6z" /><path d="M9 13h6M9 17h4" /></svg>
            <span>PDF（排版剧本）<small>打印 / 分享</small></span>
          </button>
          <div class="sep"></div>
          <button class="it" @click="doExport('copy')">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><rect x="9" y="9" width="11" height="11" rx="2" /><path d="M5 15V5a2 2 0 012-2h10" /></svg>
            <span>复制 YAML 到剪贴板</span>
          </button>
        </div>
      </div>
      <div class="ring" title="改编质量分">
        <svg width="38" height="38">
          <circle cx="19" cy="19" r="16" fill="none" stroke="#232c37" stroke-width="3.5" />
          <circle cx="19" cy="19" r="16" fill="none" :stroke="scoreColor" stroke-width="3.5" stroke-linecap="round"
            :stroke-dasharray="100.5" :stroke-dashoffset="100.5 - (100.5 * score) / 100" transform="rotate(-90 19 19)" />
        </svg>
        <span class="num" :style="{ color: scoreColor }">{{ score }}</span>
      </div>
      <button class="pane-toggle right-t" @click="rightOpen = !rightOpen" aria-label="角色与质量">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M16 21v-2a4 4 0 00-4-4H6a4 4 0 00-4 4v2" /><circle cx="9" cy="7" r="4" /><path d="M22 21v-2a4 4 0 00-3-3.87" /></svg>
      </button>
    </header>

    <div class="work">
      <!-- LEFT: outline -->
      <aside class="pane left" :class="{ show: leftOpen }">
        <div class="pane-h">场景大纲 <span class="c">{{ data.scenes.length }}</span></div>
        <div class="outline">
          <button v-for="s in data.scenes" :key="s.id" class="oi" :class="{ sel: s.id === selScene }" @click="selectScene(s.id)">
            <div class="sid">{{ s.id }}<span v-if="sceneWarn(s)" class="warn">⚠</span></div>
            <div class="sslug">{{ s.heading.location || '未命名' }} · {{ s.heading.time_of_day || '—' }}</div>
            <div class="smeta">{{ s.heading.int_ext === 'INT' ? '内景' : '外景' }} · 在场 {{ s.present_characters.length }} 人 · {{ s.elements.length }} 元素</div>
          </button>
        </div>
        <button class="add-scene" @click="addScene">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 5v14M5 12h14" /></svg>新增场景
        </button>
      </aside>

      <!-- CENTER -->
      <main class="pane center">
        <div class="ctool">
          <div class="seg">
            <button :class="{ on: viewMode === 'cards' }" @click="switchView('cards')">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7" rx="1" /><rect x="14" y="3" width="7" height="7" rx="1" /><rect x="3" y="14" width="7" height="7" rx="1" /><rect x="14" y="14" width="7" height="7" rx="1" /></svg>场景卡片
            </button>
            <button :class="{ on: viewMode === 'yaml' }" @click="switchView('yaml')">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M8 6l-6 6 6 6M16 6l6 6-6 6" /></svg>YAML
            </button>
          </div>
          <div class="cur-scene" v-if="curScene">
            <span class="stag">{{ curScene.id }}</span>
            <b>{{ curScene.heading.location || '未命名' }}</b> · {{ curScene.heading.time_of_day || '—' }}
            <span style="color:var(--muted)">({{ sceneIndex }}/{{ data.scenes.length }})</span>
          </div>
          <div class="hint">改任一侧 <span class="k">卡片</span> ⇆ <span class="k">YAML</span> 即时双向同步</div>
        </div>

        <!-- CARDS：只渲染当前选中场景 -->
        <div class="cards" v-show="viewMode === 'cards'" v-if="curScene">
          <div class="scard" :class="{ warn: sceneWarn(curScene), sel: true }" :id="'card-' + curScene.id">
            <div class="sc-head">
              <span class="sidtag">{{ curScene.id }}</span>
              <div class="slug">
                <select class="selie" v-model="curScene.heading.int_ext"><option>INT</option><option>EXT</option></select>
                <input class="seg-loc" :class="{ miss: !curScene.heading.location }" v-model="curScene.heading.location" placeholder="地点?" />·
                <input class="seg-loc" :class="{ miss: !curScene.heading.time_of_day }" v-model="curScene.heading.time_of_day" placeholder="时间?" />
              </div>
              <span class="ch" v-if="curScene.chapter">{{ curScene.chapter }}</span>
            </div>
            <div class="present">
              <span class="lab">在场</span>
              <span v-for="cid in curScene.present_characters" :key="cid" class="cchip"><span class="av">{{ (charName(cid) || '?').charAt(0) }}</span>{{ charName(cid) }}</span>
              <button class="addc" @click="toast('在 YAML 视图中编辑在场角色列表')">+ 添加</button>
            </div>
            <div class="elems">
              <div v-for="(e, i) in curScene.elements" :key="i" class="erow" :class="{ dim: dimChar && e.character !== dimChar, hl: dimChar && e.character === dimChar }">
                <div class="barc" :style="{ background: 'var(--' + TYPE_VAR[e.type] + ')' }"></div>
                <div class="ebody">
                  <div class="etype" :style="{ color: 'var(--' + TYPE_VAR[e.type] + ')' }">
                    {{ TYPE_LABEL[e.type] }}
                    <span v-if="isSpoken(e)" class="who">{{ charName(e.character) }}</span>
                    <span v-if="isSpoken(e) && e.parenthetical" class="paren">（{{ e.parenthetical }}）</span>
                  </div>
                  <input v-if="isSpoken(e)" class="etext" v-model="e.line" />
                  <textarea v-else class="etext ta" v-model="e.text" rows="1"></textarea>
                </div>
                <div class="eact">
                  <button title="复制" @click="dupElement(curScene, i)">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="9" y="9" width="11" height="11" rx="2" /><path d="M5 15V5a2 2 0 012-2h10" /></svg>
                  </button>
                  <button title="删除" @click="delElement(curScene, i)">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6" /></svg>
                  </button>
                </div>
              </div>
            </div>
            <div class="addel">
              <span class="lab">+ 添加元素</span>
              <button v-for="t in EL_TYPES" :key="t" :style="{ color: 'var(--' + TYPE_VAR[t] + ')' }" @click="addElement(curScene, t)">{{ TYPE_LABEL[t] }}</button>
            </div>
            <div class="annot">
              <span class="grp">🎬 分镜</span>
              <template v-if="curScene.shots.length"><span v-for="(sh, i) in curScene.shots" :key="i" class="atag shot">{{ sh }}</span></template>
              <span v-else class="atag" style="color:var(--muted)">—</span>
              <span class="grp">情绪</span><span class="atag mood">{{ curScene.mood || '—' }}</span>
              <span class="grp">节奏</span><span class="atag pace">{{ curScene.pacing || '—' }}</span>
              <button class="ai-sug" @click="aiSuggest(curScene)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 3l1.9 5.8L20 9l-5 3.6L16.8 19 12 15.4 7.2 19 9 12.6 4 9l6.1-.2z" /></svg>AI 建议标注
              </button>
            </div>
            <details class="trace" v-if="curScene.source">
              <summary>
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z" /><circle cx="12" cy="12" r="3" /></svg>原文溯源
              </summary>
              <div class="src">“{{ curScene.source }}”</div>
            </details>
          </div>
        </div>

        <!-- YAML -->
        <div class="yaml-wrap" v-show="viewMode === 'yaml'">
          <div class="yaml-bar">
            <span class="vstat" :class="valid ? 'ok' : 'bad'">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.3"><path v-if="valid" d="M20 6L9 17l-5-5" /><path v-else d="M12 8v5M12 17h.01M10.3 3.9L2 18a2 2 0 001.7 3h16.6a2 2 0 001.7-3L13.7 3.9a2 2 0 00-3.4 0z" /></svg>
              {{ valid ? 'Schema 合法' : '解析失败 · 检查缩进/格式' }}
            </span>
            <span class="sp"></span>
            <button @click="revalidate">重校验</button>
            <button @click="copy(yamlText); toast('YAML 已复制到剪贴板')">复制</button>
          </div>
          <div class="yamled">
            <pre class="gutter">{{ gutter }}</pre>
            <textarea class="yta" spellcheck="false" v-model="yamlText" @input="onYamlInput"></textarea>
          </div>
        </div>
      </main>

      <!-- RIGHT -->
      <aside class="pane right" :class="{ show: rightOpen }" @click.self="rightOpen = false">
        <div class="tabs">
          <button :class="{ on: activeTab === 'char' }" @click="activeTab = 'char'">角色圣经</button>
          <button :class="{ on: activeTab === 'qual' }" @click="activeTab = 'qual'">质量报告</button>
          <button :class="{ on: activeTab === 'chat' }" @click="activeTab = 'chat'">AI 对话</button>
        </div>

        <div class="tabpane char" v-show="activeTab === 'char'">
          <input class="search" v-model="charSearch" placeholder="🔎 搜索角色…" />
          <div class="char-list">
            <div v-for="c in filteredChars" :key="c.id" class="ccard" :class="{ act: dimChar === c.id }" @click="toggleChar(c.id)">
              <div class="ch">
                <span class="av">{{ c.name.charAt(0) }}</span><span class="nm">{{ c.name }}</span>
                <span class="role">{{ c.role }}</span>
              </div>
              <div v-if="c.aliases && c.aliases.length" class="alias"><span v-for="a in c.aliases" :key="a">{{ a }}</span></div>
              <div class="meta" style="margin-top:8px">
                <template v-if="c.tone"><span class="k">口吻：</span>{{ c.tone }}<br /></template>
                <template v-if="c.relations && c.relations.length"><span class="k">关系：</span>{{ c.relations.map((r) => r.target + '=' + r.relation).join(' · ') }}<br /></template>
                <template v-if="c.first_appearance"><span class="k">首次登场：</span>{{ c.first_appearance }}</template>
              </div>
            </div>
          </div>
        </div>

        <div class="tabpane qual" v-show="activeTab === 'qual'">
          <div class="gauge">
            <div class="gw">
              <svg width="118" height="118">
                <circle cx="59" cy="59" r="50" fill="none" stroke="#232c37" stroke-width="8" />
                <circle cx="59" cy="59" r="50" fill="none" :stroke="scoreColor" stroke-width="8" stroke-linecap="round"
                  :stroke-dasharray="314" :stroke-dashoffset="314 - (314 * score) / 100" transform="rotate(-90 59 59)" />
              </svg>
              <div class="gnum"><b :style="{ color: scoreColor }">{{ score }}</b><small>/ 100</small></div>
            </div>
            <div class="glabel">综合评分 · <b style="color:var(--text)">{{ report.grade || '—' }}</b></div>
          </div>
          <div class="qpass" v-if="report.schema_valid"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M20 6L9 17l-5-5" /></svg>Schema 校验通过 · 0 错误</div>
          <div class="qpass bad" v-else><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M12 8v5M12 17h.01M10.3 3.9L2 18a2 2 0 001.7 3h16.6a2 2 0 001.7-3L13.7 3.9a2 2 0 00-3.4 0z" /></svg>Schema 有 {{ report.schema_error_count }} 处错误</div>
          <div class="qrow"><div class="qt">对白说话人覆盖 <span class="qv">{{ pct(report.dialogue_attribution_rate) }}%</span></div><div class="qbar good"><i :style="{ width: pct(report.dialogue_attribution_rate) + '%' }"></i></div></div>
          <div class="qrow"><div class="qt">角色一致性 <span class="qv">{{ pct(report.character_consistency_rate) }}%</span></div><div class="qbar good"><i :style="{ width: pct(report.character_consistency_rate) + '%' }"></i></div></div>
          <div class="qrow"><div class="qt">场景头完整 <span class="qv">{{ pct(report.scene_heading_completeness_rate) }}%</span></div><div class="qbar"><i :style="{ width: pct(report.scene_heading_completeness_rate) + '%' }"></i></div></div>
          <div class="qrow"><div class="qt">演 / 说比 <span class="qv">{{ report.show_vs_tell_ratio }}</span></div><div class="qbar good"><i :style="{ width: Math.min(100, (report.show_vs_tell_ratio || 0) * 100) + '%' }"></i></div></div>
          <div class="qstat"><span>场景 {{ report.scene_count }}</span><span>角色 {{ report.character_count }}</span><span>平均 {{ report.avg_elements_per_scene }} 元素/场</span></div>
          <div class="qtodo" v-if="report.issues && report.issues.length">
            <h5>待改进</h5>
            <div v-for="(it, i) in report.issues" :key="i" class="ti">
              <span class="w">⚠</span>{{ it.message }}
              <button v-if="it.scene_id" class="loc" @click="locate(it.scene_id)">定位</button>
            </div>
          </div>
        </div>

        <div class="tabpane chat" v-show="activeTab === 'chat'">
          <div class="chat-msgs" ref="chatScroll">
            <div v-for="(m, i) in chatMessages" :key="i" class="cmsg" :class="m.role">
              <span class="who" v-if="m.role === 'assistant'">AI</span>
              <div class="bubble">{{ m.content }}</div>
            </div>
            <div v-if="chatBusy" class="cmsg assistant">
              <span class="who">AI</span>
              <div class="bubble typing"><i></i><i></i><i></i></div>
            </div>
          </div>
          <div class="chat-input">
            <textarea
              v-model="chatInput"
              rows="2"
              :disabled="chatBusy"
              placeholder="例如：把 S2 改得更紧张；给主角加一句画外音；标题改为《活着》改编 …（Enter 发送，Shift+Enter 换行）"
              @keydown.enter.exact.prevent="sendChat"
            ></textarea>
            <button class="send" :disabled="chatBusy || !chatInput.trim()" @click="sendChat">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 2L11 13M22 2l-7 20-4-9-9-4 20-7z" /></svg>
              发送
            </button>
          </div>
          <div class="chat-tip">改动会自动 Schema 校验并同步到卡片 / YAML · 可指定场景（S2 / 第2场）</div>
        </div>
      </aside>
    </div>

    <!-- right scrim (mobile) -->
    <div class="pane-scrim" :class="{ show: rightOpen || leftOpen }" @click="rightOpen = false; leftOpen = false"></div>

    <!-- preview drawer -->
    <div class="scrim" :class="{ show: drawerOpen }" @click="drawerOpen = false"></div>
    <aside class="drawer" :class="{ show: drawerOpen }">
      <div class="dh">
        <h3>剧本预览 · Fountain</h3>
        <button @click="copy(fountainText()); toast('剧本文本已复制')">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="9" y="9" width="11" height="11" rx="2" /><path d="M5 15V5a2 2 0 012-2h10" /></svg>复制
        </button>
        <button @click="drawerOpen = false">✕ 关闭</button>
      </div>
      <div class="script-page"><div class="fountain" v-html="fountainHtml"></div></div>
    </aside>

    <div class="toast" :class="{ show: toastShow }">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M20 6L9 17l-5-5" /></svg>{{ toastMsg }}
    </div>
  </div>
</template>

<style scoped>
.wb { height: 100%; display: flex; flex-direction: column; overflow: hidden; }
button { font: inherit; cursor: pointer; }

/* ---------- top bar ---------- */
.top { display: flex; align-items: center; gap: 12px; padding: 11px 18px; border-bottom: 1px solid var(--border); background: var(--surface); flex: none; z-index: 30; }
.brand { display: flex; align-items: center; gap: 9px; font-weight: 700; font-size: 15px; }
.logo { width: 24px; height: 24px; border-radius: 6px; background: linear-gradient(135deg, var(--accent), #b07d1e); display: grid; place-items: center; color: #0e1116; font-weight: 800; font-size: 14px; }
.proj { color: var(--text-2); font-weight: 500; font-size: 13px; border-left: 1px solid var(--border); padding-left: 11px; white-space: nowrap; }
.proj b { color: var(--text); font-weight: 600; }
.proj .v { font-family: var(--mono); font-size: 11px; color: var(--muted); }
.top .spacer { flex: 1; }
.tb { display: inline-flex; align-items: center; gap: 7px; background: var(--raised); border: 1px solid var(--border); color: var(--text-2); padding: 7px 12px; border-radius: 6px; font-size: 13px; transition: 0.15s; white-space: nowrap; }
.tb:hover { border-color: var(--border-strong); color: var(--text); }
.tb svg { width: 15px; height: 15px; }
.tb b { color: var(--text); font-weight: 600; }
.tb .dot { width: 6px; height: 6px; border-radius: 50%; background: var(--accent); box-shadow: 0 0 7px var(--accent); }
.tb.ok { color: var(--success); border-color: rgba(63, 185, 80, 0.32); }
.tb.primary { background: var(--accent); color: #0e1116; border-color: var(--accent); font-weight: 600; }
.tb.primary:hover { background: var(--accent-hover); }
.ring { width: 38px; height: 38px; position: relative; flex: none; }
.ring svg { transform: rotate(0); }
.ring .num { position: absolute; inset: 0; display: grid; place-items: center; font-family: var(--mono); font-size: 13px; font-weight: 600; }
.menu { position: relative; }
.dd { position: absolute; right: 0; top: 44px; background: var(--raised); border: 1px solid var(--border-strong); border-radius: 10px; padding: 7px; min-width: 240px; box-shadow: 0 18px 50px rgba(0, 0, 0, 0.55); z-index: 60; display: none; }
.dd.open { display: block; }
.dd .it { display: flex; align-items: center; gap: 11px; width: 100%; text-align: left; padding: 9px 11px; border-radius: 7px; color: var(--text-2); font-size: 13px; background: none; border: none; }
.dd .it:hover { background: var(--surface); color: var(--text); }
.dd .it svg { width: 16px; height: 16px; flex: none; color: var(--accent); }
.dd .it small { display: block; color: var(--muted); font-size: 11px; margin-top: 1px; }
.dd .sep { height: 1px; background: var(--border); margin: 6px 4px; }
.dd.model-pop { padding: 15px; min-width: 300px; }
.dd.model-pop h4 { font-size: 11px; text-transform: uppercase; letter-spacing: 0.12em; color: var(--muted); margin-bottom: 11px; font-weight: 600; }
.dd .field { margin-bottom: 10px; }
.dd .field label { display: block; font-size: 11px; color: var(--text-2); margin-bottom: 4px; }
.dd .field input { width: 100%; background: var(--inset); border: 1px solid var(--border); color: var(--text); border-radius: 6px; padding: 7px 9px; font: inherit; font-size: 12.5px; }
.dd .field input:focus { outline: none; border-color: var(--accent); }
.pop-note { font-size: 11px; color: var(--muted); margin-top: 8px; line-height: 1.6; }
.pop-note code { font-family: var(--mono); color: var(--text-2); background: var(--inset); border-radius: 4px; padding: 1px 4px; }

/* ---------- 3 pane ---------- */
.work { flex: 1; display: grid; grid-template-columns: 248px 1fr 332px; min-height: 0; }
.pane { min-height: 0; overflow-y: auto; display: flex; flex-direction: column; }
.left { border-right: 1px solid var(--border); background: var(--surface); }
.center { background: var(--bg); }
.right { border-left: 1px solid var(--border); background: var(--surface); overflow: hidden; }
.pane-h { padding: 13px 16px; font-size: 12px; text-transform: uppercase; letter-spacing: 0.1em; color: var(--text-2); font-weight: 600; border-bottom: 1px solid var(--border); display: flex; align-items: center; gap: 8px; position: sticky; top: 0; background: var(--surface); z-index: 5; }
.pane-h .c { font-family: var(--mono); color: var(--muted); margin-left: auto; font-size: 11px; }

/* 三栏可见纵向滚动条（暗色主题；右栏由内部面板各自滚动） */
.pane,
.char-list,
.chat-msgs,
.tabpane.qual,
.script-page {
  scrollbar-width: thin;
  scrollbar-color: var(--border-strong) transparent;
}
.pane::-webkit-scrollbar,
.char-list::-webkit-scrollbar,
.chat-msgs::-webkit-scrollbar,
.tabpane.qual::-webkit-scrollbar,
.script-page::-webkit-scrollbar { width: 10px; height: 10px; }
.pane::-webkit-scrollbar-track,
.char-list::-webkit-scrollbar-track,
.chat-msgs::-webkit-scrollbar-track,
.tabpane.qual::-webkit-scrollbar-track,
.script-page::-webkit-scrollbar-track { background: transparent; }
.pane::-webkit-scrollbar-thumb,
.char-list::-webkit-scrollbar-thumb,
.chat-msgs::-webkit-scrollbar-thumb,
.tabpane.qual::-webkit-scrollbar-thumb,
.script-page::-webkit-scrollbar-thumb {
  background: var(--border-strong);
  border-radius: 999px;
  border: 2px solid transparent;
  background-clip: padding-box;
}
.pane::-webkit-scrollbar-thumb:hover,
.char-list::-webkit-scrollbar-thumb:hover,
.chat-msgs::-webkit-scrollbar-thumb:hover,
.tabpane.qual::-webkit-scrollbar-thumb:hover,
.script-page::-webkit-scrollbar-thumb:hover {
  background: var(--muted);
  background-clip: padding-box;
}

/* left outline */
.outline { padding: 8px; }
.oi { display: block; width: 100%; text-align: left; background: none; border: 1px solid transparent; border-radius: 8px; padding: 10px 11px; margin-bottom: 3px; color: var(--text-2); transition: 0.12s; }
.oi:hover { background: var(--raised); }
.oi.sel { background: var(--accent-soft); border-color: rgba(232, 179, 73, 0.3); }
.oi .sid { font-family: var(--mono); font-size: 11px; color: var(--muted); margin-bottom: 3px; display: flex; align-items: center; gap: 6px; }
.oi.sel .sid { color: var(--accent); }
.oi .sslug { font-size: 13.5px; color: var(--text); font-weight: 500; }
.oi .smeta { font-size: 11px; color: var(--muted); margin-top: 2px; }
.oi .warn { color: var(--warning); margin-left: auto; }
.add-scene { display: flex; align-items: center; gap: 8px; justify-content: center; width: calc(100% - 16px); margin: 6px 8px; padding: 10px; background: none; border: 1px dashed var(--border-strong); border-radius: 8px; color: var(--text-2); font-size: 13px; }
.add-scene:hover { border-color: var(--accent); color: var(--accent); }

/* center toolbar */
.ctool { position: sticky; top: 0; z-index: 6; display: flex; align-items: center; gap: 12px; padding: 11px 18px; background: var(--bg); border-bottom: 1px solid var(--border); }
.seg { display: inline-flex; background: var(--raised); border: 1px solid var(--border); border-radius: 8px; padding: 3px; }
.seg button { display: inline-flex; align-items: center; gap: 7px; background: none; border: none; color: var(--text-2); padding: 6px 14px; border-radius: 6px; font-size: 13px; }
.seg button.on { background: var(--accent); color: #0e1116; font-weight: 600; }
.seg button svg { width: 14px; height: 14px; }
.cur-scene { display: flex; align-items: center; gap: 8px; font-size: 12.5px; color: var(--text-2); padding-left: 4px; }
.cur-scene .stag { font-family: var(--mono); font-size: 11px; color: var(--accent); background: var(--accent-soft); border: 1px solid rgba(232, 179, 73, 0.3); border-radius: 5px; padding: 2px 7px; }
.cur-scene b { color: var(--text); font-weight: 600; }
.ctool .hint { color: var(--muted); font-size: 12px; margin-left: auto; display: flex; align-items: center; gap: 7px; }
.ctool .hint .k { font-family: var(--mono); background: var(--raised); border: 1px solid var(--border); border-radius: 4px; padding: 1px 6px; font-size: 11px; color: var(--text-2); }

/* scene cards */
.cards { padding: 18px; display: flex; flex-direction: column; gap: 18px; }
.scard { background: var(--surface); border: 1px solid var(--border); border-radius: 12px; overflow: hidden; scroll-margin-top: 70px; box-shadow: 0 1px 0 rgba(255, 255, 255, 0.03) inset, 0 8px 24px rgba(0, 0, 0, 0.32); }
.scard.sel { border-color: var(--border-strong); }
.scard.warn { border-color: rgba(210, 153, 34, 0.4); }
.sc-head { padding: 14px 16px; border-bottom: 1px solid var(--border); display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.slug { display: flex; align-items: center; gap: 7px; font-family: var(--script); font-weight: 700; font-size: 14px; letter-spacing: 0.02em; text-transform: uppercase; color: var(--text); }
.slug .selie { background: var(--inset); border: 1px solid var(--border); color: var(--text); border-radius: 5px; padding: 3px 7px; font-family: var(--ui); font-size: 12px; }
.slug .seg-loc { background: transparent; border: none; border-bottom: 1px dashed var(--border-strong); color: var(--text); padding: 2px 4px; outline: none; font: inherit; font-size: 13px; width: 88px; }
.slug .seg-loc:focus { border-bottom-color: var(--accent); background: var(--accent-soft); }
.slug .miss { color: var(--warning); border-color: var(--warning); }
.sc-head .sidtag { font-family: var(--mono); font-size: 11px; color: var(--muted); }
.sc-head .ch { font-size: 11px; color: var(--muted); margin-left: auto; font-family: var(--mono); }
.present { display: flex; align-items: center; gap: 7px; padding: 10px 16px; border-bottom: 1px solid var(--border); flex-wrap: wrap; }
.present .lab { font-size: 11px; color: var(--muted); }
.cchip { display: inline-flex; align-items: center; gap: 6px; background: var(--raised); border: 1px solid var(--border); border-radius: 999px; padding: 3px 10px 3px 4px; font-size: 12px; color: var(--text); }
.cchip .av { width: 18px; height: 18px; border-radius: 50%; background: var(--accent-soft); color: var(--accent); display: grid; place-items: center; font-size: 10px; font-weight: 700; }
.addc { background: none; border: 1px dashed var(--border-strong); color: var(--muted); border-radius: 999px; padding: 3px 10px; font-size: 12px; }
.addc:hover { border-color: var(--accent); color: var(--accent); }

.elems { padding: 6px 0; }
.erow { display: flex; position: relative; }
.erow .barc { width: 3px; flex: none; border-radius: 3px; margin: 10px 0 10px 16px; }
.erow .ebody { flex: 1; padding: 9px 14px 9px 12px; min-width: 0; }
.erow:hover { background: var(--raised); }
.erow .etype { font-size: 10px; text-transform: uppercase; letter-spacing: 0.08em; font-family: var(--mono); margin-bottom: 3px; display: flex; align-items: center; gap: 8px; }
.erow .who { font-weight: 600; color: var(--text); }
.erow .paren { color: var(--el-paren); font-style: italic; font-size: 12px; }
.erow .etext { display: block; width: 100%; color: var(--text); font: inherit; font-size: 14px; background: transparent; border: 1px solid transparent; border-radius: 4px; padding: 1px 3px; resize: none; }
.erow .etext:focus { background: var(--inset); border-color: var(--accent); outline: none; }
.erow .etext.ta { line-height: 1.5; min-height: 24px; field-sizing: content; }
.erow.dim { opacity: 0.32; }
.erow.hl { background: rgba(45, 212, 191, 0.07); }
.eact { display: flex; gap: 2px; opacity: 0; transition: 0.12s; align-items: flex-start; padding: 9px 12px 0 0; }
.erow:hover .eact { opacity: 1; }
.eact button { width: 26px; height: 26px; border-radius: 6px; background: none; border: none; color: var(--muted); display: grid; place-items: center; }
.eact button:hover { background: var(--inset); color: var(--text); }
.eact button svg { width: 14px; height: 14px; }
.addel { display: flex; gap: 7px; flex-wrap: wrap; padding: 8px 16px 12px; align-items: center; }
.addel .lab { font-size: 11px; color: var(--muted); }
.addel button { background: var(--raised); border: 1px solid var(--border); border-radius: 6px; padding: 4px 11px; font-size: 12px; }
.addel button:hover { border-color: var(--accent); }

.annot { border-top: 1px dashed var(--border); padding: 11px 16px; display: flex; align-items: center; gap: 9px; flex-wrap: wrap; font-size: 12px; }
.annot .grp { display: inline-flex; align-items: center; gap: 6px; color: var(--muted); }
.atag { background: var(--inset); border: 1px solid var(--border); border-radius: 5px; padding: 3px 9px; color: var(--text-2); font-size: 12px; }
.atag.shot { color: var(--accent); border-color: rgba(232, 179, 73, 0.3); }
.atag.mood { color: #f3b4d6; border-color: rgba(244, 114, 182, 0.3); }
.atag.pace { color: #9ecbff; border-color: rgba(88, 166, 255, 0.3); }
.ai-sug { margin-left: auto; background: none; border: none; color: var(--accent); font-size: 12px; display: inline-flex; align-items: center; gap: 5px; }
.ai-sug svg { width: 13px; height: 13px; }
.trace { border-top: 1px dashed var(--border); padding: 0 16px; }
.trace summary { padding: 10px 0; font-size: 12px; color: var(--text-2); cursor: pointer; list-style: none; display: flex; align-items: center; gap: 7px; }
.trace summary::-webkit-details-marker { display: none; }
.trace summary svg { width: 13px; height: 13px; color: var(--muted); }
.trace .src { padding: 0 0 12px 13px; font-size: 13px; color: var(--text-2); font-style: italic; border-left: 2px solid var(--accent); margin: 0 0 10px 5px; line-height: 1.6; }

/* YAML view */
.yaml-wrap { display: flex; flex-direction: column; min-height: 0; flex: 1; }
.yaml-bar { display: flex; align-items: center; gap: 10px; padding: 9px 16px; border-bottom: 1px solid var(--border); background: var(--bg); font-size: 12px; color: var(--muted); }
.yaml-bar .vstat { display: inline-flex; align-items: center; gap: 6px; }
.yaml-bar .vstat.ok { color: var(--success); }
.yaml-bar .vstat.bad { color: var(--danger); }
.yaml-bar .sp { flex: 1; }
.yaml-bar button { background: var(--raised); border: 1px solid var(--border); border-radius: 6px; padding: 5px 10px; font-size: 12px; color: var(--text-2); }
.yaml-bar button:hover { border-color: var(--accent); color: var(--accent); }
.yamled { flex: 1; display: flex; min-height: 0; }
.gutter { font-family: var(--mono); font-size: 13px; line-height: 1.6; color: var(--muted); text-align: right; padding: 14px 8px 14px 14px; background: var(--inset); user-select: none; white-space: pre; margin: 0; }
.yta { flex: 1; background: var(--inset); color: var(--text); border: none; resize: none; font-family: var(--mono); font-size: 13px; line-height: 1.6; padding: 14px 16px 14px 10px; outline: none; white-space: pre; tab-size: 2; }
.yta:focus { box-shadow: inset 3px 0 0 var(--accent); }

/* right panel */
.tabs { display: flex; gap: 4px; padding: 10px 12px 0; position: sticky; top: 0; background: var(--surface); z-index: 5; border-bottom: 1px solid var(--border); }
.tabs button { flex: 1; background: none; border: none; border-bottom: 2px solid transparent; color: var(--text-2); padding: 9px 0; font-size: 13px; font-weight: 500; }
.tabs button.on { color: var(--accent); border-bottom-color: var(--accent); }
.tabpane { padding: 12px; min-height: 0; }
.tabpane.char { display: flex; flex-direction: column; flex: 1; overflow: hidden; }
.tabpane.qual { flex: 1; overflow-y: auto; }
.char-list { flex: 1; min-height: 0; overflow-y: auto; margin: 0 -4px; padding: 0 4px 4px; }
.search { width: 100%; background: var(--inset); border: 1px solid var(--border); color: var(--text); border-radius: 7px; padding: 8px 11px; font: inherit; font-size: 13px; margin-bottom: 11px; flex: none; }
.search:focus { outline: none; border-color: var(--accent); }
.ccard { background: var(--raised); border: 1px solid var(--border); border-radius: 10px; padding: 13px; margin-bottom: 10px; cursor: pointer; transition: 0.15s; }
.ccard:hover { border-color: var(--border-strong); }
.ccard.act { border-color: var(--accent); box-shadow: 0 0 0 2px var(--accent-soft); }
.ccard .ch { display: flex; align-items: center; gap: 10px; margin-bottom: 9px; }
.ccard .av { width: 34px; height: 34px; border-radius: 50%; background: var(--accent-soft); color: var(--accent); display: grid; place-items: center; font-weight: 700; font-size: 15px; border: 1px solid rgba(232, 179, 73, 0.3); }
.ccard .nm { font-weight: 700; font-size: 15px; }
.ccard .role { font-size: 11px; color: var(--accent); background: var(--accent-soft); border-radius: 999px; padding: 2px 9px; margin-left: auto; }
.ccard .meta { font-size: 12px; color: var(--text-2); line-height: 1.7; }
.ccard .meta .k { color: var(--muted); }
.ccard .alias { display: flex; gap: 5px; flex-wrap: wrap; margin-top: 7px; }
.ccard .alias span { font-size: 11px; background: var(--inset); border: 1px solid var(--border); border-radius: 5px; padding: 2px 7px; color: var(--text-2); }

/* quality */
.gauge { display: flex; flex-direction: column; align-items: center; padding: 8px 0 16px; }
.gauge .gw { position: relative; width: 118px; height: 118px; }
.gauge .gw .gnum { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; }
.gauge .gw .gnum b { font-size: 34px; font-family: var(--mono); font-weight: 600; }
.gauge .gw .gnum small { font-size: 11px; color: var(--muted); }
.gauge .glabel { margin-top: 8px; font-size: 13px; color: var(--text-2); }
.qrow { margin-bottom: 13px; }
.qrow .qt { display: flex; align-items: center; font-size: 13px; margin-bottom: 5px; }
.qrow .qt .qv { margin-left: auto; font-family: var(--mono); font-size: 12px; color: var(--text-2); }
.qbar { height: 6px; background: var(--inset); border-radius: 999px; overflow: hidden; }
.qbar i { display: block; height: 100%; border-radius: 999px; background: linear-gradient(90deg, #b07d1e, var(--accent)); }
.qbar.good i { background: var(--success); }
.qpass { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--success); background: rgba(63, 185, 80, 0.08); border: 1px solid rgba(63, 185, 80, 0.25); border-radius: 8px; padding: 9px 12px; margin-bottom: 14px; }
.qpass svg { width: 16px; height: 16px; }
.qpass.bad { color: var(--danger); background: rgba(248, 81, 73, 0.08); border-color: rgba(248, 81, 73, 0.25); }
.qstat { display: flex; gap: 12px; font-size: 12px; color: var(--muted); font-family: var(--mono); margin: 6px 0 16px; flex-wrap: wrap; }
.qtodo { border-top: 1px solid var(--border); padding-top: 13px; }
.qtodo h5 { font-size: 12px; color: var(--text-2); margin-bottom: 9px; text-transform: uppercase; letter-spacing: 0.08em; }
.qtodo .ti { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--text-2); padding: 7px 0; }
.qtodo .ti .w { color: var(--warning); flex: none; }
.qtodo .ti .loc { margin-left: auto; background: var(--raised); border: 1px solid var(--border); color: var(--accent); border-radius: 6px; padding: 3px 10px; font-size: 11px; }
.qtodo .ti .loc:hover { border-color: var(--accent); }

/* AI 对话 */
.tabpane.chat { display: flex; flex-direction: column; flex: 1; overflow: hidden; padding: 12px; }
.chat-msgs { flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 12px; padding: 2px 2px 8px; }
.cmsg { display: flex; flex-direction: column; max-width: 92%; }
.cmsg.user { align-self: flex-end; align-items: flex-end; }
.cmsg.assistant { align-self: flex-start; align-items: flex-start; }
.cmsg .who { font-size: 10px; color: var(--accent); font-family: var(--mono); margin: 0 0 3px 2px; letter-spacing: 0.06em; }
.cmsg .bubble { font-size: 13.5px; line-height: 1.6; padding: 9px 12px; border-radius: 12px; white-space: pre-wrap; word-break: break-word; }
.cmsg.user .bubble { background: var(--accent-soft); border: 1px solid rgba(232, 179, 73, 0.3); color: var(--text); border-bottom-right-radius: 4px; }
.cmsg.assistant .bubble { background: var(--raised); border: 1px solid var(--border); color: var(--text-2); border-bottom-left-radius: 4px; }
.cmsg .bubble.typing { display: inline-flex; gap: 4px; align-items: center; }
.cmsg .bubble.typing i { width: 6px; height: 6px; border-radius: 50%; background: var(--muted); animation: blink 1.2s infinite both; }
.cmsg .bubble.typing i:nth-child(2) { animation-delay: 0.2s; }
.cmsg .bubble.typing i:nth-child(3) { animation-delay: 0.4s; }
@keyframes blink { 0%, 80%, 100% { opacity: 0.25; } 40% { opacity: 1; } }
.chat-input { display: flex; gap: 8px; align-items: flex-end; padding-top: 10px; border-top: 1px solid var(--border); flex: none; }
.chat-input textarea { flex: 1; background: var(--inset); border: 1px solid var(--border); color: var(--text); border-radius: 9px; padding: 9px 11px; font: inherit; font-size: 13.5px; line-height: 1.5; resize: none; }
.chat-input textarea:focus { outline: none; border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
.chat-input .send { display: inline-flex; align-items: center; gap: 6px; background: var(--accent); color: #0e1116; border: none; border-radius: 9px; padding: 9px 14px; font-size: 13px; font-weight: 600; white-space: nowrap; }
.chat-input .send:hover { background: var(--accent-hover); }
.chat-input .send:disabled { background: var(--raised); color: var(--muted); cursor: not-allowed; }
.chat-input .send svg { width: 15px; height: 15px; }
.chat-tip { font-size: 11px; color: var(--muted); margin-top: 8px; flex: none; }

/* ---------- preview drawer ---------- */
.scrim { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.6); opacity: 0; pointer-events: none; transition: 0.2s; z-index: 80; }
.scrim.show { opacity: 1; pointer-events: auto; }
.drawer { position: fixed; top: 0; right: 0; height: 100%; width: min(640px, 100%); background: var(--surface); border-left: 1px solid var(--border-strong); transform: translateX(100%); transition: 0.28s cubic-bezier(0.4, 0, 0.2, 1); z-index: 90; display: flex; flex-direction: column; }
.drawer.show { transform: none; }
.drawer .dh { display: flex; align-items: center; gap: 12px; padding: 14px 18px; border-bottom: 1px solid var(--border); }
.drawer .dh h3 { font-size: 15px; font-weight: 600; flex: 1; }
.drawer .dh button { display: inline-flex; align-items: center; gap: 6px; background: var(--raised); border: 1px solid var(--border); border-radius: 6px; padding: 6px 11px; font-size: 12px; color: var(--text-2); }
.drawer .dh button:hover { border-color: var(--accent); color: var(--accent); }
.drawer .dh button svg { width: 14px; height: 14px; }
.script-page { flex: 1; overflow-y: auto; padding: 48px 64px; background: #11151b; }
.fountain { max-width: 540px; margin: 0 auto; font-family: var(--script); font-size: 15px; line-height: 1.7; color: #dfe6ee; }
.fountain :deep(.title-pg) { text-align: center; margin-bottom: 48px; }
.fountain :deep(.title-pg .t) { font-size: 20px; font-weight: 700; letter-spacing: 0.05em; }
.fountain :deep(.title-pg .by) { color: var(--text-2); margin-top: 8px; font-size: 14px; }
.fountain :deep(.sh) { text-transform: uppercase; font-weight: 700; margin: 24px 0 10px; }
.fountain :deep(.ac) { margin: 0 0 12px; }
.fountain :deep(.cue) { text-align: center; text-transform: uppercase; margin: 14px 0 0; font-weight: 700; }
.fountain :deep(.par) { text-align: center; color: var(--text-2); font-size: 13px; }
.fountain :deep(.dlg) { max-width: 340px; margin: 2px auto 12px; text-align: center; }
.fountain :deep(.tr) { text-align: right; text-transform: uppercase; font-weight: 700; margin: 14px 0; color: var(--accent); }

/* toast */
.toast { position: fixed; bottom: 24px; left: 50%; transform: translateX(-50%) translateY(20px); background: var(--raised); border: 1px solid var(--border-strong); color: var(--text); padding: 11px 18px; border-radius: 10px; font-size: 13px; box-shadow: 0 12px 36px rgba(0, 0, 0, 0.5); opacity: 0; pointer-events: none; transition: 0.25s; z-index: 120; display: flex; align-items: center; gap: 9px; }
.toast.show { opacity: 1; transform: translateX(-50%); }
.toast svg { width: 16px; height: 16px; color: var(--success); }

/* ---------- mobile drawers ---------- */
.pane-toggle { display: none; align-items: center; justify-content: center; width: 34px; height: 34px; background: var(--raised); border: 1px solid var(--border); border-radius: 6px; color: var(--text-2); }
.pane-toggle svg { width: 17px; height: 17px; }
.pane-scrim { display: none; position: fixed; inset: 0; background: rgba(0, 0, 0, 0.55); opacity: 0; pointer-events: none; transition: 0.2s; z-index: 84; }

@media (max-width: 1200px) {
  .work { grid-template-columns: 248px 1fr; }
  .right { position: fixed; top: 0; right: 0; height: 100%; width: min(360px, 100%); transform: translateX(100%); transition: 0.26s; z-index: 85; border-left: 1px solid var(--border-strong); }
  .right.show { transform: none; }
  .pane-toggle.right-t { display: inline-flex; }
  .pane-scrim.show { display: block; opacity: 1; pointer-events: auto; }
}
@media (max-width: 900px) {
  .work { grid-template-columns: 1fr; }
  .left { position: fixed; top: 0; left: 0; height: 100%; width: min(300px, 86%); transform: translateX(-100%); transition: 0.26s; z-index: 85; border-right: 1px solid var(--border-strong); }
  .left.show { transform: none; }
  .pane-toggle.left-t { display: inline-flex; }
  .proj { display: none; }
  .ctool .hint { display: none; }
}
@media (max-width: 620px) {
  .top { flex-wrap: wrap; gap: 8px; padding: 10px 12px; }
  .tb span.lbl { display: none; }
  .script-page { padding: 28px 18px; }
  .fountain { font-size: 13px; }
  .cur-scene { display: none; }
}
</style>
