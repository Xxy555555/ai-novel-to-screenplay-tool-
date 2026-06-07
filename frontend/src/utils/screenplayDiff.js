// 内容级剧本 diff：对比旧/新剧本，产出可在「场景卡片」上高亮的结构化差异。
// 不依赖外部库——元素级用一个小 LCS 实现（按元素内容作 key）。

const SEP = ''

/** 元素内容指纹（用于 LCS 匹配；对白用 character+line，其它用 text）。 */
function elKey(e) {
  if (!e) return ''
  return [e.type || '', e.character || '', e.line || e.text || '', e.parenthetical || '', e.emotion || ''].join(SEP)
}

/** 两个元素数组的 LCS diff → [{op:'same'|'add'|'del', element}]（按新数组顺序，del 就地穿插）。 */
function diffElements(oldEls = [], newEls = []) {
  const a = oldEls.map(elKey)
  const b = newEls.map(elKey)
  const m = a.length
  const n = b.length
  // LCS 长度表
  const dp = Array.from({ length: m + 1 }, () => new Array(n + 1).fill(0))
  for (let i = m - 1; i >= 0; i--) {
    for (let j = n - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }
  const out = []
  let i = 0
  let j = 0
  while (i < m && j < n) {
    if (a[i] === b[j]) {
      out.push({ op: 'same', element: newEls[j] })
      i++
      j++
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      out.push({ op: 'del', element: oldEls[i] })
      i++
    } else {
      out.push({ op: 'add', element: newEls[j] })
      j++
    }
  }
  while (i < m) out.push({ op: 'del', element: oldEls[i++] })
  while (j < n) out.push({ op: 'add', element: newEls[j++] })
  return out
}

function norm(v) {
  if (v == null) return ''
  return typeof v === 'object' ? JSON.stringify(v) : String(v)
}

/** 比较一组字段，返回有差异的 [{field, before, after}]。 */
function diffFields(oldObj = {}, newObj = {}, fields) {
  const out = []
  for (const f of fields) {
    if (norm(oldObj?.[f]) !== norm(newObj?.[f])) {
      out.push({ field: f, before: oldObj?.[f] ?? null, after: newObj?.[f] ?? null })
    }
  }
  return out
}

const META_FIELDS = ['title', 'source_title', 'author', 'language', 'user_requirements']
const SCENE_FIELDS = ['chapter', 'mood', 'pacing', 'shots', 'present_characters', 'source']
const CHAR_FIELDS = ['name', 'role', 'tone', 'aliases', 'relations', 'first_appearance']

function byId(list = []) {
  const m = {}
  for (const x of list) if (x && x.id != null) m[x.id] = x
  return m
}

/**
 * 计算两份剧本的内容级差异。
 * @returns {{changed:boolean, summary:string, meta:Array, characters:{added:Array,removed:Array,changed:Array}, scenes:Array}}
 */
export function diffScreenplay(oldSp, newSp) {
  oldSp = oldSp || {}
  newSp = newSp || {}

  // meta
  const meta = diffFields(oldSp.meta || {}, newSp.meta || {}, META_FIELDS)

  // characters
  const oc = byId(oldSp.characters)
  const nc = byId(newSp.characters)
  const characters = { added: [], removed: [], changed: [] }
  for (const c of newSp.characters || []) {
    if (!oc[c.id]) characters.added.push({ id: c.id, name: c.name })
    else {
      const fields = diffFields(oc[c.id], c, CHAR_FIELDS)
      if (fields.length) characters.changed.push({ id: c.id, name: c.name, fields })
    }
  }
  for (const c of oldSp.characters || []) {
    if (!nc[c.id]) characters.removed.push({ id: c.id, name: c.name })
  }

  // scenes（按新顺序；删除的场景追加在后）
  const os = byId(oldSp.scenes)
  const ns = byId(newSp.scenes)
  const scenes = []
  let scenesChanged = 0
  let scenesAdded = 0
  let scenesRemoved = 0
  for (const s of newSp.scenes || []) {
    const prev = os[s.id]
    if (!prev) {
      scenes.push({ id: s.id, op: 'added', scene: s, elements: (s.elements || []).map((e) => ({ op: 'add', element: e })), fields: [] })
      scenesAdded++
      continue
    }
    const headingFields = diffFields(prev.heading || {}, s.heading || {}, ['int_ext', 'location', 'time_of_day'])
      .map((f) => ({ ...f, field: 'heading.' + f.field }))
    const fields = [...diffFields(prev, s, SCENE_FIELDS), ...headingFields]
    const els = diffElements(prev.elements || [], s.elements || [])
    const elementChanged = els.some((e) => e.op !== 'same')
    const op = fields.length || elementChanged ? 'changed' : 'same'
    if (op === 'changed') scenesChanged++
    scenes.push({ id: s.id, op, scene: s, elements: els, fields })
  }
  for (const s of oldSp.scenes || []) {
    if (!ns[s.id]) {
      scenes.push({ id: s.id, op: 'removed', scene: s, elements: (s.elements || []).map((e) => ({ op: 'del', element: e })), fields: [] })
      scenesRemoved++
    }
  }

  const changed = meta.length > 0
    || characters.added.length > 0 || characters.removed.length > 0 || characters.changed.length > 0
    || scenesChanged > 0 || scenesAdded > 0 || scenesRemoved > 0

  // 摘要
  const parts = []
  if (meta.find((m) => m.field === 'title')) parts.push('标题变更')
  if (scenesChanged) parts.push(`${scenesChanged} 个场景改动`)
  if (scenesAdded) parts.push(`新增 ${scenesAdded} 个场景`)
  if (scenesRemoved) parts.push(`删除 ${scenesRemoved} 个场景`)
  if (characters.added.length) parts.push(`新增 ${characters.added.length} 个角色`)
  if (characters.removed.length) parts.push(`删除 ${characters.removed.length} 个角色`)
  if (characters.changed.length) parts.push(`${characters.changed.length} 个角色改动`)
  const summary = changed ? parts.join(' · ') || '有改动' : '无可见改动'

  return { changed, summary, meta, characters, scenes }
}

const clone = (o) => JSON.parse(JSON.stringify(o))

/**
 * 逐行采纳：把「目标剧本 newSp」中的<单个改动>应用到「当前剧本 oldSp」，返回新剧本（纯函数，不改输入）。
 * 配合 diffScreenplay 的渲染：每应用一条后调用方应重新 diff（采纳的行会变成 same 而从 diff 中消失）。
 *
 * change 形态：
 *  - {kind:'meta', field}
 *  - {kind:'char-add'|'char-del'|'char-change', id}
 *  - {kind:'scene-add'|'scene-del'|'scene-replace', id}
 *  - {kind:'scene-field', id, field}   // field 可为 'heading.location' 等
 *  - {kind:'element', id, rowIndex}    // rowIndex = 该场景元素 diff（diffElements(当前,目标)）中的行下标
 */
export function applyDiffChange(oldSp, newSp, change) {
  const out = clone(oldSp || {})
  out.meta = out.meta || {}
  out.characters = out.characters || []
  out.scenes = out.scenes || []
  const tgt = newSp || {}
  const findScene = (sp, id) => (sp.scenes || []).find((s) => s.id === id)

  switch (change.kind) {
    case 'meta': {
      const f = change.field
      if (tgt.meta && Object.prototype.hasOwnProperty.call(tgt.meta, f) && tgt.meta[f] != null) out.meta[f] = clone(tgt.meta[f])
      else delete out.meta[f]
      break
    }
    case 'char-add': {
      const c = (tgt.characters || []).find((x) => x.id === change.id)
      if (c && !out.characters.find((x) => x.id === change.id)) {
        const pos = (tgt.characters || []).findIndex((x) => x.id === change.id)
        out.characters.splice(Math.min(pos < 0 ? out.characters.length : pos, out.characters.length), 0, clone(c))
      }
      break
    }
    case 'char-del': {
      out.characters = out.characters.filter((x) => x.id !== change.id)
      break
    }
    case 'char-change': {
      const c = (tgt.characters || []).find((x) => x.id === change.id)
      const i = out.characters.findIndex((x) => x.id === change.id)
      if (c && i >= 0) out.characters.splice(i, 1, clone(c))
      break
    }
    case 'scene-add': {
      const s = findScene(tgt, change.id)
      if (s && !findScene(out, change.id)) {
        const pos = (tgt.scenes || []).findIndex((x) => x.id === change.id)
        out.scenes.splice(Math.min(pos < 0 ? out.scenes.length : pos, out.scenes.length), 0, clone(s))
      }
      break
    }
    case 'scene-del': {
      out.scenes = out.scenes.filter((x) => x.id !== change.id)
      break
    }
    case 'scene-replace': {
      const s = findScene(tgt, change.id)
      const i = out.scenes.findIndex((x) => x.id === change.id)
      if (s && i >= 0) out.scenes.splice(i, 1, clone(s))
      break
    }
    case 'scene-field': {
      const os = findScene(out, change.id)
      const ts = findScene(tgt, change.id)
      if (os && ts) {
        const f = change.field
        if (f.startsWith('heading.')) {
          const sub = f.slice('heading.'.length)
          os.heading = os.heading || {}
          os.heading[sub] = clone((ts.heading || {})[sub])
        } else {
          os[f] = clone(ts[f])
        }
      }
      break
    }
    case 'element': {
      const os = findScene(out, change.id)
      const ts = findScene(tgt, change.id)
      if (os && ts) {
        const rows = diffElements(os.elements || [], ts.elements || [])
        const newEls = []
        rows.forEach((r, idx) => {
          if (r.op === 'same') newEls.push(r.element)
          else if (r.op === 'add') { if (idx === change.rowIndex) newEls.push(r.element) }
          else if (r.op === 'del') { if (idx !== change.rowIndex) newEls.push(r.element) }
        })
        os.elements = newEls
      }
      break
    }
    default:
      break
  }
  return out
}
