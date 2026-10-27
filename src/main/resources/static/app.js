/* ============================================================================
   Code Association — impact workbench front-end
   Vanilla JS. Talks to the Spring Boot API under /api.
   Review decisions are local-only (localStorage); analysis data is server-side.
   ========================================================================== */
'use strict';

/* ------------------------------------------------------------------ labels */

const RISK_LABEL = { CRITICAL: '严重', HIGH: '高', MEDIUM: '中', LOW: '低', INFO: '信息' };
const RISK_TONE = { CRITICAL: 'danger', HIGH: 'warning', MEDIUM: 'warning', LOW: 'success', INFO: 'neutral' };
const TYPE_LABEL = {
  COMPILE: '编译影响', BEHAVIOR: '行为影响', API_CONTRACT: '接口影响', DATA: '数据影响',
  CONFIG: '配置影响', TYPE: '类型影响', STRUCTURE: '结构影响', SECURITY: '安全影响',
  MESSAGE_CONTRACT: '消息契约影响', MESSAGE_BEHAVIOR: '消息行为影响'
};
const CHANGE_LABEL = {
  METHOD_ADDED: '新增方法', METHOD_REMOVED: '删除方法', METHOD_SIGNATURE_CHANGED: '签名变化',
  METHOD_BODY_CHANGED: '实现变化', METHOD_CONDITION_CHANGED: '分支条件变化', METHOD_VISIBILITY_CHANGED: '可见性变化',
  FIELD_ADDED: '新增字段', FIELD_REMOVED: '删除字段', FIELD_TYPE_CHANGED: '字段类型变化',
  ENUM_CONSTANT_ADDED: '新增枚举值', ENUM_CONSTANT_REMOVED: '删除枚举值',
  TYPE_ADDED: '新增类型', TYPE_REMOVED: '删除类型', TYPE_HIERARCHY_CHANGED: '继承关系变化',
  API_ADDED: '新增接口', API_REMOVED: '删除接口', API_CONTRACT_CHANGED: '接口契约变化',
  SQL_CHANGED: 'SQL 变化', SQL_ADDED: '新增 SQL', SQL_REMOVED: '删除 SQL', DDL_CHANGED: '表结构变化',
  CONFIG_CHANGED: '配置值变化', CONFIG_ADDED: '新增配置', CONFIG_REMOVED: '删除配置',
  TOPIC_ADDED: '新增消息主题', TOPIC_REMOVED: '删除消息主题',
  MESSAGE_TOPOLOGY_REMOVED: '删除消息拓扑',
  MESSAGE_ROUTE_CHANGED: '消息路由变化', MESSAGE_GROUP_CHANGED: '消费组变化',
  MESSAGE_FILTER_CHANGED: '消息过滤变化', MESSAGE_SCHEMA_CHANGED: '消息契约变化',
  MESSAGE_FIELD_ADDED: '新增消息字段', MESSAGE_FIELD_REMOVED: '删除消息字段',
  MESSAGE_FIELD_TYPE_CHANGED: '消息字段类型变化', MESSAGE_REQUIRED_CHANGED: '消息必填字段变化',
  MESSAGE_ENUM_CHANGED: '消息枚举值变化',
  TEST_CHANGED: '测试变化', BUILD_CHANGED: '构建变化'
};
const MODE_LABEL = { GIT_DIFF: '提交对比', WORKTREE_DIFF: '未提交改动', PULL_REQUEST: 'PR / MR' };
const STATUS_LABEL = { DONE: '已就绪', RUNNING: '分析中', PENDING: '排队中', FAILED: '失败' };
const STATUS_TONE = { DONE: 'success', RUNNING: 'brand', PENDING: 'neutral', FAILED: 'danger' };
const FACTOR_LABEL = { changeRisk: '变更风险', depth: '传播深度', behavior: '行为变化', criticality: '影响面', uncertainty: '不确定性' };
const PROGRESS_STEPS = [
  { id: 'PREPARE', title: '准备代码', meta: '版本与快照' },
  { id: 'DIFF', title: '读取差异', meta: '文件变更' },
  { id: 'INDEX_HEAD', title: '索引目标', meta: 'AST / 依赖 / 消息' },
  { id: 'INDEX_BASE', title: '索引基线', meta: '对比版本' },
  { id: 'DETECT', title: '检测变更', meta: '语义分类' },
  { id: 'IMPACT', title: '准备契约', meta: '变更对外契约' },
  { id: 'PEERS', title: '扫描其他程序', meta: '契约匹配与传播' },
  { id: 'REPORT', title: '生成报告', meta: '证据与风险' }
];

/* ----------------------------------------------------------------- helpers */

const $ = function (id) { return document.getElementById(id); };
const REVIEW_KEY = 'ca.review.v1';
const PAGE_KEY = 'ca.page';
const DENSITY_KEY = 'ca.density';
const THEME_KEY = 'ca.theme';
const NAV_KEY = 'ca.nav';
/** Version the user chose to stop being told about; the settings card still lists it. */
const UPDATE_SKIP_KEY = 'ca.update.skip.v1';
/** Collapsed tag groups in the source sidebar; expanded is the default, so only folds are stored. */
const NAVTAGS_KEY = 'ca.navtags';
/** Mirrors SourceTags on the server: it stays authoritative, these only shape the input. */
const TAG_MAX_COUNT = 12;
const TAG_MAX_LENGTH = 24;
/** Sources without any tag are listed under this pseudo group; it is never a real tag. */
const UNTAGGED_GROUP = '未分类';

function esc(v) {
  return String(v == null ? '' : v)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function tag(name, attrs, inner) {
  var s = '<' + name;
  if (attrs) {
    for (var k in attrs) {
      var v = attrs[k];
      if (v === null || v === undefined || v === false) continue;
      s += ' ' + k + '="' + esc(v) + '"';
    }
  }
  if (inner === null || inner === undefined) return s + '></' + name + '>';
  return s + '>' + inner + '</' + name + '>';
}

function icon(id) { return '<svg><use href="#' + id + '"/></svg>'; }
function pill(text, tone, withDot) {
  return '<span class="pill ' + (tone || 'neutral') + '">' + (withDot ? '<span class="dot"></span>' : '') + esc(text) + '</span>';
}
function chip(text, key) {
  return '<span class="chip">' + (key ? '<span class="k">' + esc(key) + '</span>' : '') + esc(text) + '</span>';
}
function fmtNum(v) { return v == null ? '0' : Number(v).toLocaleString('en-US'); }
function fmtDuration(ms) {
  if (ms == null) return '—';
  if (ms < 1000) return ms + ' ms';
  return (ms / 1000).toFixed(1) + ' s';
}
function fmtTime(iso) {
  if (!iso) return '—';
  var d = new Date(iso);
  if (isNaN(d.getTime())) return String(iso).slice(0, 19).replace('T', ' ');
  var p = function (n) { return String(n).padStart(2, '0'); };
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
}
function relTime(iso) {
  if (!iso) return '';
  var t = new Date(iso).getTime();
  if (isNaN(t)) return '';
  var diff = Date.now() - t;
  var mins = Math.round(diff / 60000);
  if (mins < 1) return '刚刚';
  if (mins < 60) return mins + ' 分钟前';
  var hrs = Math.round(mins / 60);
  if (hrs < 24) return hrs + ' 小时前';
  return Math.round(hrs / 24) + ' 天前';
}
function shortFile(path) {
  if (!path) return '';
  var parts = String(path).split('/');
  return parts.length <= 2 ? path : parts.slice(-2).join('/');
}
function shortSymbol(symbol) {
  if (!symbol) return '';
  var value = String(symbol);
  var hash = value.indexOf('#');
  if (hash < 0) return value.split('.').pop();
  var type = value.slice(0, hash);
  var method = value.slice(hash);
  var simple = type.split('.').pop();
  return simple + method;
}
function pct(a, b) { return b > 0 ? Math.round((a / b) * 100) : 0; }

async function api(path, options) {
  var res = await fetch('/api' + path, Object.assign({ headers: { 'Content-Type': 'application/json' } }, options || {}));
  if (!res.ok) {
    var message = res.status + ' ' + res.statusText;
    try {
      var body = await res.json();
      if (body && body.error) message = body.error;
    } catch (ignored) { /* keep status text */ }
    throw new Error(message);
  }
  if (res.status === 204) return null;
  var text = await res.text();
  return text ? JSON.parse(text) : null;
}

/* -------------------------------------------------------------------- state */

var state = {
  page: 'overview',
  sources: [],
  analyses: [],
  report: null,
  analysisId: null,
  liveAnalysis: null,
  pollTimer: null,
  /** The source the workspace is currently pointed at; independent of which analyses exist. */
  sourceId: null,
  review: {},
  reviewTab: 'all',
  selectedImpactId: null,
  selectedModule: null,
  search: '',
  historyFilter: '',
  health: null,
  importStep: -1,
  importError: false,
  maps: {},
  /** GET /api/tags result: [{tag, count}], most used first. Empty when the call fails. */
  tags: [],
  /** Tag the workspace is filtered to, or null for "everything". */
  tagFilter: null,
  /** Source being edited in #modal-source; null means the modal is in "add" mode. */
  editingSourceId: null,
  /** Tags of the source form currently being edited, shared by the add form and the modal. */
  sourceTags: [],
  /** parent is whatever the server last reported; the client never derives it (see loadDir). */
  picker: { target: null, path: null, parent: null, selected: null },
  /** Cached GET /api/update payload; see ensureUpdateLoaded(). */
  update: null,
  updateLoaded: false,
  /**
   * Version whose banner the user dismissed with the X in this page load. Deliberately not
   * persisted: only "忽略此版本" is a lasting choice, closing is "not now". Kept on state rather
   * than in the DOM so a re-render cannot resurrect a banner the user just closed.
   */
  updateBannerClosed: null
};

/* ---------------------------------------------------------------- decisions */

function loadReviewStore() {
  try { return JSON.parse(localStorage.getItem(REVIEW_KEY) || '{}') || {}; } catch (e) { return {}; }
}
function persistReview() {
  try { localStorage.setItem(REVIEW_KEY, JSON.stringify(state.review)); } catch (e) { /* ignore quota */ }
}
function decisionsFor(analysisId) {
  return state.review[String(analysisId)] || {};
}
function decisionOf(impactId) {
  return decisionsFor(state.analysisId)[String(impactId)] || null;
}
function setDecision(impactId, decision) {
  var key = String(state.analysisId);
  if (!state.review[key]) state.review[key] = {};
  if (decision) state.review[key][String(impactId)] = decision;
  else delete state.review[key][String(impactId)];
  persistReview();
}

/* --------------------------------------------------------------------- theme */

/* Three-way preference: 'system' follows the OS, 'light' / 'dark' pin it.
   The palette itself lives in CSS (:root[data-theme="dark"]); JS only decides
   which one is active, persists the choice and keeps canvas colours in sync. */
const themeQuery = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
var themePref = 'system';

function readThemePref() {
  var saved = null;
  try { saved = localStorage.getItem(THEME_KEY); } catch (e) { /* ignore */ }
  return (saved === 'light' || saved === 'dark') ? saved : 'system';
}

function resolveTheme(pref) {
  if (pref === 'light' || pref === 'dark') return pref;
  return themeQuery && themeQuery.matches ? 'dark' : 'light';
}

function currentTheme() {
  return document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light';
}

/* Canvas colours are painted into an SVG/cytoscape scene, so they cannot inherit
   CSS custom properties. Read the resolved token value instead of hardcoding. */
function themeColor(name, fallback) {
  var v = getComputedStyle(document.documentElement).getPropertyValue(name);
  v = (v || '').trim();
  return v || fallback;
}

function applyTheme(pref, opts) {
  themePref = (pref === 'light' || pref === 'dark') ? pref : 'system';
  var resolved = resolveTheme(themePref);
  var root = document.documentElement;
  if (resolved === 'dark') root.setAttribute('data-theme', 'dark');
  else root.removeAttribute('data-theme');
  root.setAttribute('data-theme-pref', themePref);
  try { localStorage.setItem(THEME_KEY, themePref); } catch (e) { /* ignore quota */ }

  var btn = $('theme-toggle');
  if (btn) {
    // The icon shows the theme the click will take you to.
    var use = btn.querySelector('use');
    if (use) use.setAttribute('href', resolved === 'dark' ? '#i-theme-sun' : '#i-theme-moon');
    btn.setAttribute('aria-pressed', resolved === 'dark' ? 'true' : 'false');
    btn.setAttribute('title', resolved === 'dark' ? '切换到浅色' : '切换到深色');
  }
  var select = $('set-theme');
  if (select && select.value !== themePref) select.value = themePref;

  // The dependency graph bakes colours into the scene at build time.
  if (!(opts && opts.silent) && state.report) renderMapsFor(state.page);
}

function toggleTheme() {
  applyTheme(currentTheme() === 'dark' ? 'light' : 'dark');
  toast(currentTheme() === 'dark' ? '已切换到深色主题' : '已切换到浅色主题');
}

function initTheme() {
  themePref = readThemePref();
  applyTheme(themePref, { silent: true });
  var btn = $('theme-toggle');
  if (btn) btn.addEventListener('click', toggleTheme);
  var select = $('set-theme');
  if (select) {
    select.value = themePref;
    select.addEventListener('change', function () {
      applyTheme(select.value);
      toast('外观主题已更新');
    });
  }
  // Follow the OS while the preference is 'system'.
  if (themeQuery) {
    var onChange = function () { if (themePref === 'system') applyTheme('system'); };
    if (themeQuery.addEventListener) themeQuery.addEventListener('change', onChange);
    else if (themeQuery.addListener) themeQuery.addListener(onChange);
  }
}

/* ------------------------------------------------- collapsible navigation */

/**
 * Folds the text menu away and leaves the icon rail, for narrow screens or when the content
 * needs the width. Two details matter:
 *
 *   1. It is persisted, because a layout choice that resets on reload is worse than none;
 *   2. It is applied before first paint (see boot), so the menu never flashes open and shut.
 */
function navCollapsed() {
  return document.querySelector('.app').classList.contains('nav-collapsed');
}

function applyNavCollapsed(collapsed, opts) {
  var app = document.querySelector('.app');
  if (!app) return;
  app.classList.toggle('nav-collapsed', !!collapsed);

  // The toggle inside the menu is gone once folded, so both affordances carry the same state
  // for assistive tech: a stale aria-expanded on a hidden button is worse than omitting it.
  var collapseBtn = $('nav-collapse');
  if (collapseBtn) collapseBtn.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
  var expandBtn = $('nav-expand');
  if (expandBtn) expandBtn.setAttribute('aria-expanded', collapsed ? 'true' : 'false');

  // Charts measure their container, so a width change needs a re-layout or they keep the old size.
  if (!(opts && opts.silent)) {
    requestAnimationFrame(function () { renderMapsFor(state.page); });
  }
}

function setNavCollapsed(collapsed, opts) {
  applyNavCollapsed(collapsed, opts);
  if (!(opts && opts.silent)) {
    try { localStorage.setItem(NAV_KEY, collapsed ? 'collapsed' : 'expanded'); } catch (e) { /* ignore quota */ }
    toast(collapsed ? '已收起菜单栏' : '已展开菜单栏');
  }
}

function toggleNav() {
  setNavCollapsed(!navCollapsed());
}

function initNav() {
  var saved = null;
  try { saved = localStorage.getItem(NAV_KEY); } catch (e) { /* ignore */ }
  // Narrow viewports start folded: at 860px the menu is already hidden by CSS, so starting
  // expanded would only make the first toggle a no-op.
  var narrow = window.matchMedia && window.matchMedia('(max-width: 860px)').matches;
  applyNavCollapsed(saved ? saved === 'collapsed' : narrow, { silent: true });

  var collapseBtn = $('nav-collapse');
  if (collapseBtn) collapseBtn.addEventListener('click', toggleNav);
  var expandBtn = $('nav-expand');
  if (expandBtn) expandBtn.addEventListener('click', toggleNav);

  // Ctrl/⌘+B, the shortcut editors use for the same thing. Skipped while typing so it never
  // eats a keystroke meant for the search box or a form field.
  document.addEventListener('keydown', function (ev) {
    if (!(ev.key === 'b' || ev.key === 'B')) return;
    if (!(ev.ctrlKey || ev.metaKey) || ev.altKey || ev.shiftKey) return;
    var el = ev.target;
    var tag = el && el.tagName ? el.tagName.toLowerCase() : '';
    if (tag === 'input' || tag === 'textarea' || tag === 'select' || (el && el.isContentEditable)) return;
    ev.preventDefault();
    toggleNav();
  });
}

/* --------------------------------------------------------------------- toast */

function toast(message, tone) {
  var root = $('toasts');
  if (!root) return;
  var node = document.createElement('div');
  node.className = 'toast ' + (tone || '');
  var iconId = tone === 'error' ? 'i-alert-circle' : 'i-check-circle';
  node.innerHTML = icon(iconId) + '<div>' + esc(message) + '</div>';
  root.appendChild(node);
  setTimeout(function () {
    node.style.transition = 'opacity 180ms ease, transform 180ms ease';
    node.style.opacity = '0';
    node.style.transform = 'translateY(6px)';
    setTimeout(function () { node.remove(); }, 200);
  }, 2600);
}

/* -------------------------------------------------------------------- counts */

function impacts() { return (state.report && state.report.impacts) || []; }

/**
 * The program an impact belongs to.
 *
 * Reports produced before cross-program scanning existed have no peerSourceName. Falling back to
 * their targetModule would label this program's own module as "another program", so such entries
 * are marked explicitly instead.
 */
function impactProgram(impact) {
  if (!impact) return '—';
  return impact.peerSourceName || '（历史报告：本程序内部影响）';
}

/** Same rule for a module-level row. */
function moduleProgram(row) {
  if (!row) return '—';
  return row.peerSourceName || '（历史报告：本程序内部影响）';
}
function reviewCounts() {
  var all = impacts();
  var decisions = decisionsFor(state.analysisId);
  var confirmed = 0, dismissed = 0;
  for (var id in decisions) { if (decisions[id].decision === 'confirmed') confirmed++; else dismissed++; }
  return { total: all.length, confirmed: confirmed, dismissed: dismissed, pending: Math.max(0, all.length - confirmed - dismissed) };
}

/* --------------------------------------------------------------------- data */

async function loadSources() {
  try { state.sources = await api('/sources'); } catch (e) { state.sources = []; }
}

/**
 * All tags in use, with counts. A failure must not take the workspace down: the tag groups, the
 * filter pills and the candidate list all degrade to "no tags known" and everything else still
 * renders, so this mirrors loadSources' swallow-and-empty style.
 */
async function loadTags() {
  try { state.tags = (await api('/tags')) || []; } catch (e) { state.tags = []; }
}

/* ---------------------------------------------------------------- tag rules */

/**
 * Client-side mirror of the server's normalisation (SourceTags): split on both comma forms, trim,
 * drop empties, de-duplicate case-insensitively keeping the first spelling, drop anything longer
 * than 24 characters, keep at most 12. It never throws — the point is to shape the input, and the
 * server's answer stays authoritative.
 */
function normalizeTags(value) {
  var raw = [];
  if (value == null) return raw;
  if (Object.prototype.toString.call(value) === '[object Array]') raw = value;
  else raw = String(value).split(/[,，]/);
  var seen = {};
  var out = [];
  for (var i = 0; i < raw.length; i++) {
    var t = String(raw[i] == null ? '' : raw[i]).trim();
    if (!t || t.length > TAG_MAX_LENGTH) continue;
    var key = t.toLowerCase();
    if (seen[key]) continue;
    seen[key] = true;
    out.push(t);
    if (out.length >= TAG_MAX_COUNT) break;
  }
  return out;
}

/** Tags of one source, always an array even for a payload from an older server. */
function sourceTagsOf(source) {
  return (source && source.tags) || [];
}

/** True when the tag is already in the list, ignoring case — matches the server's dedupe rule. */
function hasTag(list, tag) {
  var key = String(tag == null ? '' : tag).trim().toLowerCase();
  return list.some(function (t) { return String(t).toLowerCase() === key; });
}

/* ------------------------------------------------------------ tag chips */

/**
 * The tag list of one form, keyed by form prefix ("src" for the add form, "m" for the modal).
 *
 * They are deliberately separate: the add form and the edit dialog are two different drafts, and
 * sharing one array would carry the tags typed in one over to the other. state.sourceTags is that
 * per-form draft store.
 */
function tagBuffer(prefix) {
  if (!state.sourceTags[prefix]) state.sourceTags[prefix] = [];
  return state.sourceTags[prefix];
}

/** Replaces a form's draft with the given tags, normalised the way the server will. */
function setFormTags(prefix, tags) {
  state.sourceTags[prefix] = normalizeTags(tags || []);
  renderTagInput(prefix);
}

/** Merges whatever is in the text field into the draft. Used by Enter, comma and paste. */
function commitTagInput(prefix) {
  var input = document.querySelector('[data-tag-input="' + prefix + '"]');
  if (!input) return;
  var raw = String(input.value || '');
  if (!raw.trim()) return;
  var before = tagBuffer(prefix).length;
  var merged = normalizeTags(tagBuffer(prefix).concat(raw.split(/[,，]/)));
  state.sourceTags[prefix] = merged;
  input.value = '';
  renderTagInput(prefix);
  // Silence here would look like a dropped keystroke: say why nothing was added.
  if (merged.length === before) {
    toast(merged.length >= TAG_MAX_COUNT
      ? '最多 ' + TAG_MAX_COUNT + ' 个标签'
      : '标签重复或超过 ' + TAG_MAX_LENGTH + ' 个字符', 'error');
  }
}

/** Adds one tag (a candidate click). */
function addFormTag(prefix, tag) {
  var merged = normalizeTags(tagBuffer(prefix).concat([tag]));
  var before = tagBuffer(prefix).length;
  state.sourceTags[prefix] = merged;
  renderTagInput(prefix);
  if (merged.length === before) toast('最多 ' + TAG_MAX_COUNT + ' 个标签', 'error');
}

function removeFormTag(prefix, tag) {
  state.sourceTags[prefix] = tagBuffer(prefix).filter(function (t) {
    return String(t).toLowerCase() !== String(tag).toLowerCase();
  });
  renderTagInput(prefix);
}

/**
 * Draws the chips and the candidate pool of one form.
 *
 * Only the chips and candidates are replaced; the text input is never touched, so re-rendering
 * after every commit cannot steal focus mid-typing.
 */
function renderTagInput(prefix) {
  var chipsHost = $(prefix + '-tags-chips');
  var candsHost = $(prefix + '-tags-cands');
  var tags = tagBuffer(prefix);

  if (chipsHost) {
    chipsHost.innerHTML = tags.map(function (t) {
      return '<span class="chip tag-chip">' + esc(t) +
        '<button class="tag-chip-x" type="button" data-tag-remove="' + prefix + '"' +
        ' data-tag-value="' + esc(t) + '" aria-label="移除标签 ' + esc(t) + '">' + icon('i-x') + '</button>' +
        '</span>';
    }).join('');
  }

  if (candsHost) {
    if (tags.length >= TAG_MAX_COUNT) {
      candsHost.innerHTML = '<span class="tag-cands-hint">已到上限 ' + TAG_MAX_COUNT + ' 个</span>';
      return;
    }
    var pool = knownTags().filter(function (t) { return !hasTag(tags, t); });
    candsHost.innerHTML = pool.length
      ? '<span class="tag-cands-label">已有标签</span>' + pool.slice(0, 16).map(function (t) {
          return '<button class="pill neutral tag-cand" type="button" data-tag-add="' + prefix + '"' +
            ' data-tag-value="' + esc(t) + '">' + icon('i-plus') + esc(t) + '</button>';
        }).join('')
      : '<span class="tag-cands-hint">还没有其他标签，直接输入即可创建</span>';
  }
}

/** Every tag known locally, either from GET /api/tags or from the sources themselves. */
function knownTags() {
  var seen = {};
  var out = [];
  var push = function (t) {
    var key = String(t).toLowerCase();
    if (seen[key]) return;
    seen[key] = true;
    out.push(String(t));
  };
  (state.tags || []).forEach(function (row) { if (row && row.tag) push(row.tag); });
  state.sources.forEach(function (s) { sourceTagsOf(s).forEach(push); });
  return out;
}

async function loadAnalyses() {
  try { state.analyses = await api('/analyses?limit=30'); } catch (e) { state.analyses = []; }
}

/**
 * The analysis record the workspace is currently showing, or null.
 *
 * The report is only written when a run succeeds, so everything that has to be shown about a
 * failed or in-flight run comes from this row instead. Without it the page cannot tell "no
 * analysis was ever run here" from "this run failed".
 */
function selectedAnalysis() {
  if (state.analysisId == null) return null;
  var row = state.analyses.filter(function (a) { return a.id === state.analysisId; })[0] || null;
  // A live run carries fresher progress than the list row fetched a moment earlier.
  if (state.liveAnalysis && state.liveAnalysis.id === state.analysisId) return state.liveAnalysis;
  return row;
}

async function openAnalysis(id) {
  state.analysisId = id;
  var owner = state.analyses.filter(function (a) { return a.id === id; })[0] || null;
  // A finished run has a report, but a pending one does not, and asking for it anyway is how a
  // failed record ended up with a null report *and* no visible reason.
  var hasReport = !owner || owner.status === 'DONE';
  state.report = null;
  if (hasReport) {
    try {
      state.report = await api('/analyses/' + id + '/report');
    } catch (e) {
      // 202 means "not ready yet" and is a normal answer for a run that is still going; only a
      // real failure deserves an error toast.
      if (!/(^|\s)202(\s|$)/.test(e.message)) toast('读取报告失败：' + e.message, 'error');
    }
  }
  // The report is the authority on which source it belongs to.
  if (state.report) {
    var reportOwner = state.analyses.filter(function (a) { return a.id === id; })[0];
    if (reportOwner) state.sourceId = reportOwner.sourceId;
  } else if (owner && owner.sourceId != null) {
    // Keep the workspace pointed at the source even when the run produced no report.
    state.sourceId = owner.sourceId;
  }
  // The live panel is what renders progress and failure; make sure it matches the opened record.
  if (owner && (owner.status === 'RUNNING' || owner.status === 'PENDING' || owner.status === 'FAILED')) {
    state.liveAnalysis = owner;
  } else if (state.liveAnalysis && state.liveAnalysis.id !== id) {
    state.liveAnalysis = null;
  }
  // Opening a run that is still in flight must also subscribe to it. Without this the panel shows
  // whatever the list row happened to say at click time and then never moves again - which is
  // exactly what "the progress is not updating" looks like from the outside.
  if (owner && (owner.status === 'RUNNING' || owner.status === 'PENDING')) {
    pollAnalysis(id);
  }
  state.selectedModule = null;
  state.selectedImpactId = null;
  renderAll();
}

async function loadLiveAnalysis(id) {
  try {
    state.liveAnalysis = await api('/analyses/' + id);
  } catch (e) {
    state.liveAnalysis = null;
  }
  return state.liveAnalysis;
}

/**
 * Points the workspace at a source.
 *
 * Selecting a source and loading a report are different concerns: a source may have no analyses
 * yet, in which case the pages must say so instead of silently keeping the previous source's
 * report on screen. Passing null clears the selection.
 */
async function selectSource(sourceId) {
  state.sourceId = sourceId == null ? null : Number(sourceId);
  state.selectedModule = null;
  state.selectedImpactId = null;
  if (state.sourceId == null) {
    state.report = null;
    state.analysisId = null;
    renderAll();
    return;
  }
  var latest = latestAnalysisFor(state.sourceId);
  if (latest) {
    await openAnalysis(latest.id);
    return;
  }
  // No analyses for this source: drop the previous report so nothing stale is shown.
  state.report = null;
  state.analysisId = null;
  renderAll();
}

/** Newest finished analysis of a source, or null. */
function latestAnalysisFor(sourceId) {
  return state.analyses.filter(function (a) {
    return a.sourceId === sourceId && a.status === 'DONE';
  })[0] || null;
}

/** Short badge for a source type: "dir", "git", "github", "gitlab". */
function sourceTypeLabel(type) {
  return ({ LOCAL_DIR: 'dir', LOCAL_GIT: 'git', GITHUB: 'github', GITLAB: 'gitlab', GIT: 'git' })[type] || 'dir';
}

/** The source record the workspace is pointed at. */
function currentSource() {
  return state.sources.filter(function (s) { return s.id === state.sourceId; })[0] || null;
}

async function refreshAll(keepPage) {
  await Promise.all([loadSources(), loadAnalyses(), loadTags()]);
  // A reload during a run must resume the live panel from persisted server progress.
  var running = state.analyses.filter(function (a) {
    return a.status === 'RUNNING' || a.status === 'PENDING';
  })[0];
  if (running) {
    state.liveAnalysis = running;
    state.sourceId = running.sourceId;
    pollAnalysis(running.id);
  }
  // Keep the current source if it still exists, otherwise fall back to the newest analysis.
  var current = state.sourceId != null ? latestAnalysisFor(state.sourceId) : null;
  if (current) {
    if (state.analysisId !== current.id) await openAnalysis(current.id);
    else renderAll();
  } else if (state.sourceId != null && state.sources.some(function (s) { return s.id === state.sourceId; })) {
    // A selected source with no analyses is a valid state: keep showing its empty pages.
    renderAll();
  } else {
    var latest = state.analyses.filter(function (a) { return a.status === 'DONE'; })[0];
    if (latest) await openAnalysis(latest.id);
    else renderAll();
  }
  if (keepPage) go(state.page);
}

/* ---------------------------------------------------------------- navigation */

function go(page) {
  if (!document.getElementById('page-' + page)) page = 'overview';
  // Leaving the analysis page means the user chose background work. The poll keeps running so
  // completion still refreshes history, but the live panel no longer blocks the workspace.
  if (state.page === 'analysis' && page !== 'analysis' && state.liveAnalysis) {
    state.liveAnalysis = null;
  }
  state.page = page;
  var sections = document.querySelectorAll('.page');
  for (var i = 0; i < sections.length; i++) {
    sections[i].classList.toggle('hidden', sections[i].getAttribute('data-page') !== page);
  }
  var railBtns = document.querySelectorAll('.rail-btn[data-nav]');
  for (var j = 0; j < railBtns.length; j++) {
    railBtns[j].classList.toggle('active', railBtns[j].getAttribute('data-nav') === page);
  }
  var navItems = document.querySelectorAll('.nav-item[data-nav]');
  for (var k = 0; k < navItems.length; k++) {
    navItems[k].classList.toggle('active', navItems[k].getAttribute('data-nav') === page);
  }
  var ws = $('workspace');
  if (ws) ws.scrollTop = 0;
  try { localStorage.setItem(PAGE_KEY, page); } catch (e) { /* ignore */ }
  renderMapsFor(page);
  // Page switches do not re-fetch anything, but the notice is page-independent and cheap to
  // re-evaluate; doing it here keeps the rail dot in sync without a poll-time API call.
  renderUpdateNotice();
}

function renderMapsFor(page) {
  var hosts = [];
  if (page === 'analysis') hosts.push('an-map');
  if (page === 'map') hosts.push('big-map');
  if (!state.report) {
    // Without a report the previous source's graph must not linger on screen.
    hosts.forEach(function (id) {
      var host = $(id);
      if (!host) return;
      if (state.maps[id]) { state.maps[id].destroy(); delete state.maps[id]; }
      var picked = currentSource();
      host.innerHTML = '<div class="empty">' + (picked
        ? esc(picked.name + ' 还没有分析记录，运行一次分析后这里会显示模块关系图。')
        : '添加代码源并运行一次分析后，这里会显示模块关系图。') + '</div>';
    });
    return;
  }
  if (page === 'analysis') renderMap('an-map', state.report, { height: 360 });
  if (page === 'map') renderMap('big-map', state.report, { height: 600, big: true });
}

/* -------------------------------------------------------------- nav render */

/* --------------------------------------------------------- cross-program */

/**
 * Renders what the other selected programs have to change.
 *
 * The section only appears when a comparison was requested, so a normal single-program analysis
 * is not cluttered with an empty panel.
 */
function renderPeers(report) {
  renderPeerPanel(report, 'ov');
  renderPeerPanel(report, 'rv');
}

/**
 * Draws the cross-program section into one page. The overview and the review screen show the
 * same content because both answer the same question: does this change reach another program.
 */
function renderPeerPanel(report, prefix) {
  var card = $(prefix + '-peer-card');
  var host = $(prefix + '-peers');
  var summary = $(prefix + '-peer-summary');
  if (!card || !host) return;
  var peers = (report && report.peers) || [];
  card.style.display = '';
  if (!peers.length) {
    if (summary) summary.innerHTML = pill('未选择程序', 'neutral', true);
    host.innerHTML = '<div class="callout warning"><svg><use href="#i-alert-circle"/></svg><div>' +
      esc((report && report.peersNote) || '本次没有选择其他程序，未执行跨程序扫描。') +
      '</div></div>';
    return;
  }

  var totalImpacts = 0, totalHigh = 0, maxRisk = 0;
  peers.forEach(function (p) {
    totalImpacts += p.impactedSymbols || 0;
    totalHigh += p.highRisk || 0;
    maxRisk = Math.max(maxRisk, p.maxRisk || 0);
  });
  if (summary) {
    summary.innerHTML = peers.length
      ? pill('波及 ' + totalImpacts + ' 处', totalImpacts ? 'warning' : 'success', true) +
        (totalHigh ? pill(totalHigh + ' 项高风险', 'danger', true) : '')
      : '';
  }

  host.innerHTML = peers.map(function (p) {
    var head = '<div class="row-title" style="margin-bottom:6px">' +
      '<span class="row-icon brand" style="width:22px;height:22px;border-radius:6px;margin:0">' + icon('i-database') + '</span>' +
      esc(p.peerSourceName) +
      ' ' + pill(sourceTypeLabel(p.peerType), 'neutral') +
      (p.note ? ' ' + pill('未比较', 'neutral') : '') +
      (p.matches && p.matches.length ? ' ' + pill((p.matches || []).length + ' 个契约命中', 'brand', true) : '') +
      (p.impactedSymbols ? ' ' + pill('影响 ' + p.impactedSymbols + ' 处', RISK_TONE[p.riskLevel] || 'neutral', true) : '') +
      '</div>';

    if (p.note) {
      return '<div class="ev-row" style="margin-bottom:8px">' + head +
        '<div class="ev-meta">' + esc(p.note) + '</div></div>';
    }

    var matches = (p.matches || []).map(function (m) {
      return '<div class="ev-row" style="margin-bottom:6px">' +
        '<div class="ev-type">' + esc(m.kind) + '</div>' +
        '<div class="ev-main">' + esc(m.contract) + ' → ' + esc(m.peerSymbol) + '</div>' +
        '<div class="ev-meta">' + esc(m.usage) + (m.peerFile ? ' · ' + esc(shortFile(m.peerFile)) + ':' + (m.peerLine || 0) : '') +
          ' · 置信度 ' + Math.round((m.confidence || 0) * 100) + '%</div></div>';
    }).join('');

    var impacts = (p.impacts || []).slice(0, 8).map(function (i) {
      return '<div class="list-row" data-impact-id="' + i.id + '">' +
        '<div class="row-icon ' + (RISK_TONE[i.riskLevel] || 'neutral') + '">' + icon('i-alert') + '</div>' +
        '<div class="row-main">' +
          '<div class="row-title"><span class="sym">' + esc(shortSymbol(i.targetQualifiedName || i.targetSymbolKey || '')) + '</span>' +
            pill(RISK_LABEL[i.riskLevel] || i.riskLevel, RISK_TONE[i.riskLevel], true) + '</div>' +
          '<div class="row-desc">' + esc(i.reason) + '</div>' +
          '<div class="row-meta">' + chip(i.impactType) + chip('深度 ' + i.depth) + chip('置信度 ' + i.confidence) + '</div>' +
        '</div></div>';
    }).join('');

    if (!p.matches || !p.matches.length) {
      return '<div class="ev-row" style="margin-bottom:8px">' + head +
        '<div class="ev-meta">没有发现与本程序变更相关的契约使用点。</div></div>';
    }
    return '<div style="margin-bottom:14px">' + head +
      '<div class="section-label">命中的契约</div>' +
      '<div class="evidence">' + matches + '</div>' +
      (impacts ? '<div class="section-label">受影响的其他程序代码</div><div class="list">' + impacts + '</div>' : '') +
      '</div>';
  }).join('');
}

/* --------------------------------------------------- sidebar tag groups */

/** Folded tag groups, persisted so the menu keeps its shape across reloads. */
function readNavTagFolds() {
  try { return JSON.parse(localStorage.getItem(NAVTAGS_KEY) || '{}') || {}; } catch (e) { return {}; }
}

function writeNavTagFolds(folds) {
  try { localStorage.setItem(NAVTAGS_KEY, JSON.stringify(folds)); } catch (e) { /* ignore quota */ }
}

function toggleNavTagGroup(name) {
  var folds = readNavTagFolds();
  if (folds[name]) delete folds[name];
  else folds[name] = true;
  writeNavTagFolds(folds);
  renderNavSources();
}

/** Sets or clears the tag filter; the same tag twice means "show everything again". */
function setTagFilter(tag) {
  var next = tag == null ? null : String(tag);
  state.tagFilter = (next && state.tagFilter !== next) ? next : null;
  renderNavSources();
  renderSourcesPage();
}

/**
 * Groups sources by tag for the sidebar. A source with several tags appears in every one of its
 * groups (that is the point of tags), untagged ones land in 未分类. The order follows the tag
 * counts the server returned, so the busiest group is at the top; 未分类 always sits last.
 */
function tagGroupsOf(list) {
  var order = [];
  var byTag = {};
  var addTo = function (tag, source) {
    var key = tag;
    if (!byTag[key]) { byTag[key] = []; order.push(key); }
    byTag[key].push(source);
  };
  (state.tags || []).forEach(function (row) {
    if (row && row.tag && !byTag[row.tag]) { byTag[row.tag] = []; order.push(row.tag); }
  });
  list.forEach(function (s) {
    var tags = sourceTagsOf(s);
    if (!tags.length) addTo(UNTAGGED_GROUP, s);
    else tags.forEach(function (t) { addTo(t, s); });
  });
  // A tag that no longer has any source (or was never counted) must not leave an empty group.
  var groups = order.filter(function (name) { return byTag[name].length > 0; }).map(function (name) {
    return { name: name, sources: byTag[name] };
  });
  groups.sort(function (a, b) {
    if (a.name === UNTAGGED_GROUP) return 1;
    if (b.name === UNTAGGED_GROUP) return -1;
    return 0;
  });
  return groups;
}

function renderNavSources() {
  var host = $('nav-sources');
  if (!host) return;
  var list = state.sources.slice();
  if (state.search) {
    var q = state.search.toLowerCase();
    list = list.filter(function (s) {
      var hay = String(s.name) + ' ' + String(s.location) + ' ' + sourceTagsOf(s).join(' ');
      return hay.toLowerCase().indexOf(q) >= 0;
    });
  }
  if (state.tagFilter) {
    list = list.filter(function (s) { return hasTag(sourceTagsOf(s), state.tagFilter); });
  }
  if (!list.length) {
    host.innerHTML = '<p class="muted" style="font-size:11.5px;padding:4px 8px">没有匹配的代码源</p>';
    return;
  }

  var folds = readNavTagFolds();
  var groups = tagGroupsOf(list);
  host.innerHTML = groups.map(function (group) {
    var folded = !!folds[group.name];
    var head = '<button class="nav-group-title nav-tag-head" type="button" data-navtag-group="' + esc(group.name) + '"' +
      ' aria-expanded="' + (folded ? 'false' : 'true') + '" title="' + (folded ? '展开' : '收起') + ' ' + esc(group.name) + '">' +
      icon(folded ? 'i-chevron-right' : 'i-chevron-down') +
      '<span class="nav-tag-name">' + esc(group.name) + '</span>' +
      '<span class="nav-tag-count">' + group.sources.length + '</span>' +
      '</button>';
    var items = folded ? '' : group.sources.map(function (s) {
      var tone = s.status === 'FAILED' ? 'danger' : 'brand';
      var active = state.sourceId != null && s.id === state.sourceId;
      return '<button class="nav-item' + (active ? ' active' : '') + '" data-source-id="' + s.id + '">' +
        '<span class="row-icon ' + tone + '" style="width:22px;height:22px;border-radius:6px;margin:0">' + icon('i-git') + '</span>' +
        '<span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">' + esc(s.name) + '</span>' +
        '<span class="count">' + esc(sourceTypeLabel(s.type)) + '</span>' +
        '</button>';
    }).join('');
    return '<div class="nav-tag-group">' + head + (items ? '<div class="nav-tag-items">' + items + '</div>' : '') + '</div>';
  }).join('');
}

/** The "筛选：<tag> 清除" strip shown above the source list while a tag filter is active. */
function renderTagFilterBar() {
  var host = $('src-filter-bar');
  if (!host) return;
  if (!state.tagFilter) {
    host.innerHTML = '';
    host.classList.add('hidden');
    return;
  }
  host.classList.remove('hidden');
  host.innerHTML = icon('i-filter') +
    '<span>筛选：<b>' + esc(state.tagFilter) + '</b></span>' +
    '<button class="btn btn-sm btn-ghost" type="button" data-tag-filter-clear>' + icon('i-x') + '清除</button>';
}

function renderNavCounts() {
  var counts = reviewCounts();
  var setText = function (id, v) { var el = $(id); if (el) el.textContent = v; };
  setText('nav-count-sources', state.sources.length);
  setText('nav-count-sources2', state.sources.length);
  setText('nav-count-review', counts.pending);
  setText('nav-count-triage', counts.pending);
  setText('nav-count-dismissed', counts.dismissed);
  setText('nav-count-history', state.analyses.length);

  var done = 0, running = 0, failed = 0;
  state.analyses.forEach(function (a) {
    if (a.status === 'DONE') done++;
    else if (a.status === 'FAILED') failed++;
    else running++;
  });
  setText('nav-status-done', done);
  setText('nav-status-running', running);
  setText('nav-status-failed', failed);

  var dot = $('rail-alert-dot');
  if (dot) dot.style.display = counts.pending > 0 ? '' : 'none';

  var hist = $('nav-history');
  if (hist) {
    var rows = state.analyses.slice(0, 4);
    hist.innerHTML = rows.length ? rows.map(function (a) {
      return '<button class="nav-item" data-analysis-id="' + a.id + '">' +
        '<span class="count" style="margin:0 6px 0 0">#' + a.id + '</span>' +
        '<span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">' + esc(a.sourceName || '') + '</span>' +
        '</button>';
    }).join('') : '<p class="muted" style="font-size:11.5px;padding:4px 8px">暂无记录</p>';
  }
}

/* ----------------------------------------------------------------- overview */

function renderOverview() {
  var report = state.report;
  var s = (report && report.summary) || {};
  var modePill = $('ov-mode-pill');
  if (modePill) modePill.textContent = report ? (MODE_LABEL[report.mode] || report.mode) : '暂无分析';

  var sub = $('ov-sub');
  if (sub) {
    if (report) {
      sub.innerHTML = esc(report.sourceName) + ' <span class="sep">·</span> ' +
        esc(report.baseLabel || '') + ' → ' + esc(report.headLabel || '') +
        ' <span class="sep">·</span> ' + esc(fmtTime(report.generatedAt));
    } else {
      var picked = currentSource();
      sub.innerHTML = picked
        ? esc(picked.name) + ' <span class="sep">·</span> 这个代码源还没有分析记录，运行一次分析后这里会展示影响总览。'
        : '还没有分析结果。添加代码源并运行一次分析后，这里会展示影响总览。';
    }
  }
  var setText = function (id, v) { var el = $(id); if (el) el.textContent = v; };
  setText('ov-head-programs', s.comparedPrograms == null ? '—' : s.comparedPrograms);
  setText('ov-head-high', s.highRisk == null ? '—' : s.highRisk);

  var statsHost = $('ov-stats');
  if (statsHost) {
    statsHost.innerHTML = [
      statTile('i-database', '对比程序', fmtNum(s.comparedPrograms), 'brand'),
      statTile('i-network', '命中契约', fmtNum(s.matchedContracts), 'brand'),
      statTile('i-code', '影响符号', fmtNum(s.impactedSymbols), 'brand'),
      statTile('i-alert', '高风险影响', fmtNum(s.highRisk), 'danger')
    ].join('');
  }

  var counts = reviewCounts();
  var decisions = decisionsFor(state.analysisId);
  var dismissedSet = {};
  var confirmedSet = {};
  for (var id in decisions) {
    if (decisions[id].decision === 'confirmed') confirmedSet[id] = true; else dismissedSet[id] = true;
  }
  var attention = impacts().filter(function (i) {
    return !dismissedSet[String(i.id)] && !confirmedSet[String(i.id)];
  }).sort(function (a, b) { return (b.riskScore || 0) - (a.riskScore || 0); }).slice(0, 4);

  var attnHost = $('ov-attention');
  if (attnHost) {
    attnHost.innerHTML = attention.length ? attention.map(function (i) {
      return '<div class="list-row" data-impact-id="' + i.id + '">' +
        '<div class="row-icon ' + (RISK_TONE[i.riskLevel] || 'neutral') + '">' + icon('i-alert') + '</div>' +
        '<div class="row-main">' +
          '<div class="row-title">' + esc(shortSymbol(i.targetSymbol)) +
            pill(RISK_LABEL[i.riskLevel] || i.riskLevel, RISK_TONE[i.riskLevel], true) + '</div>' +
          '<div class="row-desc">' + esc(i.reason) + '</div>' +
          '<div class="row-meta">' + chip(impactProgram(i)) + chip(TYPE_LABEL[i.impactType] || i.impactType) +
            chip('置信度 ' + i.confidence) + '</div>' +
        '</div>' +
        '<div class="row-right"><div class="row-arrow">' + icon('i-chevron-right') + '</div></div>' +
        '</div>';
    }).join('') : (state.report
      ? emptyBlock('没有待处理的影响', '当前分析中其他程序的所有影响项都已复核。')
      : emptyBlockForSource());
  }
  setText('ov-attn-count', counts.pending + ' 项');

  var ringHost = $('ov-ring');
  if (ringHost) {
    var p = pct(counts.confirmed + counts.dismissed, counts.total);
    ringHost.innerHTML = ringSvg(p);
  }
  var ringKv = $('ov-ring-kv');
  if (ringKv) {
    ringKv.innerHTML = [
      kvRow('待复核', counts.pending),
      kvRow('已确认', counts.confirmed),
      kvRow('已忽略', counts.dismissed)
    ].join('');
  }

  var bars = $('ov-risk-bars');
  if (bars) {
    var byLevel = { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0, INFO: 0 };
    impacts().forEach(function (i) { if (byLevel[i.riskLevel] != null) byLevel[i.riskLevel]++; });
    var total = impacts().length || 1;
    bars.innerHTML = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'].map(function (level) {
      var n = byLevel[level];
      return '<div>' +
        '<div style="display:flex;justify-content:space-between;font-size:11.5px;margin-bottom:5px">' +
          '<span style="color:var(--text-2)">' + RISK_LABEL[level] + '</span>' +
          '<span style="font-weight:600;font-variant-numeric:tabular-nums">' + n + '</span>' +
        '</div>' +
        '<div class="progress thin"><span class="' + riskBarClass(level) + '" style="width:' + Math.max(3, pct(n, total)) + '%"></span></div>' +
        '</div>';
    }).join('');
  }

  renderPeers(report);

  var tl = $('ov-timeline');
  if (tl) {
    var rows = state.analyses.slice(0, 5);
    tl.innerHTML = rows.length ? rows.map(function (a, idx) {
      var done = a.status === 'DONE';
      var failed = a.status === 'FAILED';
      // A failed run is the most urgent event on this list, so it must not share the neutral dot
      // (and blank meta) that an in-flight run gets.
      var tone = done ? (a.summary && a.summary.highRisk > 0 ? 'danger' : 'success') : (failed ? 'danger' : '');
      var meta = failed
        ? '<div class="tl-meta tl-reason" title="' + esc(a.message || '') + '">' + esc(a.message || '未记录失败原因') + '</div>'
        : '<div class="tl-meta">' + (a.summary ? ' · 影响 ' + (a.summary.impactedSymbols || 0) + ' · 高风险 ' + (a.summary.highRisk || 0) : '') + '</div>';
      return '<div class="tl-item" data-analysis-id="' + a.id + '">' +
        '<div class="tl-rail"><div class="tl-dot ' + tone + '"></div>' + (idx < rows.length - 1 ? '<div class="tl-line"></div>' : '') + '</div>' +
        '<div class="tl-body">' +
          '<div class="tl-title">' + esc(a.sourceName || '分析 #' + a.id) +
            pill(STATUS_LABEL[a.status] || a.status, STATUS_TONE[a.status] || 'neutral') + '</div>' +
          '<div class="tl-meta">' + esc(fmtTime(a.createdAt)) + ' · ' + esc(relTime(a.createdAt)) + meta + '</div>' +
        '</div>' +
        '</div>';
    }).join('') : emptyBlock('还没有事件', '运行一次分析后，这里会出现时间线。');
  }

  var recent = $('ov-recent');
  if (recent) {
    recent.innerHTML = analysesTable(state.analyses.slice(0, 6));
  }
}

function riskBarClass(level) {
  if (level === 'CRITICAL' || level === 'HIGH') return 'danger';
  if (level === 'MEDIUM') return 'warning';
  if (level === 'LOW') return 'success';
  return '';
}

function statTile(iconId, label, value, tone) {
  return '<div class="stat">' +
    '<div class="stat-icon ' + (tone || '') + '">' + icon(iconId) + '</div>' +
    '<div class="stat-body"><div class="value">' + esc(value) + '</div><div class="label">' + esc(label) + '</div></div>' +
    '</div>';
}
function kvRow(k, v) {
  return '<div class="kv-row"><span class="k">' + esc(k) + '</span><span class="v">' + esc(v) + '</span></div>';
}
function emptyBlock(title, sub) {
  return '<div class="empty"><strong>' + esc(title) + '</strong>' + esc(sub) + '</div>';
}

/**
 * Empty state for pages that have nothing to show because the selected source has no analysis
 * yet. Distinguishing this from "no data at all" is what tells the user the click worked.
 */
function emptyBlockForSource() {
  var picked = currentSource();
  return picked
    ? emptyBlock('这个代码源还没有分析记录', '点击「新建分析」为 ' + picked.name + ' 运行一次分析。')
    : emptyBlock('还没有分析记录', '添加代码源并运行一次分析后，这里会展示结果。');
}
function ringSvg(percent) {
  var r = 38, c = 2 * Math.PI * r, off = c * (1 - Math.max(0, Math.min(100, percent)) / 100);
  return '<svg width="92" height="92" viewBox="0 0 92 92">' +
    '<circle class="ring-track" cx="46" cy="46" r="' + r + '" fill="none" stroke-width="8"/>' +
    '<circle class="ring-value" cx="46" cy="46" r="' + r + '" fill="none" stroke-width="8" stroke-dasharray="' + c.toFixed(1) + '" stroke-dashoffset="' + off.toFixed(1) + '"/>' +
    '</svg>' +
    '<div class="ring-label"><div><div class="pct">' + percent + '%</div><div class="cap">已复核</div></div></div>';
}

function analysesTable(rows) {
  if (!rows.length) return emptyBlock('暂无分析记录', '添加代码源后运行一次分析。');
  return rows.map(function (a) {
    var s = a.summary || {};
    var failed = a.status === 'FAILED';
    // A failed row has no scale to report, so the reason takes that cell: "对比 0 · 契约 0 · 影响 0"
    // reads as a successful empty run and hides the one thing the row must communicate.
    var metaCell = failed
      ? '<div class="cell optional tr-reason" title="' + esc(a.message || '') + '">' + esc(a.message || '未记录失败原因') + '</div>'
      : '<div class="cell optional" style="font-size:11.5px;color:var(--text-2)">对比 ' + (s.comparedPrograms || 0) +
        ' · 契约 ' + (s.matchedContracts || 0) + ' · 影响 ' + (s.impactedSymbols || 0) + '</div>';
    return '<div class="tr tr-history" data-analysis-id="' + a.id + '">' +
      '<div class="cell mono" style="color:var(--text-2)">#' + a.id + '</div>' +
      '<div class="cell"><div style="font-weight:550">' + esc(a.sourceName || '') + '</div>' +
        '<div class="sub">' + esc(MODE_LABEL[a.mode] || a.mode || '') + ' · ' + esc(a.targetRef || '') + '</div></div>' +
      '<div class="cell optional">' + pill(STATUS_LABEL[a.status] || a.status, STATUS_TONE[a.status] || 'neutral', true) + '</div>' +
      metaCell +
      '<div class="cell optional" style="font-size:11.5px;color:var(--text-2)">' + esc(fmtTime(a.createdAt)) + '</div>' +
      '<div class="cell" style="text-align:right"><span class="row-arrow">' + icon('i-chevron-right') + '</span></div>' +
      '</div>';
  }).join('');
}

/* ------------------------------------------------------------------ sources */

/**
 * One tag pill in the source list. A button, not a span, because clicking it filters: aria-pressed
 * tells assistive tech which tag is the active filter.
 */
function tagPill(tag, active) {
  return '<button class="pill brand tag-pill" type="button" data-tag-filter="' + esc(tag) + '"' +
    ' aria-pressed="' + (active ? 'true' : 'false') + '" title="按标签筛选 ' + esc(tag) + '">' +
    esc(tag) + '</button>';
}

/** The tags column of a source row; an em dash keeps the column from collapsing when empty. */
function sourceTagCell(s) {
  var tags = sourceTagsOf(s);
  if (!tags.length) return '<span class="tag-empty">未分类</span>';
  return '<div class="tag-cell">' + tags.map(function (t) {
    return tagPill(t, state.tagFilter === t);
  }).join('') + '</div>';
}

function renderSourcesPage() {
  var host = $('src-recent');
  if (!host) return;
  renderTagFilterBar();
  var list = state.sources;
  if (state.tagFilter) {
    list = list.filter(function (s) { return hasTag(sourceTagsOf(s), state.tagFilter); });
  }
  if (!state.sources.length) {
    host.innerHTML = emptyBlock('还没有代码源', '在上方表单中添加第一个代码源。');
    return;
  }
  if (!list.length) {
    host.innerHTML = emptyBlock('这个标签下没有代码源', '清除筛选后可以查看全部代码源。');
    return;
  }
  host.innerHTML = list.map(function (s) {
    var last = state.analyses.filter(function (a) { return a.sourceId === s.id; })[0];
    var status = last ? last.status : 'PENDING';
    // MISSING comes from the server (an import whose directory does not exist on this machine),
    // and it is about the source, not about a run, so it leads the status cell.
    var missing = s.status === 'MISSING';
    return '<div class="tr tr-sources" data-source-id="' + s.id + '">' +
      '<div class="cell mono" style="color:var(--text-2)">' + esc(sourceTypeLabel(s.type)) + '</div>' +
      '<div class="cell"><div style="font-weight:550">' + esc(s.name) + '</div>' +
        '<div class="sub mono">' + esc(s.location) + '</div>' +
        (missing && s.message ? '<div class="sub danger-text">' + esc(s.message) + '</div>' : '') + '</div>' +
      '<div class="cell optional">' + (missing
        ? pill('目录缺失', 'danger', true)
        : pill(STATUS_LABEL[status] || status, STATUS_TONE[status] || 'neutral', true)) + '</div>' +
      '<div class="cell optional">' + sourceTagCell(s) + '</div>' +
      '<div class="cell optional" style="font-size:11.5px;color:var(--text-2)">' +
        (last ? '对比 ' + ((last.summary && last.summary.comparedPrograms) || 0) +
          ' · 影响 ' + ((last.summary && last.summary.impactedSymbols) || 0) : '尚未分析') + '</div>' +
      '<div class="cell optional" style="font-size:11.5px;color:var(--text-2)">' + esc(relTime(s.lastSyncedAt)) + '</div>' +
      '<div class="cell actions">' +
        '<button class="icon-btn row-edit" data-edit-source="' + s.id + '" title="编辑代码源" aria-label="编辑代码源 ' + esc(s.name) + '">' +
          icon('i-edit') + '</button>' +
        '<button class="icon-btn row-delete" data-delete-source="' + s.id + '" title="删除代码源" aria-label="删除代码源 ' + esc(s.name) + '">' +
          icon('i-trash') + '</button>' +
        '<span class="row-arrow">' + icon('i-chevron-right') + '</span>' +
      '</div>' +
      '</div>';
  }).join('');
  renderImportStepper();
}

function renderImportStepper() {
  var host = $('src-stepper');
  if (!host) return;
  var steps = [
    { title: '读取代码', meta: '扫描目录与文件' },
    { title: '解析依赖', meta: 'Maven 模块与符号' },
    { title: '建立图谱', meta: '调用 / 接口 / SQL' },
    { title: '就绪', meta: '可运行分析' }
  ];
  host.innerHTML = steps.map(function (s, idx) {
    var cls = '';
    if (state.importError && idx === Math.max(0, state.importStep)) cls = 'failed';
    else if (state.importStep > idx) cls = 'done';
    else if (state.importStep === idx) cls = 'active';
    return '<div class="step ' + cls + '">' +
      '<div class="step-rail"><div class="step-dot">' +
        (cls === 'done' ? icon('i-check') : (cls === 'failed' ? icon('i-x') : String(idx + 1))) +
      '</div><div class="step-line"></div></div>' +
      '<div class="step-title">' + esc(s.title) + '</div>' +
      '<div class="step-meta">' + esc(s.meta) + '</div>' +
      '</div>';
  }).join('');
}

/* ------------------------------------------------------------------- review */

function renderReview() {
  var report = state.report;
  var counts = reviewCounts();
  renderPeerPanel(report, 'rv');
  var setText = function (id, v) { var el = $(id); if (el) el.textContent = v; };
  var setHtml = function (id, v) { var el = $(id); if (el) el.innerHTML = v; };

  var pickedSource = currentSource();
  setText('rv-crumb-source', report ? report.sourceName : (pickedSource ? pickedSource.name : '—'));
  setHtml('rv-reviewed', counts.confirmed + counts.dismissed + ' <small>/ ' + counts.total + '</small>');
  setText('rv-n-all', counts.total);
  setText('rv-n-pending', counts.pending);
  setText('rv-n-confirmed', counts.confirmed);
  setText('rv-n-dismissed', counts.dismissed);

  var statePill = $('rv-state-pill');
  if (statePill) {
    statePill.textContent = report ? '分析 #' + state.analysisId : '暂无分析';
    statePill.className = 'pill ' + (report ? 'success' : 'neutral');
  }

  var sub = $('rv-sub');
  if (sub) {
    if (report) {
      sub.innerHTML = esc(report.baseLabel || '') + ' → ' + esc(report.headLabel || '') +
        ' <span class="sep">·</span> ' + counts.total + ' 项影响，' + counts.pending + ' 项待复核' +
        ' <span class="sep">·</span> 复核结果仅保存在本地浏览器';
    } else {
      var picked = currentSource();
      sub.textContent = picked
        ? picked.name + ' 还没有分析记录，先运行一次分析。'
        : '还没有分析结果。';
    }
  }

  var statsHost = $('rv-stats');
  if (statsHost) {
    statsHost.innerHTML = [
      statTile('i-database', '对比程序', fmtNum((report && report.summary && report.summary.comparedPrograms) || 0), 'brand'),
      statTile('i-network', '命中契约', fmtNum((report && report.summary && report.summary.matchedContracts) || 0), 'brand'),
      statTile('i-alert', '高风险影响', impacts().filter(function (i) { return i.riskLevel === 'HIGH' || i.riskLevel === 'CRITICAL'; }).length, 'danger'),
      statTile('i-check-circle', '已确认', counts.confirmed, 'success')
    ].join('');
  }

  var decisions = decisionsFor(state.analysisId);
  var tab = state.reviewTab;
  var rows = impacts().filter(function (i) {
    var d = decisions[String(i.id)];
    if (tab === 'pending') return !d;
    if (tab === 'confirmed') return d && d.decision === 'confirmed';
    if (tab === 'dismissed') return d && d.decision === 'dismissed';
    return true;
  });
  rows.sort(function (a, b) { return (b.riskScore || 0) - (a.riskScore || 0); });

  var tabs = document.querySelectorAll('#rv-tabs .tab');
  for (var i = 0; i < tabs.length; i++) {
    tabs[i].classList.toggle('active', tabs[i].getAttribute('data-tab') === tab);
  }
  var tabNames = { all: '全部发现', pending: '待复核', confirmed: '已确认', dismissed: '已忽略' };
  setText('rv-findings-sub', (tabNames[tab] || '全部发现') + ' · ' + rows.length + ' 项');

  var host = $('rv-findings');
  if (host) {
    host.className = 'card-list';
    host.innerHTML = rows.length ? rows.map(function (i) {
      var d = decisions[String(i.id)];
      var selected = String(state.selectedImpactId) === String(i.id);
      var statusPill = d
        ? pill(d.decision === 'confirmed' ? '已确认' : '已忽略', d.decision === 'confirmed' ? 'success' : 'neutral', true)
        : pill('待审核', 'warning', true);
      return '<div class="card-row' + (selected ? ' selected' : '') + '" data-impact-id="' + i.id + '">' +
        '<div class="row-icon ' + (RISK_TONE[i.riskLevel] || 'neutral') + '">' + icon('i-shield') + '</div>' +
        '<div class="row-main">' +
          '<div class="row-title"><span class="sym">' + esc(shortSymbol(i.targetSymbol)) + '</span>' + statusPill + '</div>' +
          '<div class="row-desc">' + esc(i.reason) + '</div>' +
          '<div class="row-meta">' + chip(impactProgram(i)) +
            chip(TYPE_LABEL[i.impactType] || i.impactType) +
            chip('风险 ' + Math.round(i.riskScore || 0)) +
            chip('置信度 ' + i.confidence) + '</div>' +
        '</div>' +
        '<div class="row-right"><div class="row-arrow">' + icon('i-chevron-right') + '</div></div>' +
        '</div>';
    }).join('') : (state.report
      ? emptyBlock('没有符合条件的发现', '切换上方标签，或运行一次新的分析。')
      : emptyBlockForSource());
  }

  renderReviewEvidence();
}

function renderReviewEvidence() {
  var host = $('rv-evidence');
  if (!host) return;
  var impact = impacts().filter(function (i) { return String(i.id) === String(state.selectedImpactId); })[0];
  var title = $('rv-ev-title');
  var sub = $('rv-ev-sub');
  var actions = $('rv-ev-actions');
  if (!impact) {
    if (title) title.textContent = '证据';
    if (sub) sub.textContent = '选择左侧任一条影响查看证据链';
    if (actions) actions.innerHTML = '';
    host.innerHTML = '<div class="empty"><strong>尚未选择发现</strong>左侧列表中的每一条都带有变更点、传播路径与代码位置。</div>';
    return;
  }
  if (title) title.textContent = shortSymbol(impact.targetSymbol);
  if (sub) sub.innerHTML = esc(impactProgram(impact)) +
    (impact.peerModule ? ' / ' + esc(impact.peerModule) : '') +
    ' · ' + esc(TYPE_LABEL[impact.impactType] || impact.impactType) + ' · 风险 ' + Math.round(impact.riskScore || 0);
  if (actions) {
    actions.innerHTML =
      '<button class="btn btn-sm" data-dismiss-impact="' + impact.id + '">' + icon('i-eye-off') + '忽略</button>' +
      '<button class="btn btn-sm btn-primary" data-confirm-impact="' + impact.id + '">' + icon('i-check') + '确认</button>' +
      '<button class="btn btn-sm" data-open-impact="' + impact.id + '">' + icon('i-external') + '详情</button>';
  }

  var steps = [{
    symbol: impact.changedSymbol, relation: '变更起点', usage: 'NONE',
    file: impact.changedFile, line: impact.changedLine, note: CHANGE_LABEL[impact.changeType] || impact.changeType
  }].concat(impact.path || []);
  var pathHtml = steps.map(function (step, idx) {
    var last = idx === steps.length - 1;
    return '<div class="path-step">' +
      '<div class="path-rail"><div class="path-dot' + (idx === 0 ? ' start' : '') + '"></div>' + (last ? '' : '<div class="path-line"></div>') + '</div>' +
      '<div class="path-body"><div class="path-sym">' + esc(step.symbol) + '</div>' +
        '<div class="path-rel">' + esc(step.relation) +
          (step.usage && step.usage !== 'NONE' ? ' · 使用方式 <b>' + esc(step.usage) + '</b>' : '') +
          (step.file ? ' · ' + esc(shortFile(step.file)) + ':' + (step.line || 0) : '') + '</div>' +
        (step.note ? '<div class="path-rel">' + esc(step.note) + '</div>' : '') +
      '</div></div>';
  }).join('');

  var evidenceHtml = (impact.evidence || []).map(function (e) {
    return '<div class="ev-row"><div class="ev-type">' + esc(e.type) + '</div>' +
      '<div class="ev-main">' + esc(e.source) + (e.target ? ' → ' + esc(e.target) : '') + '</div>' +
      '<div class="ev-meta">' + esc(e.filePath || '') + (e.line ? ':' + e.line : '') + ' ' + esc(e.detail || '') + '</div></div>';
  }).join('');

  var d = decisionOf(impact.id);
  host.innerHTML =
    (d ? '<div class="callout ' + (d.decision === 'confirmed' ? 'brand' : '') + '" style="margin-bottom:12px">' +
      icon(d.decision === 'confirmed' ? 'i-check-circle' : 'i-eye-off') +
      '<div><strong>' + (d.decision === 'confirmed' ? '已确认影响' : '已忽略该发现') + '</strong> · ' +
      esc(fmtTime(d.at)) + (d.note ? '<br/>备注：' + esc(d.note) : '') + '</div></div>' : '') +
    '<div class="section-label">证据链</div>' +
    '<div class="path">' + (pathHtml || '<div class="empty">无路径记录</div>') + '</div>' +
    '<div class="section-label">证据明细</div>' +
    '<div class="evidence">' + (evidenceHtml || '<div class="empty">无证据记录</div>') + '</div>' +
    '<div class="section-label">建议验证的测试</div>' +
    ((impact.suggestedTests || []).length
      ? '<ul class="test-list">' + impact.suggestedTests.map(function (t) { return '<li>' + esc(t) + '</li>'; }).join('') + '</ul>'
      : '<div class="empty">未发现覆盖该符号的测试用例，建议新增针对性测试。</div>');
}

/* ------------------------------------------------------------------- impact */

function renderImpact() {
  var impact = impacts().filter(function (i) { return String(i.id) === String(state.selectedImpactId); })[0];
  if (!impact) { return; }
  var setText = function (id, v) { var el = $(id); if (el) el.textContent = v; };
  var setHtml = function (id, v) { var el = $(id); if (el) el.innerHTML = v; };

  setText('im-crumb-module', impactProgram(impact));
  setText('im-title', shortSymbol(impact.targetSymbol));
  setHtml('im-sub', esc(impactProgram(impact)) +
    (impact.peerModule ? ' / ' + esc(impact.peerModule) : '') + ' <span class="sep">·</span> ' +
    esc(shortFile(impact.targetFile)) + ':' + (impact.targetLine || 0) +
    ' <span class="sep">·</span> 来源 ' + esc(shortSymbol(impact.changedSymbol)));
  var riskPill = $('im-risk-pill');
  if (riskPill) {
    riskPill.textContent = (RISK_LABEL[impact.riskLevel] || impact.riskLevel) + ' · ' + Math.round(impact.riskScore || 0);
    riskPill.className = 'pill ' + (RISK_TONE[impact.riskLevel] || 'neutral');
  }
  var typePill = $('im-type-pill');
  if (typePill) typePill.textContent = TYPE_LABEL[impact.impactType] || impact.impactType;
  setText('im-score', Math.round(impact.riskScore || 0));
  setText('im-confidence', Math.round((impact.confidenceScore || 0) * 100) + '%');

  var flow = $('im-flow');
  if (flow) {
    var severe = impact.riskLevel === 'HIGH' || impact.riskLevel === 'CRITICAL';
    flow.innerHTML =
      '<div class="flow-card"><div class="flow-label">变更</div>' +
        '<div class="flow-value" style="font-size:12.5px;font-family:var(--mono)">' + esc(shortSymbol(impact.changedSymbol)) + '</div>' +
        '<div class="flow-note">' + esc(CHANGE_LABEL[impact.changeType] || impact.changeType || '') + ' · ' + esc(impact.changedModule) + '</div></div>' +
      '<div class="flow-card"><div class="flow-label">传播</div>' +
        '<div class="flow-value">' + (impact.depth || 0) + ' 层</div>' +
        '<div class="flow-note">' + esc((impact.path || []).length) + ' 个中间调用点，' + esc(impact.confidence) + ' 置信度</div></div>' +
      '<div class="flow-card ' + (severe ? 'accent' : 'ok') + '"><div class="flow-label">影响</div>' +
        '<div class="flow-value ' + (severe ? 'danger' : 'success') + '">' + esc(RISK_LABEL[impact.riskLevel] || impact.riskLevel) + '</div>' +
        '<div class="flow-note">' + esc(impactProgram(impact)) + ' 中 ' +
          esc(TYPE_LABEL[impact.impactType] || impact.impactType) + '</div></div>';
  }

  var caveat = $('im-caveat');
  if (caveat) caveat.style.display = (impact.confidence === 'HIGH') ? 'none' : '';

  setHtml('im-reason', esc(impact.reason));

  var steps = [{
    symbol: impact.changedSymbol, relation: '变更起点', usage: 'NONE',
    file: impact.changedFile, line: impact.changedLine, note: CHANGE_LABEL[impact.changeType] || impact.changeType
  }].concat(impact.path || []);
  setHtml('im-path', steps.map(function (step, idx) {
    var last = idx === steps.length - 1;
    return '<div class="path-step">' +
      '<div class="path-rail"><div class="path-dot' + (idx === 0 ? ' start' : '') + '"></div>' + (last ? '' : '<div class="path-line"></div>') + '</div>' +
      '<div class="path-body"><div class="path-sym">' + esc(step.symbol) + '</div>' +
        '<div class="path-rel">' + esc(step.relation) +
          (step.usage && step.usage !== 'NONE' ? ' · 使用方式 <b>' + esc(step.usage) + '</b>' : '') +
          (step.file ? ' · ' + esc(shortFile(step.file)) + ':' + (step.line || 0) : '') + '</div>' +
        (step.note ? '<div class="path-rel">' + esc(step.note) + '</div>' : '') +
      '</div></div>';
  }).join(''));

  var evidence = impact.evidence || [];
  setText('im-ev-count', evidence.length + ' 条');
  setHtml('im-evidence', evidence.length ? evidence.map(function (e) {
    return '<div class="ev-row"><div class="ev-type">' + esc(e.type) + '</div>' +
      '<div class="ev-main">' + esc(e.source) + (e.target ? ' → ' + esc(e.target) : '') + '</div>' +
      '<div class="ev-meta">' + esc(e.filePath || '') + (e.line ? ':' + e.line : '') + ' ' + esc(e.detail || '') + '</div></div>';
  }).join('') : emptyBlock('无证据记录', '该影响没有附带可展示的证据。'));

  var tests = impact.suggestedTests || [];
  setHtml('im-tests', tests.length
    ? '<ul class="test-list">' + tests.map(function (t) { return '<li>' + esc(t) + '</li>'; }).join('') + '</ul>'
    : emptyBlock('未找到覆盖测试', '建议新增针对该符号的测试用例。'));

  setHtml('im-details', [
    kvRow('其他程序', impactProgram(impact)),
    kvRow('对方模块', impact.peerModule || '—'),
    kvRow('影响符号', shortSymbol(impact.targetSymbol) + (impact.targetSignature || '')),
    kvRow('位置', shortFile(impact.targetFile) + ':' + (impact.targetLine || 0)),
    kvRow('来源变更', shortSymbol(impact.changedSymbol)),
    kvRow('变更来源模块', impact.changedModule),
    kvRow('影响类型', TYPE_LABEL[impact.impactType] || impact.impactType),
    kvRow('传播深度', (impact.depth || 0) + ' 层'),
    kvRow('置信度', impact.confidence + ' · ' + Math.round((impact.confidenceScore || 0) * 100) + '%'),
    kvRow('验证状态', impact.verificationStatus || '—')
  ].join(''));

  var factors = impact.riskFactors || {};
  var factorKeys = Object.keys(factors);
  setHtml('im-factors', factorKeys.length ? factorKeys.map(function (k) {
    var v = Math.round((factors[k] || 0) * 100);
    return '<div><div style="display:flex;justify-content:space-between;font-size:11.5px;margin-bottom:5px">' +
      '<span style="color:var(--text-2)">' + esc(FACTOR_LABEL[k] || k) + '</span>' +
      '<span style="font-weight:600;font-variant-numeric:tabular-nums">' + v + '%</span></div>' +
      '<div class="progress thin"><span style="width:' + v + '%"></span></div></div>';
  }).join('') : emptyBlock('无风险因子', '该条目没有记录风险构成。'));

  var diff = state.report && state.report.diffs ? state.report.diffs[impact.changedFile] : null;
  setHtml('im-diff', diff ? '<pre class="code">' + colorDiff(diff) + '</pre>' : emptyBlock('无文本差异', '该文件没有保存差异内容。'));
}

function colorDiff(text) {
  return String(text || '').split('\n').map(function (line) {
    var safe = esc(line);
    if (line.indexOf('+') === 0 && line.indexOf('+++') !== 0) return '<span class="add">' + safe + '</span>';
    if (line.indexOf('-') === 0 && line.indexOf('---') !== 0) return '<span class="del">' + safe + '</span>';
    return '<span class="ctx">' + safe + '</span>';
  }).join('');
}

/* ----------------------------------------------------------------- analysis */

function renderAnalysis() {
  var report = state.report;
  var s = (report && report.summary) || {};
  var setText = function (id, v) { var el = $(id); if (el) el.textContent = v; };
  var setHtml = function (id, v) { var el = $(id); if (el) el.innerHTML = v; };

  var pickedSource = currentSource();
  var selected = selectedAnalysis();
  renderAnalysisProgress();
  setText('an-crumb-source', report ? report.sourceName
    : (selected && selected.sourceName) || (pickedSource ? pickedSource.name : '—'));
  setText('an-title', report ? '分析 #' + state.analysisId + ' · ' + (MODE_LABEL[report.mode] || report.mode)
    : (selected ? '分析 #' + selected.id : '分析详情'));

  // A page with no report has three distinct meanings, and conflating them is what made a failed
  // run look like a source that was never analysed: the user picked a real record, and the page
  // answered "this source has no records".
  var failed = !!(selected && selected.status === 'FAILED');
  var running = !!(selected && (selected.status === 'RUNNING' || selected.status === 'PENDING'));

  // One page-level empty state beats five identical per-card ones.
  var emptyHost = $('an-empty');
  var body = $('an-body');
  if (emptyHost) emptyHost.classList.toggle('hidden', !!report || failed || running);
  if (body) body.classList.toggle('hidden', !report);
  if (!report && !failed && !running && emptyHost) {
    var emptySub = $('an-empty-sub');
    if (emptySub) {
      emptySub.textContent = pickedSource
        ? '为 ' + pickedSource.name + ' 运行一次扫描后，这里会展示变更清单、其他程序影响与风险构成。'
        : '添加代码源并运行一次扫描后，这里会展示变更清单、其他程序影响与风险构成。';
    }
  }
  // The pill must follow the record on screen, not merely "is there a report": a failed run is a
  // real status and reading "已完成" next to it is exactly the confusion being fixed here.
  var anState = $('an-state');
  if (anState) {
    var status = report ? 'DONE' : (selected ? selected.status : null);
    anState.textContent = STATUS_LABEL[status] || '暂无数据';
    anState.className = 'pill ' + (STATUS_TONE[status] || 'neutral');
    anState.classList.toggle('hidden', !status);
  }
  // A run with no report still knows when it happened and how long it took, so the header should
  // not blank out: "— · —" above a real failure reads like missing data rather than a stopped run.
  var subHtml = '—';
  if (report) {
    subHtml = esc(report.baseLabel || '') + ' → ' + esc(report.headLabel || '')
      + ' <span class="sep">·</span> ' + esc(fmtTime(report.generatedAt));
  } else if (selected) {
    subHtml = esc(MODE_LABEL[selected.mode] || selected.mode || '')
      + (selected.targetRef ? ' <span class="sep">·</span> ' + esc(selected.targetRef) : '')
      + ' <span class="sep">·</span> ' + esc(fmtTime(selected.createdAt));
  }
  setHtml('an-sub', subHtml);
  setText('an-files', report ? fmtNum(s.parsedFiles) : '—');
  setText('an-duration', report ? fmtDuration(s.indexMillis)
    : (selected && selected.durationMs != null ? fmtDuration(selected.durationMs) : '—'));

  var statsHost = $('an-stats');
  if (statsHost) {
    statsHost.innerHTML = [
      statTile('i-database', '对比程序', fmtNum(s.comparedPrograms), 'brand'),
      statTile('i-network', '命中契约', fmtNum(s.matchedContracts), 'brand'),
      statTile('i-code', '受影响符号', fmtNum(s.impactedSymbols), 'brand'),
      statTile('i-alert', '高风险影响', fmtNum(s.highRisk), 'danger')
    ].join('');
  }

  var modulesHost = $('an-modules');
  var modules = ((report && report.moduleImpacts) || []).slice().sort(function (a, b) {
    return (b.maxRisk || 0) - (a.maxRisk || 0);
  });
  if (modulesHost) {
    modulesHost.innerHTML = modules.length ? modules.map(function (m) {
      var selected = state.selectedModule === m.module;
      return '<div class="list-row' + (selected ? ' selected' : '') + '" data-module="' + esc(moduleProgram(m)) + '">' +
        '<div class="row-icon ' + (RISK_TONE[m.riskLevel] || 'neutral') + '">' + icon('i-package') + '</div>' +
        '<div class="row-main">' +
          '<div class="row-title"><span class="sym">' + esc(moduleProgram(m)) + '</span>' +
            (m.peerSourceName && m.module ? pill(m.module, 'neutral') : '') +
            pill((RISK_LABEL[m.riskLevel] || m.riskLevel) + ' · ' + m.maxRisk, RISK_TONE[m.riskLevel], true) + '</div>' +
          '<div class="row-meta">' + chip('影响符号 ' + m.impactedSymbols) + chip('高风险 ' + m.highRisk) +
            chip('最深 ' + m.maxDepth + ' 层') +
            (m.impactTypes || []).map(function (t) { return chip(TYPE_LABEL[t] || t); }).join('') + '</div>' +
          ((m.reasons || [])[0] ? '<div class="row-desc">' + esc((m.reasons || [])[0]) + '</div>' : '') +
        '</div>' +
        '<div class="row-right"><div class="row-arrow">' + icon('i-chevron-right') + '</div></div>' +
        '</div>';
    }).join('') : (report
      ? emptyBlock('没有跨程序影响', '本次变更没有通过共享契约传播到选中的其他程序。')
      : emptyBlockForSource());
  }

  var changes = (report && report.changes) || [];
  setText('an-change-count', changes.length + ' 项');
  var changesHost = $('an-changes');
  if (changesHost) {
    changesHost.innerHTML = changes.length
      ? '<div class="tr tr-changes head"><div class="cell">类型</div><div class="cell">符号 / 摘要</div>' +
        '<div class="cell optional">文件</div><div class="cell"></div></div>' +
        changes.map(function (c) {
          return '<div class="tr tr-changes" data-change-id="' + c.id + '">' +
            '<div class="cell">' + pill(CHANGE_LABEL[c.changeType] || c.changeType, changeTone(c.changeType)) + '</div>' +
            '<div class="cell wrap"><div class="sym">' + esc(shortSymbol(c.qualifiedName || '')) + esc(c.signature || '') + '</div>' +
              '<div class="sub">' + esc(c.summary || '') + '</div></div>' +
            '<div class="cell optional mono" style="font-size:11px;color:var(--text-2)">' + esc(shortFile(c.file)) + (c.startLine ? ':' + c.startLine : '') + '</div>' +
            '<div class="cell" style="text-align:right"><span class="row-arrow">' + icon('i-chevron-right') + '</span></div>' +
            '</div>';
        }).join('')
      : (report
        ? emptyBlock('没有语义变更', '本次比较没有检测到 AST 级别的变化。')
        : emptyBlockForSource());
  }

  var blind = (report && report.blindSpots) || [];
  var skipped = (report && report.skippedFiles) || [];
  // A skipped file is a blind spot of a different kind: the analysis is not uncertain about it,
  // it simply never read it. Listing it above the unresolved calls keeps the two apart, because
  // "we could not resolve this" and "we never looked" need different follow-up.
  var skippedRows = skipped.slice(0, 30).map(function (sk) {
    var size = sk.size < 0 ? '大小未知' : (sk.size / (1024 * 1024)).toFixed(1) + ' MB';
    return '<div class="ev-row" style="margin-bottom:6px"><div class="ev-type">文件过大未索引</div>' +
      '<div class="ev-main">' + esc(shortFile(sk.path)) + '</div>' +
      '<div class="ev-meta">' + esc(size) + ' — 超过单文件读取上限，未参与分析</div></div>';
  }).join('');
  setHtml('an-blindspots', (skippedRows + (blind.length ? blind.slice(0, 30).map(function (b) {
    return '<div class="ev-row" style="margin-bottom:6px"><div class="ev-type">未解析调用</div>' +
      '<div class="ev-main">' + esc(b.module) + ' · ' + esc(shortFile(b.file)) + ':' + b.line + '</div>' +
      '<div class="ev-meta">' + esc(b.expression) + ' — ' + esc(b.reason) + '</div></div>';
  }).join('') : '')) || (report
    ? '<div class="callout"><svg><use href="#i-check-circle"/></svg><div>跨程序扫描没有产生盲区记录；本程序内部调用不在本次扫描范围。</div></div>'
    : emptyBlockForSource()));

  var notes = (report && report.notes) || [];
  setHtml('an-notes', notes.length ? notes.map(function (n) {
    return '<div style="display:flex;gap:9px;padding:9px 0;border-bottom:1px solid var(--border)">' +
      '<span style="color:var(--text-3);flex:none">' + icon('i-info') + '</span>' +
      '<div style="font-size:12px;line-height:1.65;color:var(--text-body)">' + esc(n) + '</div></div>';
  }).join('') : (report
    ? emptyBlock('没有附加说明', '引擎未给出额外口径说明。')
    : emptyBlockForSource()));
}

/**
 * Human label for the stage a failed run died in.
 *
 * The server overwrites progress_stage with "FAILED" when a run ends badly, so the stage itself is
 * gone by the time the UI reads it. What survives is the completed/skipped lists, and the first
 * step missing from both is the one that was interrupted.
 */
function failedStageLabel(completed, skipped) {
  var reached = function (s) { return completed.indexOf(s.id) >= 0 || skipped.indexOf(s.id) >= 0; };
  var pending = PROGRESS_STEPS.filter(function (s) { return !reached(s); })[0];
  return pending ? '在「' + pending.title + '」阶段' : '分析进程';
}

/**
 * Live analysis progress. The server persists the real stage and percentage, so this panel remains
 * correct after a reload and never advances on a client-side timer.
 */
function renderAnalysisProgress() {
  var host = $('an-progress-card');
  if (!host) return;
  // Fall back to the record on screen: after a reload there is no poll running, and a failed run
  // must still explain itself without anyone having watched it happen.
  var selected = selectedAnalysis();
  var live = state.liveAnalysis || (selected && selected.status !== 'DONE' ? selected : null);
  var active = live && (live.status === 'RUNNING' || live.status === 'PENDING');
  var failed = live && live.status === 'FAILED';
  host.classList.toggle('hidden', !(active || failed));
  if (!(active || failed)) return;

  var percent = Math.max(0, Math.min(100, Number(live.progressPercent) || 0));
  var stage = live.progressStage || 'PENDING';
  var label = live.progressLabel || (live.status === 'PENDING' ? '排队中' : '分析中');
  // progressDetail is overwritten by finishAnalysis() with the failure message, so it is the
  // reason when the run failed and the currently-parsed file while it is running.
  var detail = live.progressDetail || '';
  var reason = live.message || (failed ? detail : '');
  var completed = live.progressCompleted || [];
  var skipped = live.progressSkipped || [];

  var titleEl = $('an-progress-title');
  if (titleEl) titleEl.textContent = failed ? '分析失败' : '分析进度';

  var pillEl = $('an-progress-pill');
  if (pillEl) {
    pillEl.textContent = failed ? '失败' : percent + '% · ' + label;
    pillEl.className = 'pill ' + (failed ? 'danger' : 'brand');
  }

  // The failing stage belongs in the one-line sub, where it reads as "what was happening when it
  // died"; the reason itself goes in the block below, because it is often a long message.
  var detailEl = $('an-progress-detail');
  if (detailEl) {
    detailEl.textContent = failed
      ? failedStageLabel(completed, skipped) + '失败'
        + (live.durationMs != null ? ' · 耗时 ' + fmtDuration(live.durationMs) : '')
      : (detail || '正在处理…');
  }
  // The percentage alone reads as "nothing is happening" whenever the server has no movement to
  // report, so say what the numbers mean. The step list below names the stages; this line names
  // the unit being worked through.
  var stageLabelEl = $('an-progress-stage');
  if (stageLabelEl) {
    var doneSteps = completed.length;
    stageLabelEl.textContent = failed
      ? ''
      : '第 ' + Math.min(doneSteps + 1, PROGRESS_STEPS.length) + ' / ' + PROGRESS_STEPS.length + ' 步'
        + (label && label !== '分析中' ? ' · ' + label : '');
    stageLabelEl.classList.toggle('hidden', !!failed);
  }

  var failureHost = $('an-progress-failure');
  if (failureHost) {
    failureHost.classList.toggle('hidden', !failed);
    var reasonEl = $('an-progress-failure-reason');
    if (reasonEl) {
      reasonEl.textContent = failed ? (reason || '服务端没有返回失败原因。') : '';
    }
  }

  var bar = $('an-progress-bar');
  if (bar) {
    bar.style.width = (failed ? 0 : percent) + '%';
    bar.className = failed ? 'danger' : '';
  }
  // The "run in background" affordance is meaningless once the run has stopped.
  var bgBtn = $('an-progress-background');
  if (bgBtn) bgBtn.classList.toggle('hidden', !!failed);

  // The interrupted step is the first one neither completed nor skipped. progress_stage is
  // "FAILED" by then, so matching on stage would mark nothing and leave the stepper looking like a
  // run that simply stopped between steps.
  var failedStepId = failed ? (PROGRESS_STEPS.filter(function (s) {
    return completed.indexOf(s.id) < 0 && skipped.indexOf(s.id) < 0;
  })[0] || {}).id : null;

  var stepsHost = $('an-progress-steps');
  if (stepsHost) {
    stepsHost.innerHTML = PROGRESS_STEPS.map(function (step, idx) {
      var isDone = completed.indexOf(step.id) >= 0;
      var isSkipped = skipped.indexOf(step.id) >= 0;
      var isFailed = step.id === failedStepId;
      var isActive = !isDone && !isSkipped && !isFailed && step.id === stage;
      var tone = isFailed ? 'danger' : (isDone ? 'success' : (isSkipped ? 'neutral' : (isActive ? 'brand' : '')));
      return '<div class="step ' + tone + '">' +
        '<div class="step-dot">' + (isDone ? icon('i-check') : (isSkipped ? '—' : (isFailed ? icon('i-x') : (idx + 1)))) + '</div>' +
        '<div class="step-body"><div class="step-title">' + esc(step.title) + '</div>' +
          '<div class="step-meta">' + esc(isSkipped ? '已跳过' : (isFailed ? '失败于此' : (isActive ? label : step.meta))) + '</div></div>' +
        '</div>';
    }).join('');
  }
}

function changeTone(type) {
  if (!type) return 'neutral';
  if (type.indexOf('REMOVED') >= 0 || type.indexOf('CHANGED') >= 0) return 'warning';
  if (type.indexOf('ADDED') >= 0) return 'success';
  return 'neutral';
}

/* ------------------------------------------------------------------- triage */

function renderTriage() {
  var counts = reviewCounts();
  var setText = function (id, v) { var el = $(id); if (el) el.textContent = v; };
  var setHtml = function (id, v) { var el = $(id); if (el) el.innerHTML = v; };

  setText('tr-pending', counts.pending);
  setText('tr-confirmed', counts.confirmed);

  var host = $('tr-list');
  var decisions = decisionsFor(state.analysisId);
  var high = impacts().filter(function (i) {
    return !decisions[String(i.id)] && (i.riskLevel === 'HIGH' || i.riskLevel === 'CRITICAL');
  }).sort(function (a, b) { return (b.riskScore || 0) - (a.riskScore || 0); });
  setText('tr-high-count', high.length + ' 项');
  if (host) {
    host.innerHTML = high.length ? high.slice(0, 6).map(function (i) {
      return '<div class="list-row" data-impact-id="' + i.id + '">' +
        '<div class="row-icon danger">' + icon('i-alert') + '</div>' +
        '<div class="row-main">' +
          '<div class="row-title"><span class="sym">' + esc(shortSymbol(i.targetSymbol)) + '</span>' +
            pill(RISK_LABEL[i.riskLevel] || i.riskLevel, RISK_TONE[i.riskLevel], true) + '</div>' +
          '<div class="row-desc">' + esc(i.reason) + '</div>' +
          '<div class="row-meta">' + chip(impactProgram(i)) +
            chip('置信度 ' + i.confidence) + chip('深度 ' + i.depth) + '</div>' +
        '</div>' +
        '<div class="row-right"><div class="row-arrow">' + icon('i-chevron-right') + '</div></div>' +
        '</div>';
    }).join('') : (state.report
      ? emptyBlock('高风险项已处理完', '没有待复核的高风险影响。')
      : emptyBlockForSource());
  }

  var advice = $('tr-advice');
  if (advice) {
    var highConf = impacts().filter(function (i) { return i.confidence === 'HIGH'; }).length;
    var programCount = {};
    impacts().forEach(function (i) { programCount[impactProgram(i)] = 1; });
    var contractCount = ((state.report && state.report.summary && state.report.summary.matchedContracts) || 0);
    advice.innerHTML = [
      adviceRow('i-target', '优先复核高置信度项', highConf + ' 项置信度为高的其他程序影响最可能真实发生，建议先看。'),
      adviceRow('i-database', '涉及的程序', Object.keys(programCount).length + ' 个其他程序受到影响，需要分别通知负责人。'),
      adviceRow('i-network', '契约命中', contractCount + ' 个跨程序契约命中，建议核对接口、表结构、配置与消息兼容性。')
    ].join('');
  }

  var progress = $('tr-progress');
  if (progress) {
    var done = counts.confirmed + counts.dismissed;
    var p = pct(done, counts.total);
    progress.innerHTML =
      '<div style="display:flex;justify-content:space-between;font-size:11.5px;margin-bottom:7px">' +
        '<span style="color:var(--text-2)">已复核 ' + done + ' / ' + counts.total + '</span>' +
        '<span style="font-weight:600">' + p + '%</span></div>' +
      '<div class="progress"><span style="width:' + p + '%"></span></div>' +
      '<div class="kv-list" style="margin-top:10px">' +
        kvRow('待复核', counts.pending) + kvRow('已确认', counts.confirmed) + kvRow('已忽略', counts.dismissed) +
      '</div>';
  }

  var stepper = $('tr-stepper');
  if (stepper) {
    var stages = [
      { title: '读取变更', meta: 'AST 语义差异' },
      { title: '扫描其他程序', meta: '契约匹配与传播' },
      { title: '人工复核', meta: counts.pending + ' 项待处理' },
      { title: '结论归档', meta: '本地状态' }
    ];
    stepper.innerHTML = stages.map(function (s, idx) {
      var cls = idx < 2 ? 'done' : (idx === 2 ? 'active' : '');
      return '<div class="step ' + cls + '">' +
        '<div class="step-rail"><div class="step-dot">' + (idx < 2 ? icon('i-check') : String(idx + 1)) + '</div><div class="step-line"></div></div>' +
        '<div class="step-title">' + esc(s.title) + '</div><div class="step-meta">' + esc(s.meta) + '</div></div>';
    }).join('');
  }
  var stagePill = $('tr-stage-pill');
  if (stagePill) stagePill.textContent = counts.pending > 0 ? '复核中' : '已归档';
}

function adviceRow(iconId, title, body) {
  return '<div style="display:flex;gap:11px">' +
    '<div class="row-icon brand">' + icon(iconId) + '</div>' +
    '<div><div class="row-title" style="font-size:12.5px">' + esc(title) + '</div>' +
    '<div class="row-desc" style="margin-top:2px;-webkit-line-clamp:3">' + esc(body) + '</div></div></div>';
}

/* ------------------------------------------------------------------ history */

function renderHistory() {
  var host = $('hi-table');
  if (!host) return;
  var rows = state.analyses;
  if (state.historyFilter) {
    rows = rows.filter(function (a) { return a.status === state.historyFilter; });
  }
  host.innerHTML = rows.length
    ? '<div class="tr tr-history head"><div class="cell">ID</div><div class="cell">代码源</div><div class="cell optional">状态</div>' +
      '<div class="cell optional">规模</div><div class="cell optional">时间</div><div class="cell"></div></div>' + analysesTable(rows)
    : emptyBlock('没有分析记录', '运行一次分析后记录会出现在这里。');
}

/* --------------------------------------------------------------------- map */

function renderMap(hostId, report, opts) {
  var host = $(hostId);
  if (!host) return;
  if (typeof cytoscape === 'undefined') {
    host.innerHTML = '<div class="empty">依赖图组件未加载（vendor/cytoscape.min.js）。</div>';
    return;
  }
  var nodes = new Map();
  var edges = [];
  var riskByModule = new Map();
  (report.moduleImpacts || []).forEach(function (m) {
    riskByModule.set(moduleProgram(m), m);
  });

  function ensure(name, changed) {
    if (!name || nodes.has(name)) return;
    var info = riskByModule.get(name);
    nodes.set(name, {
      data: {
        id: name, label: name,
        level: info ? info.riskLevel : 'INFO',
        score: info ? info.maxRisk : 0,
        changed: !!changed || !!(info && info.changed),
        impacted: !!info
      }
    });
  }
  ensure(report.sourceName, true);
  (report.moduleImpacts || []).forEach(function (m) {
    ensure(moduleProgram(m), m.changed);
  });

  var changedSet = new Set();
  if (report.sourceName) changedSet.add(report.sourceName);

  var seen = new Set();
  (report.moduleGraph || []).forEach(function (e) {
    var key = e.from + '->' + e.to + ':' + e.type;
    if (seen.has(key)) return;
    seen.add(key);
    ensure(e.from, false);
    ensure(e.to, false);
    var reversed = changedSet.has(e.to) && !changedSet.has(e.from);
    edges.push({
      data: {
        id: 'e' + edges.length,
        source: reversed ? e.to : e.from,
        target: reversed ? e.from : e.to,
        type: e.type,
        label: shortRelation(e.type),
        dashed: e.type === 'DEPENDS_ON'
      }
    });
  });

  var elements = Array.from(nodes.values()).concat(edges);
  if (!elements.length) {
    host.innerHTML = '<div class="empty">没有可展示的模块关系。</div>';
    return;
  }

  if (state.maps[hostId]) { state.maps[hostId].destroy(); }
  var cy = cytoscape({
    container: host,
    elements: elements,
    wheelSensitivity: 0.18,
    style: [
      {
        selector: 'node',
        style: {
          'background-color': function (ele) { return colorOf(ele.data('level')); },
          'label': 'data(label)',
          'color': themeColor('--text-body', '#4a4660'),
          'font-size': 10.5,
          'font-family': 'Inter, -apple-system, sans-serif',
          'font-weight': 500,
          'text-valign': 'bottom',
          'text-margin-y': 7,
          'text-wrap': 'wrap',
          'text-max-width': 110,
          'width': function (ele) { return 34 + Math.min(22, (ele.data('score') || 0) / 5); },
          'height': function (ele) { return 34 + Math.min(22, (ele.data('score') || 0) / 5); },
          'border-width': function (ele) { return ele.data('changed') ? 3 : 2; },
          'border-color': function (ele) {
            return ele.data('changed') ? themeColor('--primary', '#7968c5') : themeColor('--surface', '#ffffff');
          },
          'opacity': function (ele) { return (ele.data('impacted') || ele.data('changed')) ? 1 : 0.55; }
        }
      },
      {
        selector: 'edge',
        style: {
          'width': 1.3,
          'line-color': themeColor('--map-edge', '#ded8ef'),
          'target-arrow-color': themeColor('--map-arrow', '#c9c1e4'),
          'target-arrow-shape': 'triangle',
          'curve-style': 'bezier',
          'arrow-scale': 0.75,
          'line-style': function (ele) { return ele.data('dashed') ? 'dashed' : 'solid'; },
          'label': 'data(label)',
          'font-size': 7.5,
          'color': themeColor('--map-edge-label', '#a9a3bd'),
          'text-rotation': 'autorotate',
          'text-background-color': themeColor('--map-label-bg', '#fbfaff'),
          'text-background-opacity': 0.9,
          'text-background-padding': 1
        }
      },
      { selector: 'node:selected', style: { 'border-width': 3, 'border-color': themeColor('--primary', '#7968c5') } }
    ],
    layout: { name: 'preset', positions: mapPositions(report, Array.from(nodes.keys())), fit: true, padding: 26 },
    minZoom: 0.4,
    maxZoom: 2.4
  });
  state.maps[hostId] = cy;
  cy.on('tap', 'node', function (evt) {
    var name = evt.target.data('id');
    state.selectedModule = state.selectedModule === name ? null : name;
    go('analysis');
    renderAnalysis();
  });
}

function mapPositions(report, ids) {
  var changed = new Set();
  if (report.sourceName) changed.add(report.sourceName);
  if (!changed.size && ids.length) changed.add(ids[0]);

  var consumers = new Map();
  (report.moduleGraph || []).forEach(function (e) {
    if (!e.from || !e.to || e.from === e.to) return;
    if (!consumers.has(e.to)) consumers.set(e.to, new Set());
    consumers.get(e.to).add(e.from);
  });

  var layer = new Map();
  var frontier = Array.from(changed).filter(function (m) { return ids.indexOf(m) >= 0; });
  frontier.forEach(function (m) { layer.set(m, 0); });
  var depth = 0;
  while (frontier.length && depth < 8) {
    var next = [];
    frontier.forEach(function (m) {
      (consumers.get(m) || []).forEach(function (c) {
        if (!layer.has(c) && ids.indexOf(c) >= 0) { layer.set(c, depth + 1); next.push(c); }
      });
    });
    frontier = next;
    depth += 1;
  }
  var maxLayer = Math.max(0, Math.max.apply(null, Array.from(layer.values()).concat([0])));
  ids.forEach(function (id) { if (!layer.has(id)) layer.set(id, maxLayer + 1); });

  var byLayer = new Map();
  ids.forEach(function (id) {
    var l = layer.get(id);
    if (!byLayer.has(l)) byLayer.set(l, []);
    byLayer.get(l).push(id);
  });

  var positions = {};
  var width = 720;
  var rowHeight = 120;
  Array.from(byLayer.keys()).sort(function (a, b) { return a - b; }).forEach(function (l, rowIndex) {
    var members = byLayer.get(l);
    members.forEach(function (id, i) {
      var step = members.length === 1 ? 0 : (width - 120) / (members.length - 1);
      var x = members.length === 1 ? width / 2 : 60 + i * step;
      positions[id] = { x: x, y: 46 + rowIndex * rowHeight };
    });
  });
  return positions;
}

function shortRelation(type) {
  return ({
    CALLS: 'call', REFERENCES_TYPE: 'type', IMPLEMENTS: 'impl', EXTENDS: 'ext',
    EXPOSES_API: 'api', CALLS_API: 'http', EXECUTES_SQL: 'sql', READS_TABLE: 'read',
    WRITES_TABLE: 'write', USES_CONFIG: 'cfg', DEPENDS_ON: 'maven',
    PUBLISHES_TO: 'publish', SUBSCRIBES_TO: 'subscribe', USES_MESSAGE_SCHEMA: 'payload',
    ROUTES_TO: 'route', FILTERS_BY: 'filter', CROSS_PROGRAM: 'contract'
  })[type] || String(type || '').toLowerCase();
}

function colorOf(level) {
  return ({
    CRITICAL: themeColor('--risk-critical', '#c66b60'),
    HIGH: themeColor('--risk-high', '#c9915a'),
    MEDIUM: themeColor('--risk-medium', '#b78b37'),
    LOW: themeColor('--risk-low', '#368d70'),
    INFO: themeColor('--risk-info', '#c3bcd8')
  })[level] || themeColor('--risk-info', '#c3bcd8');
}

function renderMapRelations() {
  var host = $('map-relations');
  var countEl = $('map-edge-count');
  var report = state.report;
  var modePill = $('map-mode-pill');
  if (modePill) modePill.textContent = report ? (MODE_LABEL[report.mode] || report.mode) + ' · #' + state.analysisId : '暂无分析';
  var hint = $('an-map-hint');
  if (hint) {
    hint.textContent = state.selectedModule
      ? '已筛选：' + state.selectedModule + '（再次点击取消）'
      : '点击其他程序节点筛选影响明细';
  }
  if (!host) return;
  var graph = (report && report.moduleGraph) || [];
  if (countEl) countEl.textContent = report ? graph.length + ' 条关系边' : '暂无分析';
  if (!graph.length) {
    host.innerHTML = report
      ? emptyBlock('没有关系边', '本次没有命中任何跨程序契约。')
      : emptyBlockForSource();
    return;
  }
  var byType = {};
  graph.forEach(function (e) { byType[e.type] = (byType[e.type] || 0) + 1; });
  host.innerHTML = Object.keys(byType).sort(function (a, b) { return byType[b] - byType[a]; }).map(function (t) {
    return '<div class="kv-row"><span class="k">' + esc(shortRelation(t)) +
      ' <span style="color:var(--text-3)">' + esc(t) + '</span></span>' +
      '<span class="v">' + byType[t] + '</span></div>';
  }).join('');
}

/* ----------------------------------------------------------------- settings */

async function renderSettings() {
  var host = $('set-health');
  if (!host) return;
  if (!state.health) {
    try { state.health = await api('/health'); } catch (e) { state.health = null; }
  }
  var h = state.health;
  host.innerHTML = h ? [
    kvRow('服务状态', h.status === 'ok' ? '正常' : h.status),
    kvRow('数据目录', h.dataDir),
    kvRow('最大深度', h.maxDepth),
    kvRow('最大文件数', h.maxFiles),
    kvRow('AI 解读', h.aiEnabled ? '已启用' : '未启用')
  ].join('') : emptyBlock('无法读取服务状态', '请确认后端服务正在运行。');
  // Version info loads once per page load, not on every renderAll() tick.
  ensureUpdateLoaded();
}

/* ------------------------------------------------------- version & updates */

/**
 * Update state is fetched once per page load. renderAll() runs on every poll tick, so
 * re-fetching here would spam the backend; state.updateLoaded guards the first load and
 * every explicit user action (check / download / apply / rollback) refreshes it directly.
 */
var SOURCE_LABEL = {
  github: 'GitHub（本次刚拉取）',
  cache: '本地缓存',
  disabled: '已关闭',
  error: '拉取失败',
  never: '尚未检查'
};

function updateState() {
  if (!state.update) {
    state.update = {
      data: null, error: null, checking: false, busy: false, expanded: {}, pendingMsg: null,
      // The version ledger is a separate, lazily fetched resource: see loadUpdateHistory().
      history: { open: false, loaded: false, loading: false, error: null, data: null }
    };
  }
  return state.update;
}

/* --------------------------------------------------------- update notice */

/**
 * The version the user silenced, or null. Only the version string is stored: treating the whole
 * release list as "seen" would keep quiet about later releases too, which is not what
 * 「忽略此版本」 promises.
 */
function skippedUpdateVersion() {
  try { return localStorage.getItem(UPDATE_SKIP_KEY) || null; } catch (e) { return null; }
}

function skipUpdateVersion(version) {
  try { localStorage.setItem(UPDATE_SKIP_KEY, version); } catch (e) { /* storage blocked: the banner just stays */ }
}

/**
 * The notice state, in one place. Three shapes matter:
 *
 *   installable  — a newer release that really has a jar: this is the only case that should
 *                  lead the user to the download button;
 *   tagOnly      — a newer tag with nothing attached: worth saying once, but pointing the user
 *                  at "查看" would only walk them into a version they cannot install;
 *   none         — nothing new, or the user already silenced this exact version.
 */
function updateNotice() {
  var d = updateState().data;
  if (!d) return { kind: 'none' };
  var installable = !!(d.installableUpdateAvailable && d.latestInstallableVersion);
  var version = installable ? d.latestInstallableVersion : (d.latestVersion || null);
  if (!version) return { kind: 'none' };
  var newer = installable || !!d.updateAvailable;
  if (!newer) return { kind: 'none' };
  if (version === skippedUpdateVersion()) return { kind: 'none' };
  return { kind: installable ? 'installable' : 'tagOnly', version: version };
}

/**
 * One place that decides whether the banner and the rail dot are visible, called only when the
 * payload changes (boot, check now, loadUpdate) — never from renderAll(), because a poll tick
 * must not bring back a banner the user just closed.
 */
function renderUpdateNotice() {
  var d = updateState().data;
  var notice = updateNotice();
  var pending = notice.kind !== 'none';
  var banner = $('update-banner');
  if (banner) {
    // Shown on every page including settings: the banner is the one place that states the
    // version at a glance, and the settings card below it may be scrolled out of view.
    var show = pending && notice.version !== state.updateBannerClosed;
    banner.classList.toggle('hidden', !show);
    banner.classList.toggle('tag-only', notice.kind === 'tagOnly');
    if (show) {
      var ver = $('update-banner-version');
      var cur = $('update-banner-current');
      var lead = $('update-banner-lead');
      var tail = $('update-banner-tail');
      var view = banner.querySelector('[data-update-banner-view]');
      if (ver) ver.textContent = notice.version;
      if (cur) cur.textContent = d.currentVersion || '—';
      // A tag-only notice is informational: no "查看" button, because the card cannot offer a
      // download for it. The 忽略 action stays, so the notice can still be silenced.
      if (lead) lead.textContent = notice.kind === 'tagOnly' ? '发现更新的标签 ' : '发现新版本 ';
      if (tail) {
        tail.textContent = notice.kind === 'tagOnly'
          ? '，但该版本没有可下载的 jar'
          : '（当前 ' + (d.currentVersion || '—') + '）';
      }
      if (view) view.classList.toggle('hidden', notice.kind !== 'installable');
    }
  }
  var dot = $('rail-update-dot');
  if (dot) dot.classList.toggle('hidden', !pending);
}

/**
 * "查看" goes to the settings card and flashes it. The card lives inside the settings page, so
 * the page has to be switched first; the flash class is dropped on a timer (and re-triggered by
 * a forced reflow) so a second click replays the highlight instead of doing nothing visible.
 */
function showUpdateCard() {
  go('settings');
  renderSettings();
  var card = $('card-update');
  if (!card) return;
  card.scrollIntoView({ behavior: 'smooth', block: 'center' });
  card.classList.remove('flash');
  void card.offsetWidth;
  card.classList.add('flash');
  window.setTimeout(function () { card.classList.remove('flash'); }, 1700);
}

/** Closing is "not now" for this page load; only 忽略此版本 is remembered. */
function closeUpdateBanner() {
  var notice = updateNotice();
  state.updateBannerClosed = notice.version || '';
  renderUpdateNotice();
}

/**
 * Silences the version the notice is about. For a tag-only notice that is the tag, not the
 * installable version: those are different strings, and silencing the wrong one would let the
 * notice reappear on the next payload refresh.
 */
function ignoreUpdateVersion() {
  var notice = updateNotice();
  if (notice.kind === 'none' || !notice.version) return;
  skipUpdateVersion(notice.version);
  state.updateBannerClosed = null;
  renderUpdateNotice();
  toast('已忽略 ' + notice.version + '，可在设置页随时查看', 'success');
}

/**
 * Boot-time check so the banner and the dot exist before anyone opens settings. Failures are
 * swallowed: a version check is background information and a toast on every offline start is
 * noise. updateLoaded stays false on failure so the settings card still retries once.
 */
async function primeUpdateNotice() {
  var s = updateState();
  try {
    s.data = await api('/update');
    s.error = null;
    state.updateLoaded = true;
  } catch (e) {
    s.error = e && e.message ? e.message : String(e);
  }
  // Silent on purpose: a version check is background information, and a toast on every offline
  // start would be noise. The settings card still shows the error when the user goes there.
  renderUpdateNotice();
  if (state.page === 'settings') renderUpdateBody();
}

/**
 * Orders two version strings the way the server does: numeric segments first, a pre-release last.
 *
 * The list used to be ordered by publish date, which fails for the one case this feature now
 * produces: a tags-only entry has no publishedAt, so the newest version sank to the bottom of a
 * list sorted newest-first. A version is the identity of a release; it is always present and
 * always comparable, so it is the honest sort key.
 */
function compareVersions(a, b) {
  var pa = String(a == null ? "" : a).replace(/^v/i, "").split("-");
  var pb = String(b == null ? "" : b).replace(/^v/i, "").split("-");
  var na = pa[0].split(".");
  var nb = pb[0].split(".");
  var len = Math.max(na.length, nb.length);
  for (var i = 0; i < len; i++) {
    var x = parseInt(na[i], 10);
    var y = parseInt(nb[i], 10);
    if (isNaN(x)) x = 0;
    if (isNaN(y)) y = 0;
    if (x !== y) return x - y;
  }
  // A pre-release sorts below its release: 1.0.0-rc1 < 1.0.0.
  if (pa.length === 1 && pb.length > 1) return 1;
  if (pa.length > 1 && pb.length === 1) return -1;
  if (pa.length > 1 && pb.length > 1) return pa[1] < pb[1] ? -1 : pa[1] > pb[1] ? 1 : 0;
  return 0;
}

function fmtBytes(bytes) {
  if (bytes == null || isNaN(Number(bytes))) return '—';
  var n = Number(bytes);
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
  return (n / 1024 / 1024).toFixed(1) + ' MB';
}

/**
 * Minimal, deliberately dumb release-notes renderer: everything is escaped first, then
 * newlines and leading "- " bullets are turned into markup. No markdown library, so
 * nothing in the upstream release body can inject HTML or script.
 */
function renderNotes(md) {
  var lines = String(md == null ? '' : md).replace(/\r\n/g, '\n').split('\n');
  var out = [];
  var inList = false;
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i];
    var bullet = /^\s*[-*]\s+(.*)$/.exec(line);
    if (bullet) {
      if (!inList) { out.push('<ul class="release-list">'); inList = true; }
      out.push('<li>' + esc(bullet[1]) + '</li>');
      continue;
    }
    if (inList) { out.push('</ul>'); inList = false; }
    if (line.trim() === '') { out.push('<span class="release-gap"></span>'); continue; }
    // Headings are only styled, never interpreted: "## 新增" loses the hashes but stays text.
    var heading = /^\s*#{1,6}\s+(.*)$/.exec(line);
    if (heading) out.push('<div class="release-h">' + esc(heading[1]) + '</div>');
    else out.push('<div>' + esc(line) + '</div>');
  }
  if (inList) out.push('</ul>');
  return out.join('') || '<div style="color:var(--text-3)">这个版本没有填写更新说明。</div>';
}

function jarAsset(release) {
  var assets = (release && release.assets) || [];
  for (var i = 0; i < assets.length; i++) {
    if (/\.jar$/i.test(assets[i] && assets[i].name || '')) return assets[i];
  }
  return null;
}

/** Why the "应用更新" button is disabled, shown as tooltip and as an inline note. */
function applyBlockedReason(d) {
  if (!d) return '暂时无法判断当前环境是否支持自动应用。';
  if (d.runningFromJar) return '检测到无法替换运行中的 jar（Windows 文件锁或权限不足），请手动下载后替换。';
  return '当前不是以 java -jar 方式运行（可能是在 IDE 里启动），替换 jar 不会生效。';
}

/** The "current vs latest" summary block. */
function renderUpdateSummary(d) {
  var s = updateState();
  var version = d.currentVersion || '未知';
  // installableUpdateAvailable is the honest signal: "a newer release exists" and "you can
  // actually install it" are different facts once tag-only versions are listed.
  var installable = !!(d.installableUpdateAvailable && d.latestInstallableVersion);
  var right;
  if (installable) {
    right = pill('有新版本 ' + d.latestInstallableVersion, 'brand', true);
  } else if (d.updateAvailable && d.latestVersion) {
    right = pill('有更新的标签，但没有可下载的 jar', 'warning', true);
  } else if (d.latestVersion) {
    right = pill('已是最新', 'success', true);
  } else {
    right = pill('尚未获取版本信息', 'neutral');
  }
  var rows = [
    kvRow('最新版本', d.latestVersion || '—')
  ];
  // Only worth a second row when the two differ, otherwise it just repeats the line above.
  if (d.latestInstallableVersion && d.latestInstallableVersion !== d.latestVersion) {
    rows.push(kvRow('最新可安装版本', d.latestInstallableVersion));
  } else if (!d.latestInstallableVersion && d.latestVersion) {
    rows.push(kvRow('最新可安装版本', '没有可下载的 jar'));
  }
  rows.push(kvRow('上次检查', d.checkedAt ? fmtTime(d.checkedAt) : '尚未检查'));
  rows.push(kvRow('检查来源', SOURCE_LABEL[d.source] || d.source || '—'));
  if (d.repo) rows.push(kvRow('发布仓库', d.repo));
  if (d.checkIntervalMinutes) rows.push(kvRow('自动检查间隔', d.checkIntervalMinutes + ' 分钟'));

  var html = '<div class="upd-hero">' +
    '<div class="upd-version"><div class="upd-version-num">' + esc(version) + '</div>' +
    '<div class="upd-version-cap">当前运行版本</div></div>' +
    '<div class="upd-hero-side">' + right + '</div></div>';

  if (d.error) {
    html += '<div class="callout danger" style="margin-bottom:14px"><svg><use href="#i-alert-circle"/></svg>' +
      '<div><strong>检查更新失败</strong><br/>' + esc(d.error) + '</div></div>';
  }
  html += '<div class="kv-list">' + rows.join('') + '</div>';

  // Downloaded-but-not-applied jar: applying needs a restart, so say so up front.
  if (d.pending) {
    var blocked = !d.applySupported;
    html += '<div class="callout brand" style="margin-top:14px">' + icon('i-package') +
      '<div><strong>已下载 ' + esc(d.pending.version || '') + '</strong> · ' + esc(fmtBytes(d.pending.bytes)) +
      (d.pending.downloadedAt ? ' · ' + esc(fmtTime(d.pending.downloadedAt)) : '') +
      '<div style="margin-top:6px;color:var(--text-2)">下载的文件还没有替换当前 jar，点击「应用更新」后需要重启服务才会生效。</div>' +
      '<div class="upd-actions" style="margin-top:10px">' +
        '<button class="btn btn-sm btn-primary" data-update-apply' +
          (s.busy || d.busy || blocked ? ' disabled' : '') +
          (blocked
            ? ' title="' + esc(applyBlockedReason(d)) + '" aria-label="应用更新（不可用：' + esc(applyBlockedReason(d)) + '）"'
            : ' title="把已下载的 jar 替换到运行位置"') + '>' +
          icon('i-check') + '应用更新</button>' +
        '<button class="btn btn-sm" data-update-rollback' + (s.busy || d.busy ? ' disabled' : '') +
          ' title="用上一次的备份 jar 覆盖当前 jar" aria-label="回滚到上一个版本">' +
          icon('i-corner-down') + '回滚</button>' +
      '</div></div></div>';
  } else {
    html += '<div class="upd-actions" style="margin-top:14px">' +
      '<button class="btn btn-sm" data-update-rollback' + (s.busy || d.busy ? ' disabled' : '') +
        ' title="用上一次的备份 jar 覆盖当前 jar" aria-label="回滚到上一个版本">' +
        icon('i-corner-down') + '回滚到上一个版本</button>' +
      '</div>';
  }

  if (!d.applySupported) {
    html += '<div class="callout warning" style="margin-top:12px"><svg><use href="#i-alert"/></svg>' +
      '<div><strong>当前环境不支持自动应用</strong><br/>' + esc(applyBlockedReason(d)) +
      '</div></div>';
  }
  return html;
}

/** One row per release, newest first, with an expandable notes panel. */
function renderReleaseRow(release, idx) {
  var s = updateState();
  var key = String(release.version || idx);
  var open = !!s.expanded[key];
  var jar = jarAsset(release);
  var pills = [];
  if (release.current) pills.push(pill('当前版本', 'brand'));
  if (release.prerelease) pills.push(pill('预发布', 'warning'));
  if (release.newer && !release.current) pills.push(pill('可更新', 'success'));
  // A tag-only release has no GitHub release object behind it, so there are no notes and no
  // assets. Saying so beats an expandable panel that turns out to be empty.
  if (release.fromTag) pills.push(pill('仅标签', 'neutral'));
  if (release.downloaded) pills.push(pill('已下载', 'success'));

  var actions = '';
  // Nothing to expand for a tag-only entry: the notes panel would only ever say "没有填写".
  if (!release.fromTag) {
    actions += '<button class="btn btn-sm btn-ghost" data-update-notes="' + esc(key) + '" ' +
      'aria-expanded="' + (open ? 'true' : 'false') + '" aria-label="' +
      (open ? '收起 ' : '查看 ') + esc(release.version || '') + ' 的更新说明">' +
      icon(open ? 'i-chevron-down' : 'i-chevron-right') + (open ? '收起说明' : '查看说明') + '</button>';
  }

  // downloadable is the server's verdict on whether a jar really exists for this version. A
  // tag-only release has no assets at all, so rendering the button from jarAsset() alone would
  // offer a click that can only fail. The jarAsset() test stays as the fallback for payloads
  // from a server that predates the flag.
  var canDownload = release.downloadable === undefined ? !!jar : !!release.downloadable;
  if (canDownload) {
    // release.downloaded is the server's ledger; the pending jar is the older single-slot signal.
    // Either one means "you already have this file", which is what the button label reflects.
    var downloaded = !!release.downloaded ||
      !!(s.data && s.data.pending && s.data.pending.version === release.version);
    var dlTitle = jar
      ? '下载 ' + jar.name + '（' + fmtBytes(jar.size) + '）'
      : '下载版本 ' + (release.version || '');
    actions += '<button class="btn btn-sm" data-update-download="' + esc(release.version || '') + '"' +
      (s.busy || (s.data && s.data.busy) ? ' disabled' : '') +
      ' title="' + esc(dlTitle) + '" aria-label="' +
      (downloaded ? '重新下载版本 ' : '下载版本 ') + esc(release.version || '') + '">' +
      icon('i-upload') + (downloaded ? '重新下载' : '下载此版本') + '</button>';
  } else {
    // Nothing to click, but the reason has to stay readable: a pill plus a title, no dead button.
    var why = release.fromTag
      ? '这个版本只有 git tag，没有可下载的 jar 附件'
      : '这个版本没有可下载的 jar 附件';
    actions += '<span class="upd-nodl" title="' + esc(why) + '">' + pill('不可下载', 'neutral') + '</span>';
  }

  var notes = open && !release.fromTag
    ? '<div class="release-notes">' + renderNotes(release.notes) + '</div>' +
      (release.url ? '<div class="upd-notes-foot"><a href="' + esc(release.url) + '" target="_blank" rel="noopener noreferrer">' +
        '在 GitHub 上查看这个版本</a></div>' : '')
    : '';

  return '<div class="rel-row">' +
    '<div class="rel-main">' +
      '<div class="rel-head">' +
        '<span class="rel-version">' + esc(release.version || '—') + '</span>' +
        (pills.length ? '<span class="chips">' + pills.join('') + '</span>' : '') +
      '</div>' +
      '<div class="rel-meta">' +
        '<span>' + esc(release.publishedAt ? fmtTime(release.publishedAt) : '发布时间未知') + '</span>' +
        (release.name && release.name !== release.version ? '<span class="sep"></span><span>' + esc(release.name) + '</span>' : '') +
        (jar ? '<span class="sep"></span><span>' + esc(jar.name) + ' · ' + esc(fmtBytes(jar.size)) + '</span>' : '') +
        (release.downloadedAt ? '<span class="sep"></span><span>下载于 ' + esc(fmtTime(release.downloadedAt)) + '</span>' : '') +
      '</div>' +
      (release.fromTag
        ? '<div class="rel-note">这个版本只有 git tag，没有发布说明/附件。</div>'
        : '') +
    '</div>' +
    '<div class="rel-actions">' + actions + '</div>' +
    '</div>' + notes;
}

/* ------------------------------------------------------ local version ledger */

/**
 * The ledger is fetched on demand only. Entering the settings page happens on every poll tick
 * while it is open, and the ledger changes only when a check or a download happens, so eager
 * loading would cost a request per tick for data that cannot have moved.
 */
async function loadUpdateHistory() {
  var h = updateState().history;
  h.loading = true;
  h.error = null;
  renderUpdateBody();
  try {
    h.data = await api('/update/history');
    h.loaded = true;
  } catch (e) {
    // The endpoint may be missing on an older server; a failed ledger must not break the card.
    h.error = e && e.message ? e.message : String(e);
    h.data = null;
    h.loaded = false;
  } finally {
    h.loading = false;
    renderUpdateBody();
  }
}

function toggleUpdateHistory() {
  var h = updateState().history;
  h.open = !h.open;
  if (h.open && !h.loaded && !h.loading) { loadUpdateHistory(); return; }
  renderUpdateBody();
}

function historyTime(iso) {
  if (!iso) return '—';
  return '<span title="' + esc(iso) + '">' + esc(fmtTime(iso)) + '</span>';
}

/** One ledger entry: version plus the five lifecycle stamps the backend tracks. */
function renderHistoryRow(v) {
  var cells = [
    '<div class="cell"><span class="sym">' + esc(v.version || '—') + '</span>' +
      (v.fromTag ? ' ' + pill('仅标签', 'neutral') : '') + '</div>',
    '<div class="cell">' + historyTime(v.firstSeenAt) + '</div>',
    '<div class="cell">' + historyTime(v.lastSeenAt) + '</div>',
    '<div class="cell">' + historyTime(v.downloadedAt) + '</div>',
    '<div class="cell">' + historyTime(v.appliedAt) + '</div>',
    '<div class="cell">' + historyTime(v.rolledBackAt) + '</div>'
  ];
  return '<div class="tr tr-ledger">' + cells.join('') + '</div>';
}

function renderUpdateHistory() {
  var h = updateState().history;
  var head = '<div class="upd-ledger-head">' +
    '<button class="btn btn-sm btn-ghost" data-update-history aria-expanded="' + (h.open ? 'true' : 'false') + '"' +
    ' aria-label="' + (h.open ? '收起' : '展开') + '本机版本记录">' +
    icon(h.open ? 'i-chevron-down' : 'i-chevron-right') + (h.open ? '收起本机版本记录' : '展开本机版本记录') +
    '</button>' +
    (h.data && h.data.count ? '<span class="upd-ledger-count">' + esc(String(h.data.count)) + ' 个版本</span>' : '') +
    '</div>';
  if (!h.open) return head;

  var body;
  if (h.loading) {
    body = '<div class="upd-loading"><span class="spinner"></span>正在读取本机版本记录…</div>';
  } else if (h.error) {
    body = '<div class="callout warning" style="margin-bottom:10px">' + icon('i-alert') + '<div>' +
      '<strong>无法读取本机版本记录</strong><br/>' + esc(h.error) + '</div></div>' +
      '<div style="margin-top:10px"><button class="btn btn-sm" data-update-history-retry>' + icon('i-refresh') + '重试</button></div>';
  } else if (!h.data || !(h.data.versions || []).length) {
    body = emptyBlock('本机还没有版本记录', '每次检查更新后，这里会记录见过、下载过、应用过的版本。');
  } else {
    body = '<div class="upd-ledger-scroll"><div class="table">' +
      '<div class="tr tr-ledger head">' +
        '<div class="cell">版本</div><div class="cell">首次见到</div><div class="cell">最近见到</div>' +
        '<div class="cell">下载过</div><div class="cell">应用过</div><div class="cell">回滚过</div>' +
      '</div>' +
      h.data.versions.map(renderHistoryRow).join('') +
      '</div></div>' +
      '<div class="upd-ledger-foot">' +
        (h.data.firstSeenAt ? '<span>最早记录 ' + esc(fmtTime(h.data.firstSeenAt)) + '</span>' : '') +
        (h.data.lastUpdatedAt ? '<span>账本更新于 ' + esc(fmtTime(h.data.lastUpdatedAt)) + '</span>' : '') +
        (h.data.file ? '<span class="mono">' + esc(h.data.file) + '</span>' : '') +
      '</div>';
  }
  return head + '<div class="upd-ledger-body">' + body + '</div>';
}

/** Non-fatal problems from the source (e.g. the tag API failed): shown, but the card still works. */
function renderUpdateWarnings(d) {
  var warnings = (d && d.warnings) || [];
  if (!warnings.length) return '';
  return '<div class="callout warning" style="margin-bottom:14px">' + icon('i-alert') + '<div>' +
    '<strong>版本信息可能不完整</strong>' +
    '<ul class="upd-warn-list">' + warnings.map(function (w) {
      return '<li>' + esc(w) + '</li>';
    }).join('') + '</ul></div></div>';
}

function renderUpdateBody() {
  var host = $('upd-body');
  var sub = $('upd-sub');
  var checkBtn = $('upd-check');
  if (!host) return;
  var s = updateState();
  var d = s.data;

  if (checkBtn) {
    checkBtn.disabled = s.checking;
    checkBtn.innerHTML = s.checking
      ? '<span class="spinner"></span>检查中…'
      : icon('i-refresh') + '立即检查';
  }

  if (!d && s.error) {
    if (sub) sub.textContent = '无法读取更新信息';
    host.innerHTML = '<div class="callout danger"><svg><use href="#i-alert-circle"/></svg><div>' +
      '<strong>无法读取更新信息</strong><br/>' + esc(s.error) + '</div></div>' +
      '<div style="margin-top:12px">' + emptyBlock('暂时拿不到版本信息', '可以稍后点击右上角「立即检查」重试。') + '</div>';
    return;
  }
  if (!d) {
    if (sub) sub.textContent = '正在读取…';
    host.innerHTML = '<div class="upd-loading"><span class="spinner"></span>正在读取版本信息…</div>';
    return;
  }

  if (sub) {
    sub.textContent = d.enabled === false ? '自动更新已关闭' : '来自 /api/update';
  }

  if (d.enabled === false) {
    host.innerHTML = '<div class="callout warning">' + icon('i-alert') + '<div>' +
      '<strong>自动更新已关闭</strong><br/>' +
      '当前服务以 <span class="mono">code-association.update.enabled=false</span> 启动，不会检查或下载新版本。' +
      '如需启用，请去掉该配置后重启服务。</div></div>';
    return;
  }

  // The version number, not the publish date, is what orders this list. A tags-only entry has no
  // publishedAt at all (the tags API does not report one), so sorting by date buried the newest
  // version at the bottom - exactly where nobody looks for it. The server already returns the list
  // newest-first by version, and re-sorting by date only existed to defend against an unordered
  // payload; a version comparison keeps that guarantee without losing the tag-only entries.
  var releases = (d.releases || []).slice().sort(function (a, b) {
    return compareVersions(b.version, a.version);
  });

  var html = '';
  // A successful apply/rollback only swaps the file on disk; the running process still serves
  // the old code, so the restart instruction has to stay visible instead of being a toast.
  //
  // Clearing it is keyed on the process start time, not on the version: a JVM keeps reporting
  // the version it booted with, so after applying a jar the running version can equal the
  // applied one (re-applying what you already run) while the restart has not happened at all.
  // Once the server reports a start time later than the swap, the restart really did happen.
  // Both values are epoch millis produced by the server, so this comparison never mixes the
  // browser's clock or timezone with the server's.
  if (s.pendingMsg && d.startedAt != null && s.pendingMsg.swappedAt != null &&
      Number(d.startedAt) > Number(s.pendingMsg.swappedAt)) {
    s.pendingMsg = null;
  }
  if (s.pendingMsg) {
    html += '<div class="callout brand" style="margin-bottom:14px">' + icon('i-check-circle') + '<div>' +
      '<strong>' + (s.pendingMsg.kind === 'rollback' ? '已回滚到 ' : '已替换为 ') +
      esc(s.pendingMsg.version || '目标版本') + '</strong>' +
      '<div style="margin-top:4px">jar 已写入磁盘，但当前进程仍在用旧代码。重启服务后新版本才会生效。</div>' +
      '<div class="restart-cmd mono">POSIX：<code>./stop.sh &amp;&amp; ./start.sh</code></div>' +
      '<div class="restart-cmd mono">Windows：先运行 <code>stop.bat</code>，再运行 <code>start.bat</code></div>' +
      '</div></div>';
  }
  html += renderUpdateWarnings(d);
  html += renderUpdateSummary(d);
  html += '<div class="section-label">历史版本</div>';
  html += releases.length
    ? '<div class="rel-list">' + releases.map(renderReleaseRow).join('') + '</div>'
    : emptyBlock('没有版本记录', '检查一次后这里会列出 GitHub 上发布的版本。');
  html += '<div class="section-label">本机版本记录</div>' + renderUpdateHistory();
  host.innerHTML = html;
}

/** First load only; renderAll() calls this on every tick, so keep it idempotent. */
async function ensureUpdateLoaded() {
  var s = updateState();
  if (state.updateLoaded) { renderUpdateBody(); return; }
  state.updateLoaded = true;
  await loadUpdate();
}

async function loadUpdate() {
  var s = updateState();
  try {
    var data = await api('/update');
    s.data = data;
    s.error = null;
    // The ledger moves whenever a download or an apply lands, so a refresh of the payload is
    // also the moment to drop the cached ledger; it is re-fetched only if it is on screen.
    if (s.history.loaded) {
      s.history.loaded = false;
      if (s.history.open) loadUpdateHistory();
    }
  } catch (e) {
    s.error = e && e.message ? e.message : String(e);
  }
  renderUpdateNotice();
  renderUpdateBody();
}

async function checkUpdateNow() {
  var s = updateState();
  if (s.checking) return;
  s.checking = true;
  renderUpdateBody();
  try {
    var data = await api('/update/check', { method: 'POST' });
    s.data = data;
    s.error = null;
    state.updateLoaded = true;
    // A fresh check is an explicit "tell me again", so a banner closed with the X may come back;
    // the persisted 忽略 choice still wins inside updateNotice().
    state.updateBannerClosed = null;
    if (s.history.loaded) {
      s.history.loaded = false;
      if (s.history.open) loadUpdateHistory();
    }
    if (data && data.error) toast('检查完成，但拉取版本信息失败', 'error');
    else if (data && data.installableUpdateAvailable) toast('发现新版本 ' + data.latestInstallableVersion, 'success');
    else if (data && data.updateAvailable) toast('发现更新的标签 ' + data.latestVersion + '，但没有可下载的 jar');
    else toast('已是最新版本', 'success');
  } catch (e) {
    s.error = e && e.message ? e.message : String(e);
    toast('检查更新失败：' + s.error, 'error');
  } finally {
    s.checking = false;
    renderUpdateNotice();
    renderUpdateBody();
  }
}

async function downloadVersion(version) {
  var s = updateState();
  if (s.busy) return;
  s.busy = true;
  renderUpdateBody();
  try {
    var res = await api('/update/download', { method: 'POST', body: JSON.stringify({ version: version }) });
    toast('已下载 ' + ((res && res.version) || version) + (res && res.bytes ? '（' + fmtBytes(res.bytes) + '）' : ''), 'success');
    await loadUpdate();
  } catch (e) {
    toast('下载失败：' + (e && e.message ? e.message : e), 'error');
  } finally {
    s.busy = false;
    renderUpdateBody();
  }
}

async function applyUpdate() {
  var s = updateState();
  if (s.busy) return;
  s.busy = true;
  s.pendingMsg = null;
  renderUpdateBody();
  try {
    var res = await api('/update/apply', { method: 'POST' });
    // Remember when the swap happened (server clock): it is the only reliable
    // "not restarted yet" signal, see the guard in renderUpdateBody().
    s.pendingMsg = { kind: 'apply', version: res && res.version, backupPath: res && res.backupPath,
                     swappedAt: res && res.swappedAt };
    toast('已替换 jar，请重启服务生效', 'success');
  } catch (e) {
    toast('应用更新失败：' + (e && e.message ? e.message : e), 'error');
  } finally {
    s.busy = false;
    try { await loadUpdate(); } catch (ignored) { renderUpdateBody(); }
  }
}

async function rollbackUpdate() {
  var s = updateState();
  if (s.busy) return;
  s.busy = true;
  s.pendingMsg = null;
  renderUpdateBody();
  try {
    var res = await api('/update/rollback', { method: 'POST' });
    s.pendingMsg = { kind: 'rollback', version: res && res.version, swappedAt: res && res.swappedAt };
    toast('已回滚，请重启服务生效', 'success');
  } catch (e) {
    toast('回滚失败：' + (e && e.message ? e.message : e), 'error');
  } finally {
    s.busy = false;
    try { await loadUpdate(); } catch (ignored) { renderUpdateBody(); }
  }
}

/* ------------------------------------------------------------- render master */

function renderAll() {
  renderNavSources();
  renderNavCounts();
  renderOverview();
  renderSourcesPage();
  renderReview();
  renderImpact();
  renderAnalysis();
  renderTriage();
  renderHistory();
  renderMapRelations();
  if (state.page === 'settings') renderSettings();
  renderMapsFor(state.page);
}
/* ------------------------------------------------------------------ drawers */

function openImpactDrawer(impact) {
  if (!impact) return;
  $('drawer-eyebrow').textContent = '影响详情';
  $('drawer-title').textContent = shortSymbol(impact.targetSymbol);
  var steps = [{
    symbol: impact.changedSymbol, relation: '变更起点', usage: 'NONE',
    file: impact.changedFile, line: impact.changedLine, note: CHANGE_LABEL[impact.changeType] || impact.changeType
  }].concat(impact.path || []);
  var factors = impact.riskFactors || {};
  var factorRows = Object.keys(factors).map(function (k) {
    var v = Math.round((factors[k] || 0) * 100);
    return '<div class="kv-row"><span class="k">' + esc(FACTOR_LABEL[k] || k) + '</span>' +
      '<span class="v" style="display:flex;align-items:center;gap:8px">' +
      '<span class="progress thin" style="width:120px"><span style="width:' + v + '%"></span></span>' + v + '%</span></div>';
  }).join('');
  $('drawer-body').innerHTML =
    '<div class="chips" style="margin-bottom:12px">' +
      pill(RISK_LABEL[impact.riskLevel] || impact.riskLevel, RISK_TONE[impact.riskLevel], true) +
      pill(TYPE_LABEL[impact.impactType] || impact.impactType, 'brand') +
      pill(impactProgram(impact), 'neutral') + '</div>' +
    '<div class="kv-list">' +
      kvRow('其他程序', impactProgram(impact)) +
      kvRow('对方模块', impact.peerModule || '—') +
      kvRow('影响符号', shortSymbol(impact.targetSymbol) + (impact.targetSignature || '')) +
      kvRow('位置', shortFile(impact.targetFile) + ':' + (impact.targetLine || 0)) +
      kvRow('来源变更', shortSymbol(impact.changedSymbol)) +
      kvRow('风险分', Math.round(impact.riskScore || 0)) +
      kvRow('置信度', impact.confidence + ' · ' + Math.round((impact.confidenceScore || 0) * 100) + '%') +
      kvRow('传播深度', (impact.depth || 0) + ' 层') +
    '</div>' +
    '<div class="section-label">原因</div>' +
    '<div style="font-size:12.5px;line-height:1.7;color:var(--text-body)">' + esc(impact.reason) + '</div>' +
    '<div class="section-label">风险构成</div><div class="kv-list">' + factorRows + '</div>' +
    '<div class="section-label">影响路径</div>' +
    '<div class="path">' + steps.map(function (step, idx) {
      var last = idx === steps.length - 1;
      return '<div class="path-step">' +
        '<div class="path-rail"><div class="path-dot' + (idx === 0 ? ' start' : '') + '"></div>' + (last ? '' : '<div class="path-line"></div>') + '</div>' +
        '<div class="path-body"><div class="path-sym">' + esc(step.symbol) + '</div>' +
          '<div class="path-rel">' + esc(step.relation) +
          (step.usage && step.usage !== 'NONE' ? ' · 使用方式 <b>' + esc(step.usage) + '</b>' : '') +
          (step.file ? ' · ' + esc(shortFile(step.file)) + ':' + (step.line || 0) : '') + '</div></div></div>';
    }).join('') + '</div>' +
    '<div class="section-label">证据</div>' +
    '<div class="evidence">' + ((impact.evidence || []).map(function (e) {
      return '<div class="ev-row"><div class="ev-type">' + esc(e.type) + '</div>' +
        '<div class="ev-main">' + esc(e.source) + (e.target ? ' → ' + esc(e.target) : '') + '</div>' +
        '<div class="ev-meta">' + esc(e.filePath || '') + (e.line ? ':' + e.line : '') + '</div></div>';
    }).join('') || '<div class="empty">无证据记录</div>') + '</div>';
  $('drawer').classList.remove('hidden');
}

function openChangeDrawer(change) {
  if (!change) return;
  $('drawer-eyebrow').textContent = '变更详情';
  $('drawer-title').textContent = CHANGE_LABEL[change.changeType] || change.changeType;
  var diff = state.report && state.report.diffs ? state.report.diffs[change.file] : null;
  var related = (state.report && state.report.impacts || []).filter(function (i) { return i.changeId === change.id; });
  $('drawer-body').innerHTML =
    '<div class="chips" style="margin-bottom:12px">' +
      pill(CHANGE_LABEL[change.changeType] || change.changeType, changeTone(change.changeType), true) +
      pill(change.module, 'brand') + '</div>' +
    '<div class="kv-list">' +
      kvRow('符号', shortSymbol(change.qualifiedName || '') + (change.signature || '')) +
      kvRow('文件', change.file + (change.startLine ? ':' + change.startLine : '')) +
      kvRow('变更前', change.oldValue || '—') +
      kvRow('变更后', change.newValue || '—') +
      kvRow('风险权重', String(change.riskSeed == null ? '—' : change.riskSeed)) +
    '</div>' +
    '<div class="section-label">语义摘要</div>' +
    '<div style="font-size:12.5px;line-height:1.7;color:var(--text-body)">' + esc(change.summary || '—') + '</div>' +
    ((change.tags || []).length ? '<div class="section-label">标签</div><div class="chips">' +
      change.tags.map(function (t) { return chip(t); }).join('') + '</div>' : '') +
    (diff ? '<div class="section-label">代码差异</div><pre class="code">' + colorDiff(diff) + '</pre>' : '') +
    '<div class="section-label">受此变更影响的符号</div>' +
    (related.length ? '<div class="evidence">' + related.map(function (i) {
      return '<div class="ev-row" style="cursor:pointer" data-impact-id="' + i.id + '">' +
        '<div class="ev-type">' + esc(RISK_LABEL[i.riskLevel] || i.riskLevel) + ' · ' +
          esc(impactProgram(i)) + '</div>' +
        '<div class="ev-main">' + esc(shortSymbol(i.targetSymbol)) + '</div>' +
        '<div class="ev-meta">' + esc(i.reason) + '</div></div>';
    }).join('') + '</div>' : '<div class="empty">未发现跨符号影响。</div>');
  $('drawer').classList.remove('hidden');
}

/* ------------------------------------------------------------------- modals */

function openModal(id) { var el = $(id); if (el) el.classList.remove('hidden'); }
function closeModal(id) { var el = $(id); if (el) el.classList.add('hidden'); }

function openSourceModal() {
  // Always starts in add mode, even if the dialog was last used to edit something.
  resetSourceModal();
  renderTagInput('m');
  syncBrowseButtons();
  openModal('modal-source');
  setTimeout(function () { var f = $('m-location'); if (f) f.focus(); }, 60);
}

/* ---------------------------------------------------------- folder picker */

/* A browser file input never reveals the absolute path of the chosen folder, so the picker is
   driven by GET /api/fs/dirs. It lists directories only; nothing is read. */
/* Remote sources take a URL, so the folder button only applies to local types. */
function syncBrowseButtons() {
  var pairs = [['src-type', 'src-location'], ['m-type', 'm-location']];
  pairs.forEach(function (pair) {
    var typeEl = $(pair[0]);
    var input = $(pair[1]);
    if (!typeEl || !input) return;
    var local = typeEl.value === 'LOCAL_DIR' || typeEl.value === 'LOCAL_GIT';
    var btn = input.parentNode.querySelector('[data-pick-dir]');
    if (btn) {
      btn.disabled = !local;
      btn.title = local ? '选择文件夹' : '远程仓库请直接填写地址';
    }
  });
}

function openDirPicker(targetInputId) {
  var input = $(targetInputId);
  if (!input) return;
  var err = $('pk-error');
  if (err) err.style.display = 'none';
  state.picker.target = targetInputId;
  state.picker.selected = null;
  // Cleared so a failed first load cannot leave 上一级 acting on the previous dialog's folder.
  state.picker.parent = null;
  $('pk-hidden').checked = false;
  openModal('modal-picker');

  // Start from the value already typed when it points at a real folder, else from the home dir.
  var current = (input.value || '').trim();
  var start = current && current.indexOf('://') < 0 ? current : '';
  loadDir(start);
}

async function loadDir(path) {
  var list = $('pk-list');
  var err = $('pk-error');
  list.innerHTML = '<div class="empty-hint">读取中…</div>';
  try {
    var params = [];
    if (path) params.push('path=' + encodeURIComponent(path));
    if ($('pk-hidden').checked) params.push('hidden=true');
    var data = await api('/fs/dirs' + (params.length ? '?' + params.join('&') : ''));
    state.picker.path = data.path;
    // The parent comes from the server, never from string surgery on the path: "/Users" has no
    // parent *segment* yet its parent is "/", and Windows paths are separated by backslashes, so
    // a client-side regex silently stops one level short of the root. The listing is the only
    // place that knows the real answer, so keep it and let 上一级 just replay it.
    state.picker.parent = data.parent || null;
    state.picker.selected = data.path;
    $('pk-path').value = data.path;
    $('pk-selected').textContent = '已选：' + data.path;
    $('pk-up').disabled = !state.picker.parent;
    state.picker.currentGit = !!data.git;
    renderPickerRoots(data.roots || []);
    renderPickerList(data.entries || [], data.truncated);
    if (err) err.style.display = 'none';
  } catch (e) {
    list.innerHTML = '<div class="empty-hint">无法读取该目录</div>';
    if (err) {
      err.style.display = '';
      err.className = 'callout danger';
      err.innerHTML = '<svg><use href="#i-alert-circle"/></svg><div>' + esc(e.message) + '</div>';
    }
  }
}

function renderPickerRoots(roots) {
  var host = $('pk-roots');
  host.innerHTML = roots.map(function (r) {
    return '<button class="picker-root" type="button" data-pk-root="' + esc(r.path) + '">' + esc(r.label) + '</button>';
  }).join('');
}

function renderPickerList(entries, truncated) {
  var host = $('pk-list');
  if (!entries.length) {
    host.innerHTML = '<div class="empty-hint">这个文件夹下没有子文件夹，可以直接选择它。</div>';
    return;
  }
  var rows = entries.map(function (e) {
    var tags = '';
    if (e.git) tags += '<span class="pill brand">git</span>';
    if (e.maven) tags += '<span class="pill neutral">maven</span>';
    return '<button class="picker-row" type="button" role="option" data-pk-dir="' + esc(e.path) + '">' +
      '<svg><use href="#' + (e.git ? 'i-git' : 'i-folder') + '"/></svg>' +
      '<span class="nm">' + esc(e.name) + '</span>' +
      '<span class="tags">' + tags + '</span></button>';
  }).join('');
  host.innerHTML = rows + (truncated
    ? '<div class="empty-hint">目录过大，仅显示前 1000 项</div>' : '');
}

function chooseCurrentDir() {
  var target = state.picker.target;
  var path = state.picker.selected;
  if (!target || !path) return;
  var input = $(target);
  if (input) input.value = path;
  closeModal('modal-picker');
  // Picking a folder usually also answers "what is it": prefill name and type when empty.
  autoFillFromPath(path);
  toast('已选择：' + path, 'success');
}

/* The name is a label, so it is derived from the location instead of being typed.
   This mirrors SourceNames on the server, which stays authoritative. */
function deriveSourceName(location, type) {
  var raw = String(location || '').trim();
  if (!raw) return '';
  var remote = type === 'GITHUB' || type === 'GITLAB' || type === 'GIT';
  if (remote) {
    var url = raw;
    if (url.indexOf('://') >= 0) {
      url = url.slice(url.indexOf('://') + 3);
      var slash = url.indexOf('/');
      url = slash < 0 ? '' : url.slice(slash + 1);
    } else if (url.indexOf('@') >= 0 && url.indexOf(':') > url.indexOf('@')) {
      url = url.slice(url.indexOf(':') + 1);
    }
    url = url.replace(/[?#].*$/, '').replace(/\/+$/, '');
    if (/\.git$/.test(url)) url = url.slice(0, -4);
    return url;
  }
  var clean = raw.replace(/[\/]+$/, '');
  var parts = clean.split(/[\/]/);
  return parts[parts.length - 1] || clean;
}

/**
 * Loads the branches of a location into the source form's dropdown.
 *
 * Runs whenever the location, type or credential field changes, debounced so typing a path does
 * not fire a request per keystroke. The lookup is read-only on the server (ls-remote for remote
 * URLs, a direct read for local repositories) and never blocks saving: an unreachable repository
 * leaves the free-text fallback in place.
 */
var refLookup = { timer: null, seq: 0 };

function scheduleRefLookup(prefix) {
  if (refLookup.timer) clearTimeout(refLookup.timer);
  refLookup.timer = setTimeout(function () { loadRefOptions(prefix); }, 420);
}

async function loadRefOptions(prefix) {
  var typeEl = $(prefix + '-type');
  var locEl = $(prefix + '-location');
  var tokenEl = $(prefix + '-token');
  var select = $(prefix + '-ref');
  var hint = $(prefix + '-ref-hint');
  if (!typeEl || !locEl || !select) return;

  var location = locEl.value.trim();
  var type = typeEl.value;
  if (!location) {
    resetRefSelect(select, hint);
    return;
  }
  // Remote URLs are only worth querying once they look complete.
  if ((type === 'GITHUB' || type === 'GITLAB' || type === 'GIT') && location.indexOf('://') < 0
      && location.indexOf('@') < 0) {
    resetRefSelect(select, hint);
    return;
  }

  var seq = ++refLookup.seq;
  select.disabled = true;
  if (hint) hint.textContent = '正在读取分支…';
  try {
    var params = ['type=' + encodeURIComponent(type), 'location=' + encodeURIComponent(location)];
    if (tokenEl && tokenEl.value.trim()) params.push('credentialEnv=' + encodeURIComponent(tokenEl.value.trim()));
    var data = await api('/refs?' + params.join('&'));
    if (seq !== refLookup.seq) return; // a newer lookup already started
    var previous = select.value;
    var branches = data.branches || [];
    var tags = data.tags || [];
    select.innerHTML = '<option value="">（自动检测）</option>' +
      (branches.length ? '<optgroup label="分支">' + branches.map(function (b) {
        return '<option value="' + esc(b) + '">' + esc(b) + '</option>';
      }).join('') + '</optgroup>' : '') +
      (tags.length ? '<optgroup label="标签">' + tags.map(function (t) {
        return '<option value="' + esc(t) + '">' + esc(t) + '</option>';
      }).join('') + '</optgroup>' : '');
    // Keep the user's choice; otherwise preselect the detected default.
    if (previous && (branches.indexOf(previous) >= 0 || tags.indexOf(previous) >= 0)) {
      select.value = previous;
    } else if (data.defaultRef) {
      select.value = data.defaultRef;
    }
    if (hint) {
      hint.textContent = branches.length || tags.length
        ? '已读取 ' + branches.length + ' 个分支' + (tags.length ? '、' + tags.length + ' 个标签' : '') +
          (select.value ? '，当前选择 ' + select.value : '')
        : (data.note || '没有读到分支，可留空由服务端自动选择');
    }
  } catch (e) {
    if (seq !== refLookup.seq) return;
    resetRefSelect(select, hint);
    if (hint) hint.textContent = '读取分支失败：' + e.message;
  } finally {
    // Always release the field, otherwise a slow or failed lookup blocks the form.
    if (seq === refLookup.seq) {
      select.disabled = false;
      if (hint && hint.textContent === '正在读取分支…') {
        hint.textContent = '没有读到分支，可留空由服务端自动选择';
      }
    }
  }
}

function resetRefSelect(select, hint) {
  select.innerHTML = '<option value="">（自动检测）</option>';
  select.value = '';
  select.disabled = false;
  if (hint) hint.textContent = '填写位置后自动读取分支';
}

/* Keeps the read-only name preview in sync with the location and type fields. */
function refreshNamePreview(prefix) {
  var loc = $(prefix + '-location');
  var type = $(prefix + '-type');
  var preview = $(prefix + '-name-preview');
  if (!loc || !type || !preview) return;
  preview.value = deriveSourceName(loc.value, type.value);
}

/* Picking a git working copy answers "what is it", so nudge the type. */
function autoFillFromPath(path) {
  var modal = !$('modal-source').classList.contains('hidden');
  var typeField = $(modal ? 'm-type' : 'src-type');
  if (typeField && typeField.value === 'LOCAL_DIR' && state.picker.currentGit) {
    typeField.value = 'LOCAL_GIT';
  }
  refreshNamePreview(modal ? 'm' : 'src');
  scheduleRefLookup(modal ? 'm' : 'src');
}

/* ------------------------------------------------------------ delete source */

var pendingDeleteSource = null;

/**
 * Opens the confirmation for removing a source.
 *
 * Deleting also removes its analyses and, for remote sources, the cached clone - so it asks for
 * the name to be typed rather than accepting a single click.
 */
function openDeleteSource(sourceId) {
  var source = state.sources.filter(function (s) { return s.id === Number(sourceId); })[0];
  if (!source) return;
  pendingDeleteSource = source;
  var count = state.analyses.filter(function (a) { return a.sourceId === source.id; }).length;
  $('ds-sub').textContent = source.name;
  $('ds-warning').textContent = count
    ? '删除后该代码源及其 ' + count + ' 条分析记录将从本机移除，无法撤销。代码本身不会被修改。'
    : '删除后该代码源将从本机移除，无法撤销。代码本身不会被修改。';
  $('ds-detail').innerHTML = [
    kvRow('名称', source.name),
    kvRow('类型', sourceTypeLabel(source.type)),
    kvRow('位置', source.location),
    kvRow('分析记录', count + ' 条')
  ].join('');
  var input = $('ds-confirm');
  input.value = '';
  input.placeholder = source.name;
  $('ds-save').disabled = true;
  openModal('modal-delete-source');
  setTimeout(function () { input.focus(); }, 60);
}

async function confirmDeleteSource() {
  var source = pendingDeleteSource;
  if (!source) return;
  var btn = $('ds-save');
  btn.disabled = true;
  try {
    await api('/sources/' + source.id, { method: 'DELETE' });
    closeModal('modal-delete-source');
    // If the deleted source was the one on screen, clear the workspace rather than show a ghost.
    if (state.sourceId === source.id) {
      state.sourceId = null;
      state.report = null;
      state.analysisId = null;
      state.selectedImpactId = null;
      state.selectedModule = null;
    }
    pendingDeleteSource = null;
    await refreshAll(true);
    toast('已删除代码源 ' + source.name, 'success');
  } catch (e) {
    showModalError('ds-error', '删除失败：' + e.message);
    toast('删除失败：' + e.message, 'error');
    btn.disabled = false;
  }
}

async function openRunModal() {
  var select = $('mr-source');
  if (!state.sources.length) {
    toast('请先添加一个代码源', 'error');
    openSourceModal();
    return;
  }
  select.innerHTML = state.sources.map(function (s) {
    return '<option value="' + s.id + '">' + esc(s.name) + ' · ' + esc(s.type) + '</option>';
  }).join('');
  // Preselect whatever the workspace is pointed at, not merely the first source.
  var current = state.sources[0];
  var picked = currentSource();
  if (picked) {
    current = picked;
    select.value = String(picked.id);
  } else if (state.analysisId != null) {
    var found = state.analyses.filter(function (a) { return a.id === state.analysisId; })[0];
    if (found) {
      var src = state.sources.filter(function (s) { return s.id === found.sourceId; })[0];
      if (src) { current = src; select.value = String(src.id); }
    }
  }
  $('mr-target').value = current.defaultRef || '';
  $('mr-base').value = '';
  $('mr-pr').value = '';
  $('mr-mode').value = 'GIT_DIFF';
  $('mr-pr-wrap').classList.add('hidden');
  $('mr-base-wrap').classList.remove('hidden');
  $('mr-error').style.display = 'none';
  openModal('modal-run');
  // Branches come from the repository itself; the pickers are suggestions, not free text.
  await Promise.all([loadRefs(current.id), renderPeerPicker(current.id)]);
}

/**
 * Programs that can be compared against, as a checkbox list.
 *
 * The list excludes the program being analysed: comparing a program with itself is not a
 * cross-program question, and the server skips it anyway.
 */
async function renderPeerPicker(ownId) {
  var host = $('mr-peers');
  if (!host) return;
  host.innerHTML = '<div class="peer-empty">读取中…</div>';
  var peers = [];
  try {
    peers = await api('/peers' + (ownId ? '?exclude=' + ownId : ''));
  } catch (e) {
    host.innerHTML = '<div class="peer-empty">无法读取其他程序：' + esc(e.message) + '</div>';
    return;
  }
  if (!peers.length) {
    host.innerHTML = '<div class="peer-empty">还没有其他程序可比较，先添加一个代码源。</div>';
    return;
  }
  host.innerHTML = peers.map(function (p) {
    return '<label class="peer-row">' +
      '<input type="checkbox" value="' + p.id + '"/>' +
      '<span class="nm">' + esc(p.name) + '</span>' +
      '<span class="tags">' + pill(sourceTypeLabel(p.type), 'neutral') + '</span>' +
      '</label>';
  }).join('');
}

function selectedPeerIds() {
  var out = [];
  document.querySelectorAll('#mr-peers input:checked').forEach(function (cb) {
    var n = Number(cb.value);
    if (n > 0) out.push(n);
  });
  return out;
}

async function loadRefs(sourceId) {
  var list = $('mr-refs');
  if (!list) return;
  list.innerHTML = '';
  try {
    var refs = await api('/sources/' + sourceId + '/refs');
    var options = [].concat(refs.branches || [], refs.tags || []);
    list.innerHTML = options.map(function (r) { return '<option value="' + esc(r) + '"></option>'; }).join('');
    if (refs.defaultRef && !$('mr-target').value) $('mr-target').value = refs.defaultRef;
  } catch (e) { /* refs are a convenience only */ }
}

function showModalError(hostId, message) {
  var host = $(hostId);
  if (!host) return;
  host.style.display = '';
  host.className = 'callout danger';
  host.innerHTML = '<svg><use href="#i-alert-circle"/></svg><div>' + esc(message) + '</div>';
}

/**
 * Creates or updates a source.
 *
 * PUT is a full replacement of tags on the server, so the complete array is always sent — a
 * partial update would silently drop every tag the user did not touch.
 */
async function saveSourceForm(type, location, ref, token, tags) {
  var body = {
    type: type, location: location,
    defaultRef: ref || null, credentialEnv: token || null,
    tags: normalizeTags(tags || [])
  };
  if (!body.location) throw new Error('位置必填');
  var editing = state.editingSourceId;
  var saved = editing == null
    ? await api('/sources', { method: 'POST', body: JSON.stringify(body) })
    : await api('/sources/' + editing, { method: 'PUT', body: JSON.stringify(body) });
  await loadSources();
  await loadTags();
  renderAll();
  return saved;
}


/* --------------------------------------------------- import / export */
/*
 * The export is a plain link, not a fetch: the server already sets Content-Disposition, and letting
 * the browser handle the download keeps a multi-megabyte file out of JS memory. The import reads the
 * file in the browser and posts only the parsed JSON, so there is no multipart endpoint to secure
 * and a malformed file is rejected before it ever reaches the server.
 */

function exportSources() {
  if (!state.sources.length) {
    toast('还没有代码源可导出', 'error');
    return;
  }
  // A hidden iframe would be needed for a POST; a GET download is enough here.
  window.location = '/api/sources/export';
  toast('已开始下载代码源 JSON');
}

/**
 * Sends a parsed import payload and renders the outcome.
 *
 * Partial success is the normal case (a file exported from a newer build, or a path that does not
 * exist on this machine), so the counts are always shown and the errors are listed individually
 * rather than collapsed into "导入失败".
 */
async function importSourcesPayload(payload) {
  var host = $('xfer-result');
  if (host) host.innerHTML = '<div class="empty-hint">导入中…</div>';
  try {
    var result = await api('/sources/import', { method: 'POST', body: JSON.stringify(payload) });
    renderImportResult(result);
    await refreshAll(false);
    await loadTags();
    renderNavSources();
    renderSourcesPage();
    var touched = (result.created || 0) + (result.updated || 0);
    toast(touched ? '导入完成：新建 ' + (result.created || 0) + ' 个，更新 ' + (result.updated || 0) + ' 个'
      : '没有可导入的代码源', touched ? 'success' : 'error');
  } catch (e) {
    if (host) {
      host.innerHTML = '<div class="callout danger"><svg><use href="#i-alert-circle"/></svg><div>' +
        esc('导入失败：' + e.message) + '</div></div>';
    }
    toast('导入失败：' + e.message, 'error');
  }
}

/** Renders the per-file summary: three counts plus one row per rejected entry. */
function renderImportResult(result) {
  var host = $('xfer-result');
  if (!host || !result) return;
  var errors = result.errors || [];
  var rows = [
    kvRow('共读取', (result.total || 0) + ' 条'),
    kvRow('新建', (result.created || 0) + ' 条'),
    kvRow('更新（已存在，合并标签）', (result.updated || 0) + ' 条'),
    kvRow('跳过', (result.skipped || 0) + ' 条')
  ].join('');
  var detail = errors.length ? '<div class="import-errors">' + errors.map(function (e) {
    return '<div class="import-error">' +
      '<span class="idx">#' + esc(e.index == null ? '?' : e.index) + '</span>' +
      '<span class="nm">' + esc(e.name || '（未命名）') + '</span>' +
      '<span class="why">' + esc(e.reason || '') + '</span></div>';
  }).join('') + '</div>' : '';
  host.innerHTML = '<div class="import-summary">' +
    '<div class="import-counts">' + rows + '</div>' + detail + '</div>';
}

/** Reads the chosen file, parses it, and hands it to the import endpoint. */
function onImportFileChosen(input) {
  var file = input && input.files ? input.files[0] : null;
  if (!file) return;
  var host = $('xfer-result');
  if (file.size > 8 * 1024 * 1024) {
    if (host) host.innerHTML = '<div class="callout danger"><svg><use href="#i-alert-circle"/></svg><div>文件超过 8 MB，代码源清单不应该这么大，请确认选的是导出文件。</div></div>';
    input.value = '';
    return;
  }
  var reader = new FileReader();
  reader.onload = function () {
    var payload;
    try {
      payload = JSON.parse(String(reader.result || ''));
    } catch (e) {
      if (host) host.innerHTML = '<div class="callout danger"><svg><use href="#i-alert-circle"/></svg><div>不是合法的 JSON 文件，请选择由「导出」生成的 .json。</div></div>';
      toast('不是合法的 JSON 文件', 'error');
      input.value = '';
      return;
    }
    importSourcesPayload(payload);
    input.value = '';
  };
  reader.onerror = function () {
    if (host) host.innerHTML = '<div class="callout danger"><svg><use href="#i-alert-circle"/></svg><div>读取文件失败，请重试。</div></div>';
    input.value = '';
  };
  reader.readAsText(file);
}

/** Static explanation under the transfer card's title. */
function renderTransferHint() {
  var host = $('set-transfer-hint');
  if (!host) return;
  host.innerHTML = [
    kvRow('导出内容', '类型、位置、默认分支、凭据环境变量名、标签'),
    kvRow('不导出', '本机 id、同步状态、时间戳（换机器后无意义）'),
    kvRow('导入规则', '类型 + 位置相同视为同一代码源，合并标签并更新分支')
  ].join('');
}

/* ------------------------------------------------------- source edit mode */

/**
 * Fills #modal-source with an existing source and switches it to edit mode.
 *
 * The draft tags live in state.sourceTags.m, not in the DOM, so a re-render while the modal is
 * open (a poll tick) cannot lose what the user typed.
 */
function openEditSource(sourceId) {
  var source = state.sources.filter(function (s) { return s.id === Number(sourceId); })[0];
  if (!source) return;
  state.editingSourceId = source.id;
  setModalSourceMode('edit');
  $('m-type').value = source.type || 'LOCAL_DIR';
  $('m-location').value = source.location || '';
  $('m-token').value = source.credentialEnv || '';
  $('m-name-preview').value = source.name || '';
  resetRefSelect($('m-ref'), $('m-ref-hint'));
  // The saved ref may not be in the dropdown yet; keep it selectable until refs are loaded.
  if (source.defaultRef) {
    $('m-ref').innerHTML = '<option value="' + esc(source.defaultRef) + '">' + esc(source.defaultRef) + '</option>';
    $('m-ref').value = source.defaultRef;
    $('m-ref-hint').textContent = '当前选择 ' + source.defaultRef;
  }
  setFormTags('m', sourceTagsOf(source));
  var err = $('m-error');
  if (err) err.style.display = 'none';
  openModal('modal-source');
  syncBrowseButtons();
  scheduleRefLookup('m');
  setTimeout(function () { var f = $('m-location'); if (f) f.focus(); }, 60);
}

/** Switches the modal between "add" and "edit": title, save label, note and tags draft. */
function setModalSourceMode(mode) {
  var editing = mode === 'edit';
  var title = $('ms-title');
  if (title) title.textContent = editing ? '编辑代码源' : '添加代码源';
  var sub = $('ms-sub');
  if (sub) {
    sub.textContent = editing
      ? '保存后立即生效；标签会整体替换为该表单里的内容。'
      : '保存后可扫描该代码源的变更对其他程序的影响。';
  }
  var save = $('m-save');
  if (save) save.textContent = editing ? '保存修改' : '保存代码源';
  var note = $('ms-note');
  if (note) note.textContent = editing ? '位置必填，标签会整体替换' : '位置必填，名称按位置自动识别';
}

/** Back to add mode with an empty form; called on cancel, close and after a successful save. */
function resetSourceModal() {
  state.editingSourceId = null;
  setModalSourceMode('add');
  ['m-location', 'm-ref', 'm-token', 'm-name-preview'].forEach(function (f) { if ($(f)) $(f).value = ''; });
  resetRefSelect($('m-ref'), $('m-ref-hint'));
  state.sourceTags.m = [];
  renderTagInput('m');
  var err = $('m-error');
  if (err) err.style.display = 'none';
}

async function runAnalysisFromModal() {
  var sourceId = Number($('mr-source').value);
  var mode = $('mr-mode').value;
  var body = {
    sourceId: sourceId,
    mode: mode,
    baseRef: mode === 'WORKTREE_DIFF' ? null : ($('mr-base').value.trim() || null),
    targetRef: $('mr-target').value.trim() || null,
    pullRequest: mode === 'PULL_REQUEST' ? Number($('mr-pr').value) || null : null,
    peerSourceIds: selectedPeerIds()
  };
  if (!body.peerSourceIds.length) {
    showModalError('mr-error', '请至少选择一个要扫描的其他程序');
    return;
  }
  if (mode === 'PULL_REQUEST' && !body.pullRequest) {
    showModalError('mr-error', '请填写 PR / MR 编号');
    return;
  }
  var btn = $('mr-start');
  btn.disabled = true;
  btn.innerHTML = '<span class="spinner"></span>分析中…';
  try {
    var analysis = await api('/analyses', { method: 'POST', body: JSON.stringify(body) });
    closeModal('modal-run');
    await loadAnalyses();
    state.sourceId = analysis.sourceId || sourceId;
    state.liveAnalysis = analysis;
    go('analysis');
    toast('分析已提交 #' + analysis.id + '，正在解析代码与依赖图…');
    pollAnalysis(analysis.id);
  } catch (e) {
    showModalError('mr-error', '提交失败：' + e.message);
  } finally {
    btn.disabled = false;
    btn.innerHTML = icon('i-play') + '开始分析';
  }
}

function pollAnalysis(id) {
  if (state.pollTimer) clearTimeout(state.pollTimer);
  state.pollTimer = null;
  var tick = async function () {
    try {
      var analysis = await loadLiveAnalysis(id);
      if (!analysis) {
        toast('读取分析状态失败', 'error');
        return;
      }
      state.liveAnalysis = analysis;
      var liveOwner = state.analyses.filter(function (a) { return a.id === id; })[0];
      if (liveOwner) {
        liveOwner.status = analysis.status;
        liveOwner.progressStage = analysis.progressStage;
        liveOwner.progressPercent = analysis.progressPercent;
        liveOwner.progressLabel = analysis.progressLabel;
        liveOwner.progressDetail = analysis.progressDetail;
        liveOwner.progressCompleted = analysis.progressCompleted;
        liveOwner.progressSkipped = analysis.progressSkipped;
      }
      if (state.sourceId == null) {
        state.sourceId = analysis.sourceId || null;
      }
      // Only the live panel needs redrawing on each tick. Full renderAll would repeatedly rebuild
      // maps and tables while the analysis is still running.
      renderAnalysisProgress();
      if (analysis.status === 'DONE') {
        state.liveAnalysis = null;
        await loadAnalyses();
        await loadSources();
        await openAnalysis(id);
        go('overview');
        toast('分析 #' + id + ' 已完成', 'success');
        return;
      }
      if (analysis.status === 'FAILED') {
        // Keep the failed stage visible until the user acknowledges it, so the reason and the
        // last completed steps are not lost immediately.
        state.liveAnalysis = analysis;
        await loadAnalyses();
        toast('分析失败：' + (analysis.message || '未知错误'), 'error');
        renderAll();
        return;
      }
      state.pollTimer = setTimeout(tick, 900);
    } catch (e) {
      toast('读取分析状态失败：' + e.message, 'error');
      // Transient network failures must not silently stop progress tracking.
      state.pollTimer = setTimeout(tick, 3000);
    }
  };
  state.pollTimer = setTimeout(tick, 300);
}

/* --------------------------------------------------------- decision modal */

var pendingDecision = null;

function openDecision(kind, impactId) {
  var impact = impacts().filter(function (i) { return String(i.id) === String(impactId); })[0];
  if (!impact) return;
  pendingDecision = { kind: kind, impactId: impactId };
  var isConfirm = kind === 'confirmed';
  $('md-title').textContent = isConfirm ? '确认影响' : '忽略该发现';
  $('md-sub').textContent = isConfirm
    ? '确认后该影响将进入已验证队列，并记录处理人。'
    : '忽略后该发现不再出现在待处理列表中，可随时恢复。';
  var iconHost = $('md-icon');
  iconHost.className = 'modal-icon' + (isConfirm ? '' : ' brand');
  iconHost.innerHTML = icon(isConfirm ? 'i-check' : 'i-eye-off');
  $('md-save').textContent = isConfirm ? '确认影响' : '忽略';
  $('md-save').className = 'btn ' + (isConfirm ? 'btn-primary' : '');
  $('md-note').value = '';
  $('md-target').textContent = (impactProgram(impact)) + ' · ' + shortSymbol(impact.targetSymbol);
  openModal('modal-decision');
}

/* ------------------------------------------------------------------ binding */

function bindEvents() {
  document.addEventListener('click', function (ev) {
    var el = ev.target.closest('[data-nav],[data-impact-id],[data-analysis-id],[data-source-id],[data-module],[data-change-id],[data-confirm-impact],[data-dismiss-impact],[data-open-impact],[data-close],[data-open-run],[data-tab],[data-filter],[data-pick-dir],[data-pk-dir],[data-pk-root],[data-delete-source],[data-background-analysis],[data-update-check],[data-update-notes],[data-update-download],[data-update-apply],[data-update-rollback],[data-update-banner-view],[data-update-banner-skip],[data-update-banner-close],[data-update-history],[data-update-history-retry],[data-edit-source],[data-tag-add],[data-tag-remove],[data-tag-group-toggle],[data-tag-filter],[data-tag-filter-clear],[data-export],[data-import-file]');
    if (!el) return;

    // Version & update actions live on the settings card; each one is a single async call
    // that re-renders the card itself, so no other page needs to be refreshed here.
    if (el.hasAttribute('data-update-check')) { checkUpdateNow(); return; }
    if (el.hasAttribute('data-update-notes')) {
      var notesKey = el.getAttribute('data-update-notes');
      var expanded = updateState().expanded;
      if (expanded[notesKey]) delete expanded[notesKey];
      else expanded[notesKey] = true;
      renderUpdateBody();
      return;
    }
    if (el.hasAttribute('data-update-download')) { downloadVersion(el.getAttribute('data-update-download')); return; }
    if (el.hasAttribute('data-update-apply')) { applyUpdate(); return; }
    if (el.hasAttribute('data-update-rollback')) { rollbackUpdate(); return; }

    // The banner sits outside the settings card, so it carries its own three actions.
    if (el.hasAttribute('data-update-banner-view')) { showUpdateCard(); return; }
    if (el.hasAttribute('data-update-banner-skip')) { ignoreUpdateVersion(); return; }
    if (el.hasAttribute('data-update-banner-close')) { closeUpdateBanner(); return; }

    if (el.hasAttribute('data-update-history')) { toggleUpdateHistory(); return; }
    if (el.hasAttribute('data-update-history-retry')) { loadUpdateHistory(); return; }

    // Tag groups fold on their head; the head is the closest match, so this runs before any
    // ancestor-based handler could claim the click.
    if (el.hasAttribute('data-tag-group-toggle')) {
      toggleNavTagGroup(el.getAttribute('data-tag-group-toggle'));
      return;
    }
    if (el.hasAttribute('data-tag-filter-clear')) { setTagFilter(null); return; }
    if (el.hasAttribute('data-edit-source')) { openEditSource(Number(el.getAttribute('data-edit-source'))); return; }

    // Chip input: add a candidate, or drop one of the selected tags.
    if (el.hasAttribute('data-tag-add')) { addFormTag(el.getAttribute('data-tag-add'), el.getAttribute('data-tag-value')); return; }
    if (el.hasAttribute('data-tag-remove')) { removeFormTag(el.getAttribute('data-tag-remove'), el.getAttribute('data-tag-value')); return; }

    if (el.hasAttribute('data-export')) { exportSources(); return; }
    if (el.hasAttribute('data-import-file')) { $('xfer-file').click(); return; }

    if (el.hasAttribute('data-delete-source')) {
      // The delete control sits inside a clickable row, so stop it from also selecting the source.
      ev.stopPropagation();
      openDeleteSource(el.getAttribute('data-delete-source'));
      return;
    }

    if (el.hasAttribute('data-background-analysis')) {
      // Background means "stop showing the live panel", not "cancel the server task".
      state.liveAnalysis = null;
      renderAnalysis();
      go('overview');
      toast('分析继续在后台运行，完成后可在分析历史中打开');
      return;
    }

    if (el.hasAttribute('data-pick-dir')) { openDirPicker(el.getAttribute('data-pick-dir')); return; }
    if (el.hasAttribute('data-pk-dir')) {
      state.picker.selected = el.getAttribute('data-pk-dir');
      $('pk-selected').textContent = '已选：' + state.picker.selected;
      loadDir(state.picker.selected);
      return;
    }
    if (el.hasAttribute('data-pk-root')) { loadDir(el.getAttribute('data-pk-root')); return; }

    if (el.hasAttribute('data-close')) {
      // Closing the source dialog is a cancel: drop the edit draft so the next open starts clean.
      if (el.getAttribute('data-close') === 'modal-source') resetSourceModal();
      closeModal(el.getAttribute('data-close'));
      return;
    }
    if (el.hasAttribute('data-open-run')) { openRunModal(); return; }

    if (el.hasAttribute('data-nav')) {
      var page = el.getAttribute('data-nav');
      go(page);
      if (page === 'settings') renderSettings();
      return;
    }
    if (el.hasAttribute('data-tab')) {
      state.reviewTab = el.getAttribute('data-tab');
      renderReview();
      return;
    }
    if (el.hasAttribute('data-filter')) {
      var parts = el.getAttribute('data-filter').split(':');
      state.historyFilter = parts[1] || '';
      go('history');
      renderHistory();
      return;
    }
    if (el.hasAttribute('data-confirm-impact')) { openDecision('confirmed', el.getAttribute('data-confirm-impact')); return; }
    if (el.hasAttribute('data-dismiss-impact')) { openDecision('dismissed', el.getAttribute('data-dismiss-impact')); return; }
    if (el.hasAttribute('data-open-impact')) {
      state.selectedImpactId = el.getAttribute('data-open-impact');
      go('impact');
      renderImpact();
      return;
    }
    if (el.hasAttribute('data-analysis-id')) {
      // Clicking a history entry is a request to open that record, so it has to navigate. It used
      // to only load the report, which left the click looking dead for anything without one -
      // exactly the failed runs the user was trying to inspect.
      openAnalysis(Number(el.getAttribute('data-analysis-id'))).then(function () {
        if (state.page !== 'analysis') go('analysis');
      });
      return;
    }
    if (el.hasAttribute('data-module')) {
      state.selectedModule = state.selectedModule === el.getAttribute('data-module') ? null : el.getAttribute('data-module');
      renderAnalysis();
      return;
    }
    if (el.hasAttribute('data-change-id')) {
      var change = ((state.report && state.report.changes) || []).filter(function (c) {
        return String(c.id) === el.getAttribute('data-change-id');
      })[0];
      openChangeDrawer(change);
      return;
    }
    if (el.hasAttribute('data-source-id')) {
      // Selecting a source always switches the workspace to it, even before it has any analysis.
      selectSource(Number(el.getAttribute('data-source-id')));
      go('overview');
      return;
    }
    if (el.hasAttribute('data-tag-filter')) { setTagFilter(el.getAttribute('data-tag-filter')); return; }
    if (el.hasAttribute('data-impact-id')) {
      state.selectedImpactId = el.getAttribute('data-impact-id');
      if (state.page === 'overview' || state.page === 'triage') {
        go('impact');
        renderImpact();
      } else {
        renderReview();
      }
      return;
    }
  });

  // Close overlays on a backdrop click. #modal-source keeps its edit/add draft on state, so it
  // also needs resetSourceModal() here - otherwise "edit source A" would leak into the next add.
  document.querySelectorAll('.overlay').forEach(function (overlay) {
    overlay.addEventListener('click', function (ev) {
      if (ev.target !== overlay) return;
      if (overlay.id === 'modal-source') resetSourceModal();
      overlay.classList.add('hidden');
    });
  });

  document.addEventListener('keydown', function (ev) {
    if (ev.key === 'Escape') {
      document.querySelectorAll('.overlay:not(.hidden)').forEach(function (o) {
        // Escape is a cancel: the source form must not keep the edited source's values around.
        if (o.id === 'modal-source') resetSourceModal();
        o.classList.add('hidden');
      });
    }
    if ((ev.metaKey || ev.ctrlKey) && ev.key.toLowerCase() === 'k') {
      ev.preventDefault();
      var input = $('global-search');
      if (input) input.focus();
    }
  });

  var pkUp = $('pk-up');
  if (pkUp) {
    pkUp.addEventListener('click', function () {
      // The server already told us the parent in the listing that produced this view. Deriving it
      // here from state.picker.path is what broke the button: a trailing segment regex cannot climb
      // from "/Users" to "/" (no leading segment to strip) and knows nothing about Windows
      // separators, so the click re-loaded the same directory and looked dead.
      var parent = state.picker.parent;
      if (!parent) return;
      loadDir(parent);
    });
  }
  var pkGo = $('pk-go');
  if (pkGo) pkGo.addEventListener('click', function () { loadDir($('pk-path').value.trim()); });
  var pkPath = $('pk-path');
  if (pkPath) {
    pkPath.addEventListener('keydown', function (ev) {
      if (ev.key === 'Enter') { ev.preventDefault(); loadDir(pkPath.value.trim()); }
    });
  }
  var pkHidden = $('pk-hidden');
  if (pkHidden) {
    pkHidden.addEventListener('change', function () { loadDir(state.picker.path || ''); });
  }
  var pkChoose = $('pk-choose');
  if (pkChoose) pkChoose.addEventListener('click', chooseCurrentDir);

  // Typing the source name arms the delete button; this is the guard against a stray click.
  var dsConfirm = $('ds-confirm');
  if (dsConfirm) {
    dsConfirm.addEventListener('input', function () {
      var target = pendingDeleteSource ? pendingDeleteSource.name : null;
      $('ds-save').disabled = !target || dsConfirm.value.trim() !== target;
    });
    dsConfirm.addEventListener('keydown', function (ev) {
      if (ev.key === 'Enter' && !$('ds-save').disabled) { ev.preventDefault(); confirmDeleteSource(); }
    });
  }
  var dsSave = $('ds-save');
  if (dsSave) dsSave.addEventListener('click', confirmDeleteSource);

  ['src-type', 'm-type'].forEach(function (id) {
    var el = $(id);
    if (el) {
      el.addEventListener('change', syncBrowseButtons);
      el.addEventListener('change', function () {
        refreshNamePreview(id === 'm-type' ? 'm' : 'src');
      });
    }
  });
  syncBrowseButtons();

  // The name is derived, so the preview follows the location as it is typed or picked.
  // The branch dropdown follows the location too, so neither is typed by hand.
  [['src-location', 'src'], ['m-location', 'm']].forEach(function (pair) {
    var el = $(pair[0]);
    if (!el) return;
    el.addEventListener('input', function () {
      refreshNamePreview(pair[1]);
      scheduleRefLookup(pair[1]);
    });
    el.addEventListener('change', function () {
      refreshNamePreview(pair[1]);
      scheduleRefLookup(pair[1]);
    });
  });
  [['src-type', 'src'], ['m-type', 'm']].forEach(function (pair) {
    var el = $(pair[0]);
    if (el) el.addEventListener('change', function () { scheduleRefLookup(pair[1]); });
  });
  [['src-token', 'src'], ['m-token', 'm']].forEach(function (pair) {
    var el = $(pair[0]);
    if (el) el.addEventListener('change', function () { scheduleRefLookup(pair[1]); });
  });
  // Picking a branch updates the hint without re-querying the repository.
  [['src-ref', 'src'], ['m-ref', 'm']].forEach(function (pair) {
    var el = $(pair[0]);
    if (!el) return;
    el.addEventListener('change', function () {
      var hint = $(pair[1] + '-ref-hint');
      if (hint && el.value) hint.textContent = '当前选择 ' + el.value;
    });
  });
  refreshNamePreview('src');
  refreshNamePreview('m');
  renderTagInput('src');
  renderTagInput('m');
  renderTransferHint();

  var search = $('global-search');
  if (search) {
    search.addEventListener('input', function (e) {
      state.search = e.target.value.trim();
      renderNavSources();
    });
  }

  var addBtns = ['nav-add-source', 'nav-add-source-2', 'src-add', 'src-refresh', 'src-reset', 'ov-refresh', 'hi-refresh', 'rv-reset'];
  addBtns.forEach(function (id) {
    var btn = $(id);
    if (!btn) return;
    btn.addEventListener('click', async function () {
      if (id === 'nav-add-source' || id === 'nav-add-source-2' || id === 'src-add') openSourceModal();
      if (id === 'src-refresh' || id === 'ov-refresh' || id === 'hi-refresh') {
        await refreshAll(true);
        toast('已刷新', 'success');
      }
      if (id === 'src-reset') {
        ['src-location', 'src-ref', 'src-token', 'src-name-preview'].forEach(function (f) { if ($(f)) $(f).value = ''; });
        resetRefSelect($('src-ref'), $('src-ref-hint'));
        state.importStep = -1;
        state.importError = false;
        renderImportStepper();
      }
      if (id === 'rv-reset') {
        state.review[String(state.analysisId)] = {};
        persistReview();
        renderAll();
        toast('已重置本地复核状态');
      }
    });
  });

  // The file input is opened by the 导入 button; the change fires once a file is picked.
  var xferFile = $('xfer-file');
  if (xferFile) xferFile.addEventListener('change', function () { onImportFileChosen(xferFile); });

  // Tag fields: Enter or comma commits, Backspace on an empty field removes the last chip.
  // Bound per field rather than through the click delegate because these are keystrokes.
  document.querySelectorAll('[data-tag-input]').forEach(function (input) {
    var prefix = input.getAttribute('data-tag-input');
    input.addEventListener('keydown', function (ev) {
      if (ev.key === 'Enter' || ev.key === ',' || ev.key === '，') {
        ev.preventDefault();
        commitTagInput(prefix);
        return;
      }
      if (ev.key === 'Backspace' && !input.value) {
        var tags = tagBuffer(prefix);
        if (tags.length) {
          ev.preventDefault();
          removeFormTag(prefix, tags[tags.length - 1]);
        }
      }
    });
    // Paste of "a,b,c" splits into several chips in one action.
    input.addEventListener('paste', function (ev) {
      var text = ev.clipboardData ? ev.clipboardData.getData('text') : '';
      if (text && /[,，]/.test(text)) {
        ev.preventDefault();
        state.sourceTags[prefix] = normalizeTags(tagBuffer(prefix).concat(text.split(/[,，]/)));
        renderTagInput(prefix);
      }
    });
    input.addEventListener('blur', function () { if (input.value.trim()) commitTagInput(prefix); });
  });

  var srcSave = $('src-save');
  if (srcSave) {
    srcSave.addEventListener('click', async function () {
      var location = $('src-location').value.trim();
      if (!location) { toast('位置必填', 'error'); return; }
      state.importStep = 0;
      state.importError = false;
      renderImportStepper();
      srcSave.disabled = true;
      try {
        await saveSourceForm($('src-type').value, location, $('src-ref').value.trim(), $('src-token').value.trim(), tagBuffer('src'));
        state.importStep = 4;
        renderImportStepper();
        ['src-location', 'src-ref', 'src-token', 'src-name-preview'].forEach(function (f) { if ($(f)) $(f).value = ''; });
        resetRefSelect($('src-ref'), $('src-ref-hint'));
        setFormTags('src', []);
        toast('代码源已添加', 'success');
      } catch (e) {
        state.importError = true;
        state.importStep = 1;
        renderImportStepper();
        toast('添加失败：' + e.message, 'error');
      } finally {
        srcSave.disabled = false;
      }
    });
  }

  var mSave = $('m-save');
  if (mSave) {
    mSave.addEventListener('click', async function () {
      mSave.disabled = true;
      try {
        var tags = tagBuffer('m').slice(); // keep a local copy while we close the modal
        await saveSourceForm(
          $('m-type').value, $('m-location').value.trim(),
          $('m-ref').value.trim(), $('m-token').value.trim(),
          tags
        );
        resetSourceModal();
        closeModal('modal-source');
        ['m-location', 'm-ref', 'm-token', 'm-name-preview'].forEach(function (f) { if ($(f)) $(f).value = ''; });
        resetRefSelect($('m-ref'), $('m-ref-hint'));
        toast(state.editingSourceId ? '已保存修改' : '代码源已添加', 'success');
      } catch (e) {
        showModalError('m-error', '保存失败：' + e.message);
      } finally {
        mSave.disabled = false;
      }
    });
  }

  var mode = $('mr-mode');
  if (mode) {
    mode.addEventListener('change', function () {
      var m = mode.value;
      $('mr-pr-wrap').classList.toggle('hidden', m !== 'PULL_REQUEST');
      $('mr-base-wrap').classList.toggle('hidden', m === 'WORKTREE_DIFF');
    });
  }
  var mrSource = $('mr-source');
  if (mrSource) {
    mrSource.addEventListener('change', function () {
      var s = state.sources.filter(function (x) { return String(x.id) === mrSource.value; })[0];
      if (s) {
        $('mr-target').value = s.defaultRef || '';
        loadRefs(s.id);
        // The peer list must not offer the program now being analysed.
        renderPeerPicker(s.id);
      }
    });
  }
  var mrStart = $('mr-start');
  if (mrStart) mrStart.addEventListener('click', runAnalysisFromModal);

  var mdSave = $('md-save');
  if (mdSave) {
    mdSave.addEventListener('click', function () {
      if (!pendingDecision) return;
      setDecision(pendingDecision.impactId, {
        decision: pendingDecision.kind,
        note: $('md-note').value.trim(),
        owner: $('md-owner').value,
        at: new Date().toISOString()
      });
      var kind = pendingDecision.kind;
      pendingDecision = null;
      closeModal('modal-decision');
      renderAll();
      toast(kind === 'confirmed' ? '已确认该影响' : '已忽略该发现', 'success');
    });
  }

  var rvFinish = $('rv-finish');
  if (rvFinish) {
    rvFinish.addEventListener('click', function () {
      var counts = reviewCounts();
      if (!counts.total) { toast('当前没有可复核的影响项', 'error'); return; }
      if (counts.pending > 0) {
        toast('还有 ' + counts.pending + ' 项待复核，已为你定位到待审核标签', 'error');
        state.reviewTab = 'pending';
        renderReview();
        return;
      }
      go('triage');
      toast('本轮复核已完成', 'success');
    });
  }

  var imConfirm = $('im-confirm');
  if (imConfirm) imConfirm.addEventListener('click', function () { openDecision('confirmed', state.selectedImpactId); });
  var imDismiss = $('im-dismiss');
  if (imDismiss) imDismiss.addEventListener('click', function () { openDecision('dismissed', state.selectedImpactId); });

  var density = $('set-density');
  if (density) {
    var saved = null;
    try { saved = localStorage.getItem(DENSITY_KEY); } catch (e) { /* ignore */ }
    if (saved) density.value = saved;
    density.addEventListener('change', function () {
      try { localStorage.setItem(DENSITY_KEY, density.value); } catch (e) { /* ignore */ }
      document.body.classList.toggle('compact', density.value === 'compact');
      toast('已切换信息密度');
    });
    if (saved === 'compact') document.body.classList.add('compact');
  }
  var clear = $('set-clear');
  if (clear) {
    clear.addEventListener('click', function () {
      state.review = {};
      persistReview();
      renderAll();
      toast('已清除本地复核状态', 'success');
    });
  }
}

/* --------------------------------------------------------------------- boot */

(async function boot() {
  state.review = loadReviewStore();
  initTheme();
  // Before the first render: the persisted layout must not flash open and then fold.
  initNav();
  bindEvents();
  // Fire-and-forget, in parallel with the first data load: the notice must not delay first paint,
  // and a failure here is silent on purpose (see primeUpdateNotice).
  primeUpdateNotice();
  await refreshAll(false);
  var remembered = null;
  try { remembered = localStorage.getItem(PAGE_KEY); } catch (e) { /* ignore */ }
  go(remembered && remembered !== 'impact' ? remembered : 'overview');
  renderAll();
})();
