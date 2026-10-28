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
const MODE_LABEL = { GIT_DIFF: '提交对比', WORKTREE_DIFF: '未提交改动', PULL_REQUEST: 'PR / MR', COMMIT_RANGE: '提交范围' };
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
/**
 * 复核发现列表每页条数。
 *
 * 14 模块规模的报告有 756 条影响，一次性 map 成 HTML 塞进 innerHTML 就是 756 个卡片 DOM 节点，
 * 首屏要等它们全部解析完才出来。50 条一页既能一屏看完，又让单次渲染的成本与报告规模无关。
 */
const REVIEW_PAGE_SIZE = 50;

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
  /** 复核发现列表的当前页（从 1 开始）；切标签、换分析时回到第 1 页。 */
  reviewPage: 1,
  /**
   * Commits ticked in the run modal's range preview, keyed by full sha.
   *
   * Lives here rather than in the DOM because the preview is re-rendered on every keystroke and
   * every debounced refetch: reading the ticks back out of a list that is about to be replaced is
   * how a selection silently disappears while the user is still choosing.
   */
  rangePicked: {},
  /**
   * 被复核决策改了数据、但当时不在屏幕上的页面。
   *
   * 页面是预渲染后用 CSS 显隐切换的，所以一次确认不必立刻重绘另外两张页面；
   * 打个脏标，等用户真的切过去时由 go() 补一次渲染即可 —— 反正那一刻本来就要渲染。
   */
  stalePages: {},
  /**
   * impacts 的排序与聚合缓存，见 sortedImpacts() / impactStats()。
   * source 保存报告里那个数组的引用，换成别的报告时引用不同，缓存自然失效。
   */
  impactCache: { analysisId: null, source: null, sorted: null, stats: null },
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
  updateBannerClosed: null,
  /**
   * AI review state, kept in one object because four surfaces read it: the settings card,
   * the analysis card, the run dialog and the health rows.
   *
   * `data` is the last GET/PUT /api/ai payload, `loaded` guards the one-time fetch (renderSettings
   * runs on every poll tick), and `formFilled` is what keeps that tick from rewriting the inputs
   * under the user's cursor - see fillAiForm().
   */
  ai: {
    data: null,
    loaded: false,
    loading: false,
    error: null,
    formFilled: false,
    saving: false,
    testing: false,
    /** The last POST /api/ai/test result, or null. Rendered separately from the form. */
    test: null,
    /** Analysis id whose AI section is being (re)generated, or null. */
    generating: null
  }
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

/**
 * 空报告时返回同一个空数组，而不是每次 new 一个字面量。
 * 缓存用数组引用当键（见 sortedImpacts），如果这里每次都返回新数组，
 * 没有报告时缓存永远命中不了，反而每帧白排一次空列表。
 * 这个数组只被读（filter / slice / forEach），不会被改写，共享是安全的。
 */
var EMPTY_IMPACTS = [];
function impacts() { return (state.report && state.report.impacts) || EMPTY_IMPACTS; }

/**
 * 按风险分从高到低排好的 impacts，按报告缓存。
 *
 * 之前 renderReview / renderOverview / renderTriage 各自对全量 impacts 做一遍
 * filter().sort()，而 renderAll 一次会跑三遍：756 条就是三次全量排序，且每次复核决策后
 * 还会再来一遍。排序结果只跟报告内容有关、跟复核决策无关，所以按"哪个分析 + 哪个数组"
 * 缓存即可 —— 决策变化时这份缓存依然有效，不需要失效。
 *
 * 缓存键用数组引用而不只是 analysisId：重新拉同一份报告时 JSON 会解析出新数组，
 * 引用变了就说明数据变了，比"记得在某处手动清缓存"可靠。
 */
function sortedImpacts() {
  var list = impacts();
  var cache = state.impactCache;
  if (cache.source === list && cache.analysisId === state.analysisId && cache.sorted) {
    return cache.sorted;
  }
  var sorted = list.slice().sort(function (a, b) { return (b.riskScore || 0) - (a.riskScore || 0); });
  cache.analysisId = state.analysisId;
  cache.source = list;
  cache.sorted = sorted;
  cache.stats = null;
  return sorted;
}

/**
 * 全量 impacts 的聚合结果，同样按报告缓存。
 *
 * 这些计数（高风险条数、高置信度条数、涉及程序数、各风险级分布）每次渲染都要重算一遍，
 * 但它们同样只取决于报告内容。复核决策只影响"哪些条已处理"，在需要时按决策再筛一次即可。
 */
function impactStats() {
  sortedImpacts();
  var cache = state.impactCache;
  if (cache.stats) return cache.stats;
  var list = cache.sorted;
  var byLevel = { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0, INFO: 0 };
  var highConfidence = 0;
  var programs = {};
  for (var i = 0; i < list.length; i++) {
    var it = list[i];
    if (byLevel[it.riskLevel] != null) byLevel[it.riskLevel]++;
    if (it.confidence === 'HIGH') highConfidence++;
    programs[impactProgram(it)] = 1;
  }
  cache.stats = {
    total: list.length,
    byLevel: byLevel,
    highRisk: byLevel.HIGH + byLevel.CRITICAL,
    highConfidence: highConfidence,
    programCount: Object.keys(programs).length
  };
  return cache.stats;
}

/**
 * 当前复核标签下、按风险分排好的行。
 *
 * 筛选只跟决策状态有关，而决策是用户点出来的，条目数量级远小于 impacts，
 * 所以在已排好的数组上做一次 O(n) 过滤就够，不必再缓存第二份。
 */
function reviewRows(tab, decisions) {
  var sorted = sortedImpacts();
  var rows = [];
  for (var i = 0; i < sorted.length; i++) {
    var it = sorted[i];
    var d = decisions[String(it.id)];
    if (tab === 'pending' && d) continue;
    if (tab === 'confirmed' && !(d && d.decision === 'confirmed')) continue;
    if (tab === 'dismissed' && !(d && d.decision === 'dismissed')) continue;
    rows.push(it);
  }
  return rows;
}

/**
 * 分页状态：当前页、总页数、本页首尾下标。
 * 抽成纯函数是为了能单独验证边界（空列表、越界页码、恰好整页），不依赖 DOM。
 */
function pageWindow(totalItems, page, pageSize) {
  // 缺省回到每页 50 条，而不是 1：漏传参数时应当退化成默认分页，不该变成"每页一条"。
  var size = pageSize > 0 ? pageSize : REVIEW_PAGE_SIZE;
  var pages = Math.max(1, Math.ceil(totalItems / size));
  var current = Math.min(Math.max(1, Number(page) || 1), pages);
  var start = (current - 1) * size;
  return {
    page: current,
    pages: pages,
    start: start,
    end: Math.min(start + size, totalItems),
    total: totalItems,
    hasPrev: current > 1,
    hasNext: current < pages
  };
}

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
  // 换了一份报告，页码必须回到第 1 页；impactCache 由 sortedImpacts() 按数组引用自动失效。
  state.reviewPage = 1;
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
  state.reviewPage = 1;
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

  // 复核决策在别的页面上留下的脏标：这里补一次渲染，用户切过来看到的就是最新数据。
  // 只在确实标过脏时才刷，平时切页仍然是零成本的纯显隐切换。
  if (state.stalePages && state.stalePages[page]) {
    delete state.stalePages[page];
    if (page === 'review') renderReview();
    else if (page === 'overview') renderOverview();
    else if (page === 'triage') renderTriage();
  }

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
  // sortedImpacts() 已经是风险分降序，这里只需从头挑 4 条未复核的，不必再排一次全量。
  var ordered = sortedImpacts();
  var attention = [];
  for (var ai = 0; ai < ordered.length && attention.length < 4; ai++) {
    var cand = ordered[ai];
    if (!dismissedSet[String(cand.id)] && !confirmedSet[String(cand.id)]) attention.push(cand);
  }

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
    // 风险级分布只跟报告内容有关，缓存后每次渲染都是常数时间。
    var stats = impactStats();
    var byLevel = stats.byLevel;
    var total = stats.total || 1;
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
          icon('i-pencil') + '</button>' +
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

/**
 * 复核页的计数部分：页头的"已复核"、四个标签上的数字、以及统计卡里的"已确认"。
 *
 * 单独抽出来是因为复核决策只需要改这些数字，不需要重建整个列表。
 * counts 参数可省，省了就地算一次 —— reviewCounts() 只遍历决策表，不碰 impacts，很便宜。
 */
function renderReviewCounters(counts) {
  counts = counts || reviewCounts();
  var setText = function (id, v) { var el = $(id); if (el) el.textContent = v; };
  var setHtml = function (id, v) { var el = $(id); if (el) el.innerHTML = v; };

  setHtml('rv-reviewed', counts.confirmed + counts.dismissed + ' <small>/ ' + counts.total + '</small>');
  setText('rv-n-all', counts.total);
  setText('rv-n-pending', counts.pending);
  setText('rv-n-confirmed', counts.confirmed);
  setText('rv-n-dismissed', counts.dismissed);

  var statsHost = $('rv-stats');
  if (statsHost) {
    var report = state.report;
    statsHost.innerHTML = [
      statTile('i-database', '对比程序', fmtNum((report && report.summary && report.summary.comparedPrograms) || 0), 'brand'),
      statTile('i-network', '命中契约', fmtNum((report && report.summary && report.summary.matchedContracts) || 0), 'brand'),
      statTile('i-alert', '高风险影响', impactStats().highRisk, 'danger'),
      statTile('i-check-circle', '已确认', counts.confirmed, 'success')
    ].join('');
  }
}

function renderReview() {
  var report = state.report;
  var counts = reviewCounts();
  renderPeerPanel(report, 'rv');
  var setText = function (id, v) { var el = $(id); if (el) el.textContent = v; };

  var pickedSource = currentSource();
  setText('rv-crumb-source', report ? report.sourceName : (pickedSource ? pickedSource.name : '—'));
  renderReviewCounters(counts);

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

  var tab = state.reviewTab;
  var tabs = document.querySelectorAll('#rv-tabs .tab');
  for (var i = 0; i < tabs.length; i++) {
    tabs[i].classList.toggle('active', tabs[i].getAttribute('data-tab') === tab);
  }
  var rows = reviewRows(tab, decisionsFor(state.analysisId));
  renderReviewList(rows, tab);

  renderReviewEvidence();
}

/**
 * 只重建"发现列表 + 分页控件"这一块，不动页头、统计卡和右侧证据面板。
 *
 * 抽出来是为了让切页 / 复核决策 / 切标签都走同一条局部重渲染路径：决策变化时重算计数是必要的，
 * 但没必要连带重建导航、总览、历史、关系图这些跟决策无关的页面。
 */
function renderReviewList(rows, tab) {
  var host = $('rv-findings');
  if (!host) return;
  var setText = function (id, v) { var el = $(id); if (el) el.textContent = v; };
  // 小标题里的条数是"当前标签下的总数"，不是本页条数，所以跟着列表一起更新。
  var tabNames = { all: '全部发现', pending: '待复核', confirmed: '已确认', dismissed: '已忽略' };
  setText('rv-findings-sub', (tabNames[tab] || '全部发现') + ' · ' + rows.length + ' 项');
  host.className = 'card-list';
  var win = pageWindow(rows.length, state.reviewPage, REVIEW_PAGE_SIZE);
  // 页数可能因为筛选变少而夹紧（比如从"全部"切到"已确认"），把夹紧后的值写回 state，
  // 否则下一次 renderReview 会拿一个越界的页码继续算。
  state.reviewPage = win.page;
  var pageRows = rows.slice(win.start, win.end);

  host.innerHTML = pageRows.length ? pageRows.map(reviewRowHtml).join('') : (state.report
    ? emptyBlock('没有符合条件的发现', '切换上方标签，或运行一次新的分析。')
    : emptyBlockForSource());

  renderReviewPager(win, rows.length);
}

/**
 * 只更新发现列表里"选中"高亮，不重建列表。
 *
 * 点一行原来会 renderReview() 整页：50 张卡片全部重新生成 HTML 再让浏览器解析一遍。
 * 选中的效果只是给一行加 .selected 类，逐个切换 class 比重建整页快一个数量级。
 * 若选中的发现不在当前页（比如翻了页又点回来），列表里没有任何一行匹配，
 * 此时保持选中状态不变、只是不高亮 —— 证据面板仍然显示它的内容，行为与改造前一致。
 */
function markSelectedReviewRow(impactId) {
  var host = $('rv-findings');
  if (!host) return;
  var rows = host.querySelectorAll('.card-row[data-impact-id]');
  for (var i = 0; i < rows.length; i++) {
    rows[i].classList.toggle('selected', rows[i].getAttribute('data-impact-id') === String(impactId));
  }
}

/** 一条发现卡片。抽成独立函数，避免分页改动时重复维护这段较长的模板字符串。 */
function reviewRowHtml(i) {
  var decisions = decisionsFor(state.analysisId);
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
}

/**
 * 分页条：上一页 / 页码 / 下一页。
 *
 * 控件动态建在 #rv-findings 的父卡片里，而不是写进 index.html —— 分页是纯前端行为，
 * 不给静态结构增加一个必须同步维护的节点。页码最多显示 7 个并带省略号，
 * 756 条 / 每页 50 = 16 页时不会把一条控件撑成两行。
 */
function renderReviewPager(win, rowCount) {
  var host = $('rv-findings');
  if (!host || !host.parentNode) return;
  var bar = $('rv-pager');
  if (!bar) {
    bar = document.createElement('div');
    bar.id = 'rv-pager';
    bar.className = 'pager';
    host.parentNode.appendChild(bar);
  }
  // 只有一页时不显示：没有可翻的页，控件只会占地方。
  if (win.pages <= 1) {
    bar.classList.add('hidden');
    bar.innerHTML = '';
    return;
  }
  bar.classList.remove('hidden');

  var btn = function (label, page, opts) {
    var o = opts || {};
    return '<button class="btn btn-sm' + (o.active ? ' btn-primary' : '') + '" type="button"' +
      ' data-review-page="' + page + '"' + (o.disabled ? ' disabled' : '') +
      (o.title ? ' title="' + esc(o.title) + '"' : '') + '>' + label + '</button>';
  };

  var parts = [
    '<span class="pager-info">第 ' + win.page + ' / ' + win.pages + ' 页 · 共 ' + rowCount + ' 项</span>',
    '<span class="pager-pages">',
    btn(icon('i-chevron-left'), win.page - 1, { disabled: !win.hasPrev, title: '上一页' })
  ];
  pageNumbers(win.page, win.pages).forEach(function (p) {
    if (p === '…') { parts.push('<span class="pager-gap">…</span>'); return; }
    parts.push(btn(String(p), p, { active: p === win.page }));
  });
  parts.push(btn(icon('i-chevron-right'), win.page + 1, { disabled: !win.hasNext, title: '下一页' }));
  parts.push('</span>');
  bar.innerHTML = parts.join('');
}

/**
 * 页码序列，超过 7 页时用 '…' 折叠中间段。
 * 返回的是数字与字符串 '…' 的混合数组，方便调用方直接 map。
 */
function pageNumbers(current, pages) {
  var max = 7;
  if (pages <= max) {
    var all = [];
    for (var i = 1; i <= pages; i++) all.push(i);
    return all;
  }
  var keep = [1, pages, current, current - 1, current + 1];
  keep = keep.filter(function (p) { return p >= 1 && p <= pages; })
    .filter(function (p, idx, arr) { return arr.indexOf(p) === idx; })
    .sort(function (a, b) { return a - b; });
  var out = [];
  for (var j = 0; j < keep.length; j++) {
    if (j > 0 && keep[j] - keep[j - 1] > 1) out.push('…');
    out.push(keep[j]);
  }
  return out;
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

/** 按 id 找一条影响；id 可能来自 data 属性（字符串），所以统一转成字符串比。 */
function impactById(impactId) {
  var list = impacts();
  var key = String(impactId);
  for (var i = 0; i < list.length; i++) {
    if (String(list[i].id) === key) return list[i];
  }
  return null;
}

/** 发现列表里对应那一条的 DOM 节点，不在当前页时返回 null。 */
function findReviewRowNode(impactId) {
  var host = $('rv-findings');
  if (!host) return null;
  var rows = host.querySelectorAll('.card-row[data-impact-id]');
  var key = String(impactId);
  for (var i = 0; i < rows.length; i++) {
    if (rows[i].getAttribute('data-impact-id') === key) return rows[i];
  }
  return null;
}

/**
 * 复核页在决策之后的最小更新：只替换那一条卡片，外加右侧证据面板里的决策提示。
 *
 * 为什么值得这么做：756 条规模下"全部"标签页每页仍有 50 张卡片，
 * 重建一次就是重新生成并解析 50 段 HTML，而其中 49 段的内容一模一样。
 *
 * 只有"全部"标签可以原地替换：其余三个标签的筛选条件本身就包含决策状态，
 * 这一条会直接进出列表，总条数、总页数都会变，所以退回重建当前这一页（仍然只有 ≤50 行）。
 */
function patchReviewAfterDecision(impactId) {
  var tab = state.reviewTab;
  var impact = impactById(impactId);
  if (tab === 'all' && impact) {
    var node = findReviewRowNode(impactId);
    if (node) {
      node.outerHTML = reviewRowHtml(impact);
      renderReviewEvidence();
      return;
    }
  }
  renderReviewList(reviewRows(tab, decisionsFor(state.analysisId)), tab);
  renderReviewEvidence();
}

/**
 * 复核决策改变后的局部刷新。
 *
 * 之前这里直接 renderAll()：一次确认/忽略会依次重建导航、总览、源码页、发现列表、影响详情、
 * 分析页、triage、历史、关系图，最后还要 renderMapsFor 重建一次 cytoscape 场景 —— 756 条规模下
 * 这一整串跟"某一条发现被确认了"没有半点关系。
 *
 * 现在只做两件事：刷新计数（导航徽标 + 复核页页头，都是几个数字），
 * 再按当前所在页面刷新真正受决策影响的那一块内容。
 *
 * 其余受决策影响、但此刻不可见的页面不立即重绘 —— 页面是预渲染后靠 CSS 显隐切换的（见 go()），
 * 给它们标脏，等用户真的切过去时再刷。这样一次确认的代价与"当前页面有多少行"成正比，
 * 而不是与"工作台一共有多少页面"成正比。
 */
function renderAfterDecision(impactId) {
  renderNavCounts();
  renderReviewCounters();

  var page = state.page;
  if (page === 'review') {
    patchReviewAfterDecision(impactId);
  } else if (page === 'overview') {
    renderOverview();
  } else if (page === 'triage') {
    renderTriage();
  }
  // 其余页面（源码 / 分析 / 历史 / 地图）与复核决策无关，一个都不用碰。

  // 上面只刷了当前页；另外两张依赖决策的页面留到切过去时再刷。
  state.stalePages = state.stalePages || {};
  if (page !== 'review') state.stalePages.review = true;
  if (page !== 'overview') state.stalePages.overview = true;
  if (page !== 'triage') state.stalePages.triage = true;
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
    // The commit count belongs in the header line: it is the scale of the range, and the card
    // below repeats it only for readers who scrolled there.
    if (commitTotal(report) > 0) subHtml += ' <span class="sep">·</span> ' + commitTotal(report) + ' 个提交';
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

  renderAnalysisCommits(report);
  renderAnalysisAi(report);

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
    var kind = b.kind === 'COLUMN_BLIND_SPOT' ? '字段盲区' : '未解析调用';
    return '<div class="ev-row" style="margin-bottom:6px"><div class="ev-type">' + kind + '</div>' +
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


/* ------------------------------------------------------- ai review (report) */

/**
 * The AI section of an analysis page.
 *
 * Four states, and the difference between them is the whole point of the card: a generated review
 * (labelled 待验证, with the model and the time it was produced), a failed generation (the reason,
 * because "AI 审核失败" alone cannot be acted on), a report that simply has none yet (offer to
 * generate one - that is how an old report gets reviewed without re-running the analysis), and an
 * installation where the feature is off (point at the settings card, do not pretend it exists).
 *
 * Hidden entirely while AI is not configured AND the report has no AI section: an installation that
 * never turns this on must not see a new card appear on every analysis it opens.
 */
function renderAnalysisAi(report) {
  var host = $('an-ai-body');
  var card = $('an-ai-card');
  var actions = $('an-ai-actions');
  var sub = $('an-ai-sub');
  if (!host || !card || !actions) return;

  var ai = state.ai;
  var section = report ? report.ai : null;
  var configured = !!(ai.data && ai.data.ready);
  var busy = ai.generating != null;

  // No report: nothing to explain, and #an-body is hidden anyway.
  if (!report) {
    card.classList.add('hidden');
    host.innerHTML = '';
    actions.innerHTML = '';
    return;
  }

  // The configuration decides three of the four states, and the analysis page is often the first
  // screen opened after a reload. Fetch it once and redraw, rather than rendering "未启用" for a
  // second and then changing the card's meaning under the reader.
  if (!ai.loaded && !ai.error) {
    ensureAiLoaded().then(function () {
      if (state.report) renderAnalysisAi(state.report);
    });
  }

  // Neither generated nor configured: stay out of the way entirely.
  if (!section && !configured) {
    card.classList.add('hidden');
    host.innerHTML = '';
    actions.innerHTML = '';
    return;
  }

  card.classList.remove('hidden');
  var html = '';
  var buttons = '';

  if (section && section.generated === true) {
    if (sub) sub.textContent = '基于本页静态分析事实生成的评审要点，不是已验证的结论';
    html += '<div class="ai-verified-note">' + icon('i-alert') +
      '<div>' + esc(section.disclaimer || 'AI 生成内容，仅基于下列静态分析事实，属于待验证的分析结论。') + '</div></div>';
    // renderNotes is the escaped, no-markdown-library renderer already used for release notes:
    // model output must never reach innerHTML unescaped, and this is the one place it could.
    html += '<div class="ai-text">' + renderNotes(section.text || '') + '</div>';
    var foot = [];
    if (section.model) foot.push('模型 ' + esc(section.model));
    if (section.generatedAt) foot.push('生成于 ' + esc(fmtTime(section.generatedAt)));
    if (section.maxImpacts) foot.push('送审 ' + esc(section.maxImpacts) + ' 条影响');
    if (foot.length) html += '<div class="ai-foot" style="margin-top:10px">' + foot.join(' · ') + '</div>';
    buttons = '<button class="btn btn-sm" data-ai-generate type="button"' + (busy ? ' disabled' : '') + '>' +
      (busy ? '<span class="spinner"></span>生成中…' : icon('i-refresh') + '重新生成') + '</button>';
  } else if (section && section.generated === false) {
    if (sub) sub.textContent = '上一次生成失败';
    // The stored failure is shown whatever the current configuration says: it is what happened to
    // this report, and a user who has since switched the feature off still needs to know the
    // section on screen is a failed attempt rather than an absent one.
    html += '<div class="callout danger">' + icon('i-alert-circle') + '<div>' +
      '<strong>AI 评审说明生成失败</strong><br/>' + esc(section.error || '模型没有返回可用的内容。') +
      '<div class="ai-foot" style="margin-top:4px">静态分析结论不受影响；' +
      (configured ? '可修正配置后重试。' : 'AI 审核当前未启用。') + '</div>' +
      '</div></div>';
    // Retrying is only offered while the feature is configured: the endpoint answers 400 when it is
    // not, and a button that reliably fails reads as a broken page rather than a disabled feature.
    buttons = configured
      ? '<button class="btn btn-sm" data-ai-generate type="button"' + (busy ? ' disabled' : '') + '>' +
        (busy ? '<span class="spinner"></span>生成中…' : icon('i-refresh') + '重试') + '</button>'
      : '<button class="btn btn-sm" data-nav="settings" type="button">' + icon('i-settings') + '去设置</button>';
  } else if (configured) {
    if (sub) sub.textContent = '本次分析没有生成 AI 审核，可按需补生成';
    html += '<div class="callout">' + icon('i-sparkles') + '<div>' +
      '这次分析只包含静态分析事实。模型 ' + esc(ai.data.model || '') +
      ' 可以基于这些事实写一份评审要点，发送内容仅限本页摘要。' +
      '</div></div>';
    buttons = '<button class="btn btn-sm btn-primary" data-ai-generate type="button"' + (busy ? ' disabled' : '') + '>' +
      (busy ? '<span class="spinner"></span>生成中…' : icon('i-sparkles') + '生成 AI 评审') + '</button>';
  } else {
    if (sub) sub.textContent = '未启用';
    html += '<div class="callout warning">' + icon('i-info') + '<div>' +
      '<strong>AI 审核未启用</strong><br/>' +
      '在「设置 → AI 审核」中配置接口地址与模型后，可以为这份报告生成评审说明。' +
      '关闭状态下报告只包含静态分析结论。' +
      '</div></div>';
    buttons = '<button class="btn btn-sm" data-nav="settings" type="button">' + icon('i-settings') + '去设置</button>';
  }

  host.innerHTML = html;
  actions.innerHTML = buttons;
}

/**
 * Generates (or regenerates) the AI section of the report on screen.
 *
 * The server writes the result into report_json and returns the AI object itself, so the page is
 * updated from that response rather than by refetching a multi-megabyte report: one small round
 * trip, and the card cannot disagree with what was stored.
 */
async function generateAiReview() {
  var ai = state.ai;
  var id = state.analysisId;
  if (!id || ai.generating != null) return;
  if (!state.report) return;
  ai.generating = id;
  renderAnalysisAi(state.report);
  try {
    var result = await api('/analyses/' + id + '/ai-review', { method: 'POST' });
    state.report.ai = result;
    if (result && result.generated) toast('AI 评审说明已生成', 'success');
    else toast('AI 评审说明生成失败：' + ((result && result.error) || '未知原因'), 'danger');
  } catch (e) {
    toast('生成失败：' + (e && e.message ? e.message : String(e)), 'danger');
  } finally {
    ai.generating = null;
    if (state.report) renderAnalysisAi(state.report);
  }
}

/**
 * Size of the range. The server may trim report.commits for a very wide range while commitCount
 * still holds the true total, so the larger of the two is the honest number.
 */
function commitTotal(report) {
  if (!report) return 0;
  return Math.max((report.commits || []).length, Number(report.commitCount) || 0);
}

/**
 * The commit range a run covered, as a card above the change list.
 *
 * Hidden rather than emptied when the report has no commits: every mode other than COMMIT_RANGE
 * leaves this empty, and a visible "提交范围 0 个" card would read as a failed range read on runs
 * that never had a range.
 */
function renderAnalysisCommits(report) {
  var host = $('an-commits');
  var card = $('an-commits-card');
  if (!host || !card) return;
  var commits = (report && report.commits) || [];
  card.classList.toggle('hidden', !commits.length);
  if (!commits.length) {
    host.innerHTML = '';
    return;
  }
  var total = commitTotal(report);
  var countEl = $('an-commit-count');
  if (countEl) countEl.textContent = total + ' 个';
  var subEl = $('an-commits-sub');
  if (subEl) subEl.textContent = (report.rangeNote || '') || '本次分析包含的提交，新提交在前';

  // A long range is scrolled, not truncated: the commit list is the evidence that the range the
  // user picked is the range that ran, so dropping the tail would defeat its purpose.
  host.innerHTML = '<div class="tr tr-commits head"><div class="cell">提交</div><div class="cell">说明</div>' +
    '<div class="cell optional">作者</div><div class="cell optional">时间</div></div>' +
    commits.map(function (c) {
      var parents = c.parents || [];
      return '<div class="tr tr-commits">' +
        '<div class="cell mono">' + esc(c.shortSha || String(c.sha || '').slice(0, 8)) +
          // A merge commit explains why the range's diff contains changes nobody wrote; the badge
          // is inline and tiny so it cannot push the sha out of its column.
          (parents.length > 1 ? '<span class="commit-merge" title="合并提交">合并</span>' : '') + '</div>' +
        '<div class="cell wrap" title="' + esc(c.sha || '') + '">' + esc(c.subject || '') + '</div>' +
        '<div class="cell optional">' + esc(c.author || '') + '</div>' +
        '<div class="cell optional mono">' + esc(fmtTime(c.date)) + '</div>' +
        '</div>';
    }).join('');
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
  // 沿用 sortedImpacts() 的风险分降序，只做一次 O(n) 过滤，不再重复排序。
  var ordered = sortedImpacts();
  var high = [];
  for (var hi = 0; hi < ordered.length; hi++) {
    var cand = ordered[hi];
    if (!decisions[String(cand.id)] && (cand.riskLevel === 'HIGH' || cand.riskLevel === 'CRITICAL')) high.push(cand);
  }
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
    var stats = impactStats();
    var highConf = stats.highConfidence;
    var contractCount = ((state.report && state.report.summary && state.report.summary.matchedContracts) || 0);
    advice.innerHTML = [
      adviceRow('i-target', '优先复核高置信度项', highConf + ' 项置信度为高的其他程序影响最可能真实发生，建议先看。'),
      adviceRow('i-database', '涉及的程序', stats.programCount + ' 个其他程序受到影响，需要分别通知负责人。'),
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
    kvRow('AI 审核', aiHealthLabel(h))
  ].join('') : emptyBlock('无法读取服务状态', '请确认后端服务正在运行。');
  // Version info loads once per page load, not on every renderAll() tick.
  ensureUpdateLoaded();
  renderAiSettings();
}

/**
 * The health row's three states.
 *
 * "已启用" alone was the old label and it was wrong in the case that matters: a switch that is on
 * with no endpoint or model produces nothing, and the row claimed it was working. The third state
 * is the one that sends the user to the card below with a reason.
 */
function aiHealthLabel(h) {
  if (!h) return '—';
  if (h.aiReady === true) return '已启用';
  if (h.aiEnabled === true) return '配置不完整';
  return '未启用';
}

/* -------------------------------------------------------------- ai settings */

/**
 * The three presets only touch the endpoint and the model.
 *
 * Deliberately not touching the key: a preset is a typing shortcut for values a user can never
 * remember exactly, and silently clearing a stored key would turn "pick Ollama to look at it" into
 * a lost secret. Ollama needs no key at all, which is why the empty-key case has to work.
 */
var AI_PRESETS = {
  deepseek: { baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
  openai: { baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini' },
  ollama: { baseUrl: 'http://localhost:11434/v1', model: 'qwen2.5:7b' }
};

/**
 * Loads GET /api/ai once per page load.
 *
 * Shared by the three surfaces that need to know whether AI is usable - the settings card, the run
 * dialog and the analysis card - because they can each be the first one opened. A single cached
 * fetch also means the analysis page cannot show "生成 AI 评审" while the run dialog says the
 * feature is off: both read the same payload.
 *
 * A server that predates the endpoint answers 404; that is recorded once and rendered as a plain
 * statement rather than thrown, because every caller has other work to do on the same page.
 */
async function ensureAiLoaded() {
  var ai = state.ai;
  if (ai.loaded || ai.loading) return ai.data;
  ai.loading = true;
  try {
    ai.data = await api('/ai');
    ai.error = null;
    ai.loaded = true;
  } catch (e) {
    ai.error = e && e.message ? e.message : String(e);
  } finally {
    ai.loading = false;
  }
  return ai.data;
}

/**
 * Loads GET /api/ai once per page load, then keeps the form in sync.
 *
 * renderSettings() is called on every renderAll() tick (a live analysis polls every 900 ms), so
 * this has to be idempotent twice over: the fetch is guarded by `loaded`, and the form is written
 * only on the first successful load or after an explicit save. Without the second guard, a poll
 * tick would overwrite whatever the user is half-way through typing - the classic "my input
 * disappeared while I was looking at it" bug.
 */
async function renderAiSettings() {
  var ai = state.ai;
  await ensureAiLoaded();
  renderAiStatus();
  renderAiTestResult();
  if (ai.data && !ai.formFilled) fillAiForm(ai.data);
  if (ai.error) showAiUnsupported(ai.error);
}

function renderAiStatus() {
  var ai = state.ai;
  var pillHost = $('ai-status');
  var noteHost = $('ai-status-note');
  if (!pillHost || !noteHost) return;
  if (ai.error) {
    pillHost.className = 'pill warning';
    pillHost.textContent = '接口不可用';
    noteHost.textContent = '当前服务端没有 /api/ai（' + ai.error + '），AI 审核只能通过 application.yml 配置。';
    return;
  }
  var d = ai.data;
  if (!d) {
    pillHost.className = 'pill neutral';
    pillHost.textContent = '读取中';
    noteHost.textContent = '正在读取本机配置…';
    return;
  }
  if (d.ready) {
    pillHost.className = 'pill success';
    pillHost.textContent = '已启用';
    noteHost.textContent = '模型 ' + (d.model || '—') + ' · 每次最多送审 ' + d.maxImpacts + ' 条影响';
  } else if (d.enabled) {
    pillHost.className = 'pill warning';
    pillHost.textContent = '配置不完整';
    noteHost.textContent = (d.reason || '缺少必要配置') + '，补全后即可使用。';
  } else {
    pillHost.className = 'pill neutral';
    pillHost.textContent = '未启用';
    noteHost.textContent = '静态分析不依赖任何外部服务；启用后才会把分析摘要发送给模型。';
  }
  var fileHost = $('ai-settings-file');
  if (fileHost) fileHost.textContent = d.settingsFile || '—';
}

/** One write of the whole form; only called on first load and after a successful save. */
function fillAiForm(d) {
  var ai = state.ai;
  if ($('ai-enabled')) $('ai-enabled').checked = d.enabled === true;
  if ($('ai-base-url')) $('ai-base-url').value = d.baseUrl || '';
  if ($('ai-model')) $('ai-model').value = d.model || '';
  if ($('ai-api-key')) {
    // Never a value, only a statement about the stored one: the browser must not hold the secret.
    $('ai-api-key').value = '';
    $('ai-api-key').placeholder = d.apiKeySet ? ('已保存 ' + (d.apiKeyMasked || '') + ' · 留空保持不变') : '未设置';
  }
  if ($('ai-max-impacts')) $('ai-max-impacts').value = d.maxImpacts;
  if ($('ai-timeout')) $('ai-timeout').value = d.timeoutSeconds;
  if ($('ai-default-runs')) $('ai-default-runs').checked = d.defaultForRuns === true;
  ai.formFilled = true;
}

/** The form as the API expects it, read fresh so unsaved edits can be tested. */
function aiFormBody() {
  var body = {
    enabled: !!($('ai-enabled') && $('ai-enabled').checked),
    baseUrl: $('ai-base-url') ? $('ai-base-url').value.trim() : '',
    model: $('ai-model') ? $('ai-model').value.trim() : '',
    maxImpacts: Number($('ai-max-impacts') ? $('ai-max-impacts').value : 0) || undefined,
    timeoutSeconds: Number($('ai-timeout') ? $('ai-timeout').value : 0) || undefined,
    defaultForRuns: !!($('ai-default-runs') && $('ai-default-runs').checked)
  };
  var key = $('ai-api-key') ? $('ai-api-key').value.trim() : '';
  // An empty input means "keep the stored key", not "remove it": the field is never pre-filled,
  // so a user who only edits the model must not lose their key by saving.
  if (key) body.apiKey = key;
  return body;
}

function showAiUnsupported(message) {
  var host = $('ai-test-result');
  if (!host) return;
  host.innerHTML = '<div class="callout warning">' + icon('i-alert') + '<div>' +
    '<strong>当前服务端不支持在线修改 AI 设置</strong><br/>' +
    '请升级到包含该接口的版本，或直接编辑 <span class="mono">application.yml</span> 的 ' +
    '<span class="mono">code-association.ai</span> 后重启服务。<br/>' +
    '<span class="mono">' + esc(message) + '</span></div></div>';
}

/** Renders the last test result in its own host, so testing never rewrites the form. */
function renderAiTestResult() {
  var host = $('ai-test-result');
  if (!host || state.ai.error) return;
  var t = state.ai.test;
  if (!t) { host.innerHTML = ''; return; }
  var head = t.ok ? '连接正常' : '连接失败';
  var details = [];
  if (t.endpoint) details.push('<span class="mono">' + esc(t.endpoint) + '</span>');
  if (t.latencyMs) details.push(esc(t.latencyMs) + ' ms');
  if (t.sample) details.push('返回：<span class="mono">' + esc(t.sample) + '</span>');
  host.innerHTML = '<div class="callout ' + (t.ok ? 'brand' : 'danger') + '">' +
    icon(t.ok ? 'i-check-circle' : 'i-alert-circle') + '<div>' +
    '<strong>' + head + '</strong>：' + esc(t.message || '') +
    (details.length ? '<div class="ai-foot" style="margin-top:4px">' + details.join(' · ') + '</div>' : '') +
    '</div></div>';
}

async function saveAiSettings() {
  var ai = state.ai;
  if (ai.saving || ai.error) return;
  ai.saving = true;
  setAiButtonsBusy(true, 'save');
  try {
    var data = await api('/ai', { method: 'PUT', body: JSON.stringify(aiFormBody()) });
    if (data) ai.data = data;
    ai.test = null;
    ai.formFilled = false;
    ai.loaded = true;
    state.health = null; // the health row reads aiEnabled/aiReady; refetch it next render
    toast('已保存', 'success');
    renderAiStatus();
    if (ai.data) fillAiForm(ai.data);
    renderAiTestResult();
    renderSettings();
  } catch (e) {
    toast(e && e.message ? e.message : String(e), 'danger');
  } finally {
    ai.saving = false;
    setAiButtonsBusy(false, 'save');
  }
}

async function testAiConnection() {
  var ai = state.ai;
  if (ai.testing || ai.error) return;
  ai.testing = true;
  setAiButtonsBusy(true, 'test');
  try {
    // The draft is sent as an override so the user can test before saving; blank fields fall back
    // to the stored configuration on the server, including the key the browser never holds.
    ai.test = await api('/ai/test', { method: 'POST', body: JSON.stringify(aiFormBody()) });
  } catch (e) {
    ai.test = { ok: false, message: e && e.message ? e.message : String(e), endpoint: '', latencyMs: 0, sample: '' };
  } finally {
    ai.testing = false;
    setAiButtonsBusy(false, 'test');
    renderAiTestResult();
  }
}

function clearAiKey() {
  var ai = state.ai;
  if (ai.saving || ai.error) return;
  // Clearing is a save in itself: an unsaved "clear" would leave the form claiming a key is gone
  // while the server still holds it, and the next save would silently restore it.
  ai.saving = true;
  setAiButtonsBusy(true, 'save');
  api('/ai', { method: 'PUT', body: JSON.stringify({ clearApiKey: true }) })
    .then(function (data) {
      if (data) ai.data = data;
      ai.formFilled = false;
      toast('已清除 API Key', 'success');
      renderAiStatus();
      if (ai.data) fillAiForm(ai.data);
      renderSettings();
    })
    .catch(function (e) { toast(e && e.message ? e.message : String(e), 'danger'); })
    .then(function () {
      ai.saving = false;
      setAiButtonsBusy(false, 'save');
    });
}

function applyAiPreset(name) {
  var preset = AI_PRESETS[name];
  if (!preset) return;
  if ($('ai-base-url')) $('ai-base-url').value = preset.baseUrl;
  if ($('ai-model')) $('ai-model').value = preset.model;
  var note = $('ai-status-note');
  if (note) note.textContent = '已填入 ' + preset.baseUrl + ' 与 ' + preset.model + '，保存后生效。';
}

function setAiButtonsBusy(busy, which) {
  var save = $('ai-save');
  var test = $('ai-test');
  if (save) {
    save.disabled = busy;
    save.innerHTML = busy && which === 'save'
      ? '<span class="spinner"></span>保存中…' : icon('i-check') + '保存';
  }
  if (test) {
    test.disabled = busy;
    test.innerHTML = busy && which === 'test'
      ? '<span class="spinner"></span>测试中…' : icon('i-zap') + '测试连接';
  }
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
      // A settings save is its own in-flight state: the three switches must not accept a second
      // click while a PUT is outstanding, or two writes would race on the server and the loser
      // would leave the toggle showing a value the server never stored.
      savingSettings: false,
      // The version ledger is a separate, lazily fetched resource: see loadUpdateHistory().
      history: { open: false, loaded: false, loading: false, error: null, data: null }
    };
  }
  return state.update;
}

/* -------------------------------------------------- restart / network view */

/**
 * The hot-restart poll. Held at module scope rather than on the update state because the settings
 * card is re-rendered on every renderAll() tick: a timer started from inside a render would pile
 * up a new chain per tick, and forty chains all polling and writing the same DOM is exactly the
 * bug this variable exists to prevent. One id, cleared before a new chain starts.
 */
var restartPollTimer = null;
var restartPollTicks = 0;
var RESTART_POLL_INTERVAL_MS = 3000;
var RESTART_POLL_MAX_TICKS = 40;

/** The restart sub-object, or an empty one on a server that predates the field. */
function restartInfo(d) {
  return (d && d.restart) || {};
}

/** The network sub-object, or null when the server does not report one. */
function networkInfo(d) {
  return (d && d.network) || null;
}

function autoRestartEnabled(d) {
  return !!(d && d.autoRestart === true);
}

/** Only a server that explicitly says supported === true may hide the manual commands. */
function restartSupported(d) {
  return restartInfo(d).supported === true;
}

function restartRunning(d) {
  return restartInfo(d).running === true;
}

/** A failure the server already recorded, i.e. it tried and had to put the old jar back. */
function restartFailed(d) {
  var r = restartInfo(d);
  return r.phase === 'failed' || r.phase === 'rolled-back' || r.rolledBack === true;
}

/**
 * A restart the server says is happening, in any of its in-flight shapes.
 *
 * Derived strictly from the reported phase, never from the mere presence of a pending swap: a
 * supervisor that declined to start (platform unsupported, another restart already running) leaves
 * phase at 'idle' forever, and treating that as "in progress" would strand the card on a spinner
 * that never resolves. The authoritative restart block from the apply response is what closes the
 * gap right after a swap — see applyUpdate().
 */
function restartActive(d) {
  var r = restartInfo(d);
  if (restartRunning(d)) return true;
  return r.phase === 'spawning' || r.phase === 'waiting';
}

/**
 * Cancels the chain but deliberately keeps the tick count.
 *
 * The count has to survive a cancel: renderUpdateBody() stops and re-arms the chain on nearly
 * every tick, and if stopping zeroed the counter the 40-tick budget would never be reached — the
 * card would poll forever against a service that never comes back. Only a new applyUpdate() resets
 * it, because that is the only moment a fresh budget is actually warranted.
 */
function stopRestartPoll() {
  if (restartPollTimer) clearTimeout(restartPollTimer);
  restartPollTimer = null;
}

/**
 * Whether there is anything left to wait for.
 *
 * A terminal phase ends the wait regardless of what else is on the card: 'succeeded' and
 * 'rolled-back' are answers, and continuing to poll them would keep a spinner alive over a
 * result the user is supposed to read. The pendingMsg branch covers the window before the
 * server has recorded a phase at all — right after apply, the old process is still answering.
 */
function restartPollNeeded(d) {
  var s = updateState();
  var r = restartInfo(d);
  if (r.phase === 'succeeded' || r.phase === 'failed' || r.phase === 'rolled-back') return false;
  // A timed-out watch is an answer too. Without this the render that shows the timeout callout
  // would immediately re-arm the chain and the timeout would never be visible — the card would
  // flip back to "正在重启…" one tick later, forever.
  if (s.restartTimeout || s.restartFailure) return false;
  if (restartRunning(d)) return true;
  return !!s.pendingMsg && autoRestartEnabled(d);
}

/**
 * Short poll while the service restarts. Every failure is swallowed on purpose: for the few
 * seconds the JVM is down, api() rejects with a connection error, and that is the expected state,
 * not something to paint red in the card.
 */
function startRestartPoll() {
  if (restartPollTimer) return;
  restartPollTimer = setTimeout(restartPollTick, RESTART_POLL_INTERVAL_MS);
}

async function restartPollTick() {
  restartPollTimer = null;
  var s = updateState();
  restartPollTicks++;
  var reached = false;
  try {
    // /update/restart is the light endpoint: the poll only needs the restart block, and pulling
    // the whole release list every three seconds would be wasteful while the box is booting.
    // Without a cached payload there is nothing to merge into, so the full read is the fallback.
    if (s.data) {
      var block = await api('/update/restart');
      // Only merge something that actually looks like a restart block. A proxy, a stale server
      // or an error page served at this path must not be able to overwrite the real state with
      // an object whose every field reads as undefined.
      if (block && (block.phase !== undefined || block.supported !== undefined)) {
        s.data.restart = block;
      }
    } else {
      s.data = await api('/update');
    }
    reached = true;
  } catch (e) {
    // Two very different failures land here. A 404/500 means the light endpoint is missing or
    // broken (an older server), so fall back to the full payload instead of waiting out forty
    // ticks against an endpoint that will never answer. Anything else is the JVM being down
    // mid-restart, which is the expected state and must stay silent.
    if (s.data && /^\d{3}\b/.test(e && e.message ? e.message : '')) {
      try {
        s.data = await api('/update');
        reached = true;
      } catch (ignored) { /* also unreachable: treat as still down */ }
    }
  }

  if (reached) {
    s.error = null;
    state.updateLoaded = true;
    renderUpdateNotice();
    renderUpdateBody();
    // renderUpdateBody() may itself restart the chain when a wait is still needed, so this is
    // the only place that decides to stop. No fall-through scheduling below on this path.
    if (!restartPollNeeded(s.data)) {
      stopRestartPoll();
      settleRestart(s.data);
      return;
    }
    if (restartPollTicks >= RESTART_POLL_MAX_TICKS) {
      stopRestartPoll();
      s.restartTimeout = true;
      renderUpdateBody();
      return;
    }
    if (!restartPollTimer) restartPollTimer = setTimeout(restartPollTick, RESTART_POLL_INTERVAL_MS);
    return;
  }

  // Unreachable so far: the process is still down. Keep waiting until the budget runs out.
  if (restartPollTicks >= RESTART_POLL_MAX_TICKS) {
    stopRestartPoll();
    s.restartTimeout = true;
    renderUpdateBody();
    return;
  }
  restartPollTimer = setTimeout(restartPollTick, RESTART_POLL_INTERVAL_MS);
}

/**
 * The moment the restart is over, one of two things happened, and they must not look alike.
 *
 * Success reloads the page: the browser is still running the old JS bundle and the old payload,
 * and a stale card after a successful upgrade is worse than a flash. The 800ms delay exists so
 * the toast is actually seen before the reload wipes it. A rollback never reloads — the point of
 * the red callout is that the user reads the reason and the log path.
 */
async function settleRestart(d) {
  var s = updateState();
  var r = restartInfo(d);
  if (restartFailed(d)) {
    s.pendingMsg = null;
    s.restartFailure = {
      message: r.message || '',
      logFile: r.logFile || '',
      version: r.attemptedVersion || ''
    };
    renderUpdateBody();
    renderUpdateNotice();
    return;
  }
  if (r.phase === 'succeeded') {
    var version = r.attemptedVersion || (s.pendingMsg && s.pendingMsg.version) || '';
    s.pendingMsg = null;
    s.restartFailure = null;
    try { await loadUpdate(); } catch (ignored) { /* the reload below is the real refresh */ }
    toast(version ? '已更新到 ' + version + ' 并完成重启' : '已更新并完成重启', 'success');
    setTimeout(function () { location.reload(); }, 800);
    return;
  }
  // Not running and not failed: the swap is on disk but this process never restarted, so the
  // manual instruction is still the honest thing to show.
  renderUpdateBody();
  renderUpdateNotice();
}

/* -------------------------------------------- background update progress */

/**
 * Two polls, each a single module-level id, and deliberately not one chain.
 *
 *   progressPollTimer  — 2 s, armed only while progress.active, stopped the moment it ends;
 *   progressWatchTimer — 15 s discovery, so a run the *server* started becomes visible at all.
 *
 * The second one is not redundant. Nothing else in this page polls /api/update: primeUpdateNotice()
 * runs once at boot and renderSettings() loads the payload once per page load. An unattended
 * download that begins ten minutes after the page opened would otherwise stay invisible until the
 * next navigation — which is exactly the complaint this feature exists to fix. It is armed only
 * when the payload says a background run is possible (autoDownload / autoApply), so an install
 * with both switches off pays nothing for it. Both timers live at module scope for the same reason
 * as restartPollTimer: renderUpdateBody() runs on every renderAll() tick, and a chain started from
 * inside a render would pile up one chain per tick.
 */
var progressPollTimer = null;
var progressWatchTimer = null;
var PROGRESS_POLL_INTERVAL_MS = 2000;
var PROGRESS_WATCH_INTERVAL_MS = 15000;
/** Consecutive failures the fast poll tolerates before handing the job back to the watch. */
var PROGRESS_POLL_MAX_FAILURES = 10;
var progressPollFailures = 0;
var progressPollInFlight = false;
var progressWatchInFlight = false;
/** Set once the server answers 404: it predates the endpoint, so stop asking forever. */
var progressEndpointMissing = false;

/** A finished run stays on screen this long, then fades out. */
var PROGRESS_FADE_MS = 5000;
var progressFadeTimer = null;
var progressFadeKey = null;
var progressFadedKey = null;

/** The progress sub-object, or null on a server that predates it. */
function progressInfo(d) {
  var p = d && d.progress;
  return p && typeof p === 'object' ? p : null;
}

/** Whether a pipeline is running right now; a missing block is never "active". */
function progressActive(d) {
  var p = progressInfo(d);
  return !!(p && p.active === true);
}

/** Identity of one progress block, used to decide whether a repaint is worth it. */
function progressKey(p) {
  if (!p) return '';
  return String(p.phase == null ? '' : p.phase) + '@' + (p.updatedAt == null ? '' : p.updatedAt);
}

function numOrNull(v) {
  if (v == null) return null;
  var n = Number(v);
  return isNaN(n) ? null : n;
}

/** "12.4 MB / 39.1 MB", or just what arrived when the total is unknown. */
function progressBytesText(bytes, total) {
  if (total != null && total > 0) {
    return fmtBytes(bytes == null ? 0 : bytes) + ' / ' + fmtBytes(total);
  }
  return bytes != null && bytes > 0 ? '已接收 ' + fmtBytes(bytes) : '';
}

function stopProgressPoll() {
  if (progressPollTimer) clearTimeout(progressPollTimer);
  progressPollTimer = null;
  progressPollFailures = 0;
}

/**
 * Arms the fast poll. A no-op while a chain is pending *or in flight*: the tick clears the id
 * before it awaits, so without the in-flight guard a renderUpdateBody() landing inside that window
 * would arm a second chain and both would keep polling.
 */
function startProgressPoll() {
  if (progressPollTimer || progressPollInFlight || progressEndpointMissing) return;
  progressPollTimer = setTimeout(progressPollTick, PROGRESS_POLL_INTERVAL_MS);
}

function stopProgressWatch() {
  if (progressWatchTimer) clearTimeout(progressWatchTimer);
  progressWatchTimer = null;
}

function startProgressWatch() {
  if (progressWatchTimer || progressWatchInFlight || progressEndpointMissing) return;
  progressWatchTimer = setTimeout(progressWatchTick, PROGRESS_WATCH_INTERVAL_MS);
}

/**
 * Adopts one block from the light endpoint.
 *
 * Only an object that actually looks like a progress block is merged: a proxy, a stale server or
 * an error page served at this path must not replace the real state with an object whose every
 * field reads as undefined. A new run also clears the fade marker, so a later success is shown
 * again without any state having to be reset elsewhere.
 */
function adoptProgress(block) {
  var s = updateState();
  if (!block || typeof block !== 'object') return false;
  if (block.phase === undefined && block.active === undefined) return false;
  if (!s.data) return false;
  s.data.progress = block;
  if (block.active === true) progressFadedKey = null;
  return true;
}

/**
 * The 2 s poll. Runs only while the pipeline is active and stops on the first terminal phase.
 *
 * Failures are mostly the JVM being down mid-restart, which the restart poll already owns, so they
 * stay silent — but only for a bounded number of tries: a dead server must not keep a chain alive
 * forever, and the slow watch is the one that survives that window.
 */
async function progressPollTick() {
  progressPollTimer = null;
  var s = updateState();
  if (progressEndpointMissing || !s.data) return;
  progressPollInFlight = true;
  var block;
  try {
    block = await api('/update/progress');
    progressPollFailures = 0;
  } catch (e) {
    progressPollInFlight = false;
    // 404 means the endpoint does not exist on this server at all; retrying cannot change that.
    if (/^404\b/.test(e && e.message ? e.message : '')) {
      progressEndpointMissing = true;
      stopProgressPoll();
      return;
    }
    // Mostly the JVM being down mid-restart, which the restart poll owns. Silent, but bounded: a
    // dead server must not keep a fast chain alive forever — the slow watch outlives that window.
    progressPollFailures++;
    if (progressPollFailures >= PROGRESS_POLL_MAX_FAILURES) {
      progressPollFailures = 0;
      stopProgressPoll();
      startProgressWatch();
      return;
    }
    startProgressPoll();
    return;
  }
  // Cleared before the render below, which itself re-arms through syncProgressTimers(): every
  // re-arm goes through startProgressPoll(), never through a direct setTimeout assignment, or the
  // id from one of the two paths would be overwritten and the orphan would fire as a second chain.
  progressPollInFlight = false;
  adoptProgress(block);
  renderUpdateNotice();
  if (state.page === 'settings') renderUpdateProgress(s.data);
  if (!progressActive(s.data)) {
    stopProgressPoll();
    // The run ended, so the pending jar and the release list just changed: one full refresh is
    // what makes the card agree with the strip that just said "已下载 …".
    try { await loadUpdate(); } catch (ignored) { /* the strip already says what happened */ }
    startProgressWatch();
    return;
  }
  startProgressPoll();
}

/**
 * The discovery probe: 15 s, and it never decides anything except "a run started".
 *
 * It stands down as soon as it sees progress.active and lets the fast poll take over; when that
 * one stops, it starts this probe again. A run that started and finished between two probes is
 * still repainted once, because a background failure must not need a navigation to be noticed.
 */
async function progressWatchTick() {
  progressWatchTimer = null;
  var s = updateState();
  // No cached payload yet (the boot fetch failed): nothing to merge into, and re-arming here would
  // keep a timer alive forever. loadUpdate() re-arms through renderUpdateNotice() once it succeeds.
  if (progressEndpointMissing || !s.data) return;
  var before = progressKey(progressInfo(s.data));
  progressWatchInFlight = true;
  var block;
  try {
    block = await api('/update/progress');
  } catch (e) {
    progressWatchInFlight = false;
    if (/^404\b/.test(e && e.message ? e.message : '')) {
      progressEndpointMissing = true;
      return;
    }
    // Offline, or restarting: keep probing, this is the only way a later run is ever noticed.
    startProgressWatch();
    return;
  }
  progressWatchInFlight = false;
  var merged = adoptProgress(block);
  if (progressActive(s.data)) {
    stopProgressWatch();
    renderUpdateNotice();
    if (state.page === 'settings') renderUpdateProgress(s.data);
    startProgressPoll();
    return;
  }
  // Same rule as the fast poll: re-arm only through startProgressWatch(), so a render landing in
  // this window cannot leave a second id behind.
  startProgressWatch();
  if (merged && progressKey(block) !== before) {
    renderUpdateNotice();
    if (state.page === 'settings') renderUpdateProgress(s.data);
  }
}

/**
 * Everything the progress UI needs, or null when there is nothing worth showing.
 *
 * Kept as data rather than markup because the same state is painted in two places — the banner and
 * the settings strip — and they must not drift into saying different things.
 */
function progressView(d) {
  var p = progressInfo(d);
  if (!p) return null;
  var phase = p.phase == null ? 'idle' : String(p.phase);
  if (!phase || phase === 'idle') return null;
  var key = progressKey(p);
  if (phase === 'succeeded' && progressFadedKey === key) return null;
  // While the supervisor is bringing the service back, renderUpdateRestart() already owns the
  // strip with its own spinner; two spinners for one restart read as two operations.
  if (phase === 'restarting' && restartActive(d)) return null;

  var automatic = p.automatic === true;
  var version = typeof p.version === 'string' ? p.version : '';
  var bytes = numOrNull(p.bytes);
  var total = numOrNull(p.totalBytes);
  var percent = numOrNull(p.percent);
  // -1 is the server's "total size unknown", not a percentage. Anything else is clamped, so a
  // stray value from an older server cannot paint a bar wider than its track.
  percent = percent == null || percent < 0 ? null : Math.max(0, Math.min(100, Math.round(percent)));
  var bytesText = progressBytesText(bytes, total);
  var prefix = automatic ? '后台自动更新 · ' : '';

  var view = {
    phase: phase, key: key, percent: percent, automatic: automatic, version: version,
    // busy means "in flight, nothing on this banner is clickable". A terminal result and a stalled
    // restart are not busy: the user has something to do about both.
    busy: phase === 'downloading' || phase === 'verifying' || phase === 'applying',
    spinner: false, tone: 'brand', icon: 'i-info', showBar: false, hint: '',
    // The banner has three text slots and one tone class; filling them here rather than in
    // renderUpdateNotice() keeps both surfaces saying the same sentence.
    title: '', detail: bytesText, bannerClass: '',
    bannerLead: '', bannerVersion: '', bannerTail: ''
  };

  if (phase === 'downloading') {
    view.title = prefix + '正在下载' + (version ? ' ' + version : '');
    view.spinner = true;
    view.showBar = true;
    view.bannerClass = 'updating';
    view.detail = bytesText && percent != null ? bytesText + ' · ' + percent + '%' : bytesText;
    view.bannerLead = prefix + '正在下载 ';
    view.bannerVersion = version || '…';
    view.bannerTail = view.detail ? '，' + view.detail : '';
  } else if (phase === 'verifying') {
    view.title = prefix + '正在校验文件' + (version ? ' ' + version : '');
    view.spinner = true;
    view.bannerClass = 'updating';
    view.bannerLead = prefix + '正在校验文件 ';
    view.bannerVersion = version || '…';
  } else if (phase === 'applying') {
    view.title = prefix + '正在替换 jar' + (version ? ' ' + version : '');
    view.spinner = true;
    view.bannerClass = 'updating';
    view.bannerLead = prefix + '正在替换 jar ';
    view.bannerVersion = version || '…';
  } else if (phase === 'restarting') {
    // The supervisor already owns this moment with its own spinner and its own timeout, and a
    // second spinner for one restart reads as two operations — so when it is really running, the
    // strip below is the only place that speaks.
    if (restartActive(d)) return null;
    // Nobody is going to bring the process back (the supervisor declined, or the platform cannot
    // restart): a spinner here would be the "forever" state this codebase keeps having to remove.
    // Say what is actually true instead, and leave the actions reachable.
    view.title = prefix + '已替换 jar，需要手动重启' + (version ? ' ' + version : '');
    view.tone = 'warning';
    view.icon = 'i-alert';
    view.hint = '自动重启没有执行，重启服务后新版本才会生效。';
    view.bannerClass = '';
    view.bannerLead = prefix + '已替换 jar，需要手动重启 ';
    view.bannerVersion = version || '…';
  } else if (phase === 'succeeded') {
    // The server's sentence is more specific than anything assembled here ("已下载 0.6.0" versus
    // "jar 已替换，需要手动重启服务"), so it wins when present.
    view.title = (typeof p.message === 'string' && p.message) ||
      (version ? '已下载 ' + version : '更新已完成');
    view.tone = '';
    view.icon = 'i-check-circle';
    view.detail = bytesText;
    view.bannerLead = view.title;
  } else if (phase === 'failed') {
    view.title = (typeof p.message === 'string' && p.message) ||
      (automatic ? '后台自动更新失败' : '更新失败');
    view.tone = 'danger';
    view.icon = 'i-alert-circle';
    view.bannerClass = 'failed';
    view.detail = typeof p.error === 'string' && p.error ? p.error : '';
    view.hint = '可在设置页重试，或查看日志。';
    view.bannerLead = view.title + '：';
    view.bannerTail = view.detail || '可在设置页重试，或查看日志。';
  } else {
    return null;
  }
  return view;
}

/** The shape of the strip. Text and bar are painted afterwards, so one code path fills both. */
function progressHtml(view) {
  return '<div class="callout ' + view.tone + '">' +
    (view.spinner ? '<span class="spinner"></span>' : icon(view.icon)) +
    '<div>' +
      '<strong data-prog-title></strong>' +
      '<div data-prog-detail style="margin-top:4px"></div>' +
      (view.showBar
        ? '<div class="progress thin" data-prog-bar role="progressbar" aria-valuemin="0" aria-valuemax="100"><span></span></div>'
        : '') +
      (view.hint ? '<div style="margin-top:4px">' + esc(view.hint) + '</div>' : '') +
    '</div></div>';
}

function paintProgress(host, view) {
  var title = host.querySelector('[data-prog-title]');
  if (title) title.textContent = view.title;
  var detail = host.querySelector('[data-prog-detail]');
  if (detail) {
    detail.textContent = view.detail || '';
    detail.classList.toggle('hidden', !view.detail);
  }
  var bar = host.querySelector('[data-prog-bar]');
  if (!bar) return;
  var fill = bar.querySelector('span');
  if (view.percent == null) {
    // Unknown total size: announcing a percentage would be a lie, and a bar parked at 0% reads as
    // "stuck". The indeterminate class lets the fill slide instead (see style.css).
    bar.removeAttribute('aria-valuenow');
    bar.classList.add('indeterminate');
    if (fill) fill.style.width = '';
  } else {
    bar.setAttribute('aria-valuenow', String(view.percent));
    bar.classList.remove('indeterminate');
    if (fill) fill.style.width = view.percent + '%';
  }
}

/**
 * The download/apply progress strip above the card body.
 *
 * Same stable-node reasoning as renderUpdateRestart: #upd-body is rewritten wholesale on every
 * renderAll() tick, and a spinner re-created there would restart its animation constantly. The host
 * is static markup in index.html; this only rebuilds its contents when the *phase* changes and
 * otherwise paints the text and the bar in place, so a two-second poll does not flicker.
 */
function clearUpdateProgress() {
  var host = $('upd-progress');
  if (!host) return;
  if (host.dataset.phase) {
    host.innerHTML = '';
    delete host.dataset.phase;
  }
  host.classList.add('hidden');
}

function renderUpdateProgress(d) {
  var host = $('upd-progress');
  if (!host) return;
  var view = progressView(d);
  if (!view) {
    clearUpdateProgress();
    return;
  }
  host.classList.remove('hidden');
  if (host.dataset.phase !== view.phase) {
    host.dataset.phase = view.phase;
    host.innerHTML = progressHtml(view);
  }
  paintProgress(host, view);
  scheduleProgressFade(view);
}

/**
 * Fades a finished run out after five seconds.
 *
 * Keyed on the block's own updatedAt instead of a boolean: the next run produces a new key, so a
 * later success is shown again without anything having to reset a flag. Module-level like the
 * timers, because the render that schedules it runs on every poll tick.
 */
function scheduleProgressFade(view) {
  if (!view || view.phase !== 'succeeded') {
    // A new run (or none at all) makes any pending fade irrelevant: the block it was counting
    // down for is no longer the one on screen.
    if (progressFadeTimer) clearTimeout(progressFadeTimer);
    progressFadeTimer = null;
    progressFadeKey = null;
    return;
  }
  if (progressFadedKey === view.key) return;
  if (progressFadeTimer && progressFadeKey === view.key) return;
  if (progressFadeTimer) clearTimeout(progressFadeTimer);
  progressFadeKey = view.key;
  progressFadeTimer = setTimeout(function () {
    progressFadeTimer = null;
    progressFadedKey = progressFadeKey;
    renderUpdateNotice();
    renderUpdateProgress(updateState().data);
  }, PROGRESS_FADE_MS);
}

/**
 * The progress strip above the card body.
 *
 * It lives outside #upd-body on purpose: renderUpdateBody() rewrites that host wholesale on every
 * poll tick, and a spinner re-created every three seconds restarts its animation each time, which
 * reads as a stutter exactly when the user is watching it for reassurance.
 */
function renderUpdateRestart(d) {
  var host = $('upd-restart');
  if (!host) return;
  var s = updateState();
  var r = restartInfo(d);
  var html = '';

  if (s.restartFailure) {
    var f = s.restartFailure;
    html = '<div class="callout danger">' + icon('i-alert-circle') + '<div>' +
      '<strong>升级失败，已回滚到原版本</strong>' +
      (f.version ? '<div style="margin-top:4px">目标版本 ' + esc(f.version) + ' 未能启动。</div>' : '') +
      (f.message ? '<div style="margin-top:4px">' + esc(f.message) + '</div>' : '') +
      '<div style="margin-top:4px">当前仍在运行升级前的版本，服务可用。可以重新检查更新后再试一次。</div>' +
      (f.logFile ? '<div class="restart-cmd mono">日志：<code>' + esc(f.logFile) + '</code></div>' : '') +
      '</div></div>';
  } else if (restartFailed(d)) {
    var tried = r.attemptedVersion || (s.pendingMsg && s.pendingMsg.version) || '';
    html = '<div class="callout danger">' + icon('i-alert-circle') + '<div>' +
      '<strong>升级失败，已回滚到原版本</strong>' +
      (tried ? '<div style="margin-top:4px">目标版本 ' + esc(tried) + ' 未能启动。</div>' : '') +
      (r.message ? '<div style="margin-top:4px">' + esc(r.message) + '</div>' : '') +
      (r.logFile ? '<div class="restart-cmd mono">日志：<code>' + esc(r.logFile) + '</code></div>' : '') +
      '</div></div>';
  } else if (restartActive(d)) {
    // restartActive() is phase-driven, so a supervisor that never started (idle) falls through to
    // the manual-command states below instead of leaving a spinner running forever.
    var ver = r.attemptedVersion || (s.pendingMsg && s.pendingMsg.version) || '';
    var timeout = d && d.restartTimeoutSeconds ? d.restartTimeoutSeconds : 90;
    html = '<div class="callout brand">' +
      '<span class="spinner"></span><div>' +
      '<strong>' + (ver ? '正在重启到 ' + esc(ver) + '…' : '正在重启服务…') + '</strong>' +
      (r.message ? '<div style="margin-top:4px">' + esc(r.message) + '</div>' : '') +
      '<div style="margin-top:4px">预计 ' + esc(String(timeout)) +
      ' 秒内完成，失败会自动回滚。这期间页面会短暂断开，恢复后自动刷新。</div>' +
      (r.healthUrl ? '<div class="restart-cmd mono">健康检查：<code>' + esc(r.healthUrl) + '</code></div>' : '') +
      '</div></div>';
  } else if (s.restartTimeout) {
    html = '<div class="callout warning">' + icon('i-alert') + '<div>' +
      '<strong>重启后等待超时</strong>' +
      '<div style="margin-top:4px">已经等了约 ' +
      Math.round(RESTART_POLL_MAX_TICKS * RESTART_POLL_INTERVAL_MS / 1000) +
      ' 秒仍未拿到服务响应。请确认服务是否已启动，或查看日志。</div>' +
      '<div style="margin-top:8px"><button class="btn btn-sm" data-update-restart-retry>' +
      icon('i-refresh') + '重新检查</button></div></div></div>';
  }

  host.innerHTML = html;
  host.classList.toggle('hidden', !html);
}

/* ----------------------------------------------------- auto-update switches */

/**
 * One switch. Both the key and the value a click would write are in the DOM, so the click handler
 * never has to infer what the button currently shows — it reads the intent the render baked in.
 */
function updateSwitch(key, on, label, hint, disabled, disabledReason) {
  return '<div class="upd-setting-row">' +
    '<div class="upd-setting-main">' +
      '<div class="upd-setting-name">' + esc(label) + '</div>' +
      '<div class="upd-setting-hint">' + esc(hint) + '</div>' +
    '</div>' +
    '<button type="button" class="upd-switch' + (on ? ' on' : '') + '"' +
      ' data-update-setting="' + esc(key) + '"' +
      ' data-update-setting-next="' + (on ? 'false' : 'true') + '"' +
      ' role="switch" aria-checked="' + (on ? 'true' : 'false') + '"' +
      ' aria-label="' + esc(label + '：' + (on ? '已开启' : '已关闭')) + '"' +
      (disabled ? ' disabled title="' + esc(disabledReason || '暂不可用') + '"' : '') + '>' +
      '<span class="upd-switch-knob" aria-hidden="true"></span>' +
      '<span class="upd-switch-text">' + (on ? '开' : '关') + '</span>' +
    '</button>' +
    '</div>';
}

function renderAutoUpdateSettings(d) {
  var s = updateState();
  var busy = !!s.savingSettings;
  var supported = restartSupported(d);
  var unsupported = restartInfo(d).unsupportedReason;
  // On a server that predates these fields, saying so once beats three switches that would PUT to
  // an endpoint that does not exist.
  if (d.autoDownload === undefined && d.autoApply === undefined && d.autoRestart === undefined) {
    return '<div class="section-label">自动更新设置</div>' +
      '<div class="callout warning">' + icon('i-alert') + '<div>' +
      '当前服务端不支持在线修改自动更新设置。请在 <span class="mono">application.yml</span> 的 ' +
      '<span class="mono">code-association.update</span> 下调整后重启服务。</div></div>';
  }
  return '<div class="section-label">自动更新设置</div>' +
    '<div class="upd-settings">' +
      updateSwitch('autoDownload', d.autoDownload === true, '自动下载',
        '发现可安装的新版本时自动下载 jar，但不会替换当前运行的版本。', busy) +
      updateSwitch('autoApply', d.autoApply === true, '自动应用并重启',
        '下载完成后自动替换 jar 并重启服务。需要同时开启「应用后自动重启」。', busy) +
      updateSwitch('autoRestart', d.autoRestart === true, '应用后自动重启',
        supported
          ? '替换 jar 后由服务自行重启，健康检查失败会自动回滚到原版本。'
          : '当前环境不支持热重启，开启后仍需手动执行 stop/start 脚本。',
        busy, unsupported) +
    '</div>' +
    (busy ? '<div class="upd-loading"><span class="spinner"></span>正在保存…</div>' : '');
}

/**
 * Persists one switch. Only the changed key is sent, so two tabs toggling different switches
 * cannot clobber each other's value.
 *
 * The server rejects autoApply without autoRestart (400 + message). That error is toasted and the
 * switch simply re-renders from the unchanged payload: the rollback is a re-render, not an undo.
 */
async function saveUpdateSetting(key, next) {
  var s = updateState();
  if (s.savingSettings) return;
  s.savingSettings = true;
  renderUpdateBody();
  var body = {};
  body[key] = next;
  try {
    var data = await api('/update/settings', { method: 'PUT', body: JSON.stringify(body) });
    // The response is a full payload, so every other number on the card stays in step with the
    // save instead of going stale behind it.
    if (data) s.data = data;
    s.error = null;
    toast('已保存', 'success');
    renderUpdateNotice();
  } catch (e) {
    toast(e && e.message ? e.message : String(e), 'danger');
  } finally {
    s.savingSettings = false;
    renderUpdateBody();
  }
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
  // A restart outranks a version notice: for those few seconds the only thing worth saying at
  // the top of the page is that the service is coming back. Every action button is hidden with
  // it — "查看" and "忽略此版本" both describe a release the user cannot act on right now, and
  // closing the strip would leave them staring at a page whose backend is gone.
  var restarting = restartActive(d);
  // A running download outranks the version notice too, and outranks the user's earlier "close":
  // the notice says a release exists, while this says the machine is already fetching it. For the
  // same reason as the restart case, the action buttons are hidden — none of them apply to a
  // transfer the user did not start. A terminal result (failed / succeeded) keeps "查看" so the
  // card can be reached, but is still dismissible by its own key.
  var progress = progressView(d);
  // Scheduled from here rather than only from renderUpdateProgress(): that one runs on the
  // settings page alone, so a run that finished while the user was elsewhere would leave the
  // banner saying "已下载 …" for the rest of the page load. Passing null clears a stale timer.
  scheduleProgressFade(progress);
  var progressBusy = !!(progress && progress.busy);
  var progressDismissed = !!(progress && !progressBusy &&
    state.updateBannerClosed === progress.key);
  var showProgress = !!progress && !progressDismissed;
  if (banner) {
    // Shown on every page including settings: the banner is the one place that states the
    // version at a glance, and the settings card below it may be scrolled out of view.
    var show = restarting || showProgress || (pending && notice.version !== state.updateBannerClosed);
    banner.classList.toggle('hidden', !show);
    banner.classList.toggle('tag-only', !restarting && !showProgress && notice.kind === 'tagOnly');
    banner.classList.toggle('restarting', restarting);
    // The tone classes are exclusive: a failed download must not keep the brand fill it had while
    // it was running, and a running one must not keep the red of the previous failure.
    banner.classList.toggle('updating', !restarting && showProgress && progress.bannerClass === 'updating');
    banner.classList.toggle('failed', !restarting && showProgress && progress.bannerClass === 'failed');
    if (show) {
      var ver = $('update-banner-version');
      var cur = $('update-banner-current');
      var lead = $('update-banner-lead');
      var tail = $('update-banner-tail');
      var view = banner.querySelector('[data-update-banner-view]');
      var actions = $('update-banner-actions');
      var iconHost = $('update-banner-icon');
      var skip = banner.querySelector('[data-update-banner-skip]');
      if (iconHost) {
        // One spinner, never two: the strip below has no spinner while a restart is in flight
        // (see renderUpdateRestart), and this is the single live region for it.
        iconHost.innerHTML = (restarting || progressBusy)
          ? '<span class="spinner"></span>'
          : progress ? icon(progress.icon) : icon('i-arrow-up');
      }
      if (actions) actions.classList.toggle('hidden', restarting || progressBusy);
      // 忽略此版本 is about a release notice; on a progress banner it would silence the wrong thing.
      if (skip) skip.classList.toggle('hidden', !!progress);
      if (restarting) {
        var target = restartInfo(d).attemptedVersion ||
          (updateState().pendingMsg && updateState().pendingMsg.version) || '';
        if (lead) lead.textContent = target ? '正在重启到 ' : '正在重启服务';
        if (ver) ver.textContent = target || '…';
        if (tail) tail.textContent = '，完成后页面会自动刷新';
        if (cur) cur.textContent = '';
      } else if (showProgress) {
        if (lead) lead.textContent = progress.bannerLead;
        if (ver) ver.textContent = progress.bannerVersion || '';
        if (tail) tail.textContent = progress.bannerTail || '';
        if (cur) cur.textContent = '';
        if (view) view.classList.toggle('hidden', progress.phase !== 'failed' && progress.phase !== 'succeeded');
      } else {
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
  }
  var dot = $('rail-update-dot');
  if (dot) dot.classList.toggle('hidden', !pending && !restarting && !showProgress);
  syncProgressTimers(d);
}

/**
 * Keeps exactly one of the two progress timers armed, from whatever render happens to run.
 *
 * Called from renderUpdateNotice() because that runs on every page (the banner is global), while
 * renderUpdateBody() only runs on settings. Idempotent by construction: each start*() returns early
 * when a chain is pending or in flight, so the many renderAll() ticks cannot stack chains.
 */
function syncProgressTimers(d) {
  if (progressEndpointMissing) {
    stopProgressPoll();
    stopProgressWatch();
    return;
  }
  if (progressActive(d)) {
    stopProgressWatch();
    startProgressPoll();
    return;
  }
  stopProgressPoll();
  // The discovery probe exists only for runs nobody asked for. With both automatic behaviours off,
  // no background pipeline can start on its own, so probing would be pure noise — and an explicit
  // download by the user already re-fetches the payload that carries the new progress block.
  if (d && (d.autoDownload === true || d.autoApply === true)) startProgressWatch();
  else stopProgressWatch();
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
  // A terminal progress result is closed by its own key, so dismissing "已下载 0.6.0" cannot also
  // silence the release notice for that version — they are two different messages with two
  // different lifetimes.
  var progress = progressView(updateState().data);
  if (progress && !progress.busy) {
    state.updateBannerClosed = progress.key;
    renderUpdateNotice();
    return;
  }
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

/**
 * Why the "应用更新" button is disabled, shown as tooltip and as an inline note.
 *
 * The server decides this now: it can see whether it was launched from a jar, from target/classes
 * (mvn spring-boot:run, IDE) or some other way, so its reason is always more accurate than a guess
 * made here. The old text guessed "possibly started in an IDE", which was wrong for the common
 * case it was written for — someone who ran "mvn spring-boot:run" in a terminal, not an IDE. The
 * local branches survive only as the fallback for a server that predates the field: a missing key
 * must not blank out the callout.
 */
function applyBlockedReason(d) {
  if (!d) return '暂时无法判断当前环境是否支持自动应用。';
  if (typeof d.applyUnsupportedReason === 'string' && d.applyUnsupportedReason.trim() !== '') {
    return d.applyUnsupportedReason;
  }
  if (d.runningFromJar) return '检测到无法替换运行中的 jar（Windows 文件锁或权限不足），请手动下载后替换。';
  return '当前不是以 java -jar 方式运行（可能是在 IDE 里启动），替换 jar 不会生效。';
}

/**
 * The "what do I run instead" line under a blocked apply. The command comes from the server
 * because only it knows the launch mode; rendering it as <code> rather than prose keeps it
 * copy-pasteable. Absent on a supported install and on an older payload, so callers get ''.
 */
function applyHintHtml(d) {
  if (!d || typeof d.applyHint !== 'string' || d.applyHint.trim() === '') return '';
  return '<div class="restart-cmd mono">建议命令：<code>' + esc(d.applyHint) + '</code></div>';
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
  // How this process was launched explains why auto-apply may be refused, so it belongs next to
  // the version rather than only inside the warning text. Rendered only when the server sends a
  // label: an older payload would otherwise produce a row reading "—", which looks like a broken
  // install instead of a server that simply predates the field.
  // Not kvRow(): its .v truncates to a single line with an ellipsis, and a launch label is a whole
  // sentence ("开发模式（mvn spring-boot:run、IDE 或测试直接运行 target/classes）"), so the half
  // that explains the refusal would be exactly the half cut off.
  if (typeof d.launchModeLabel === 'string' && d.launchModeLabel.trim() !== '') {
    rows.push('<div class="kv-row"><span class="k">启动方式</span>' +
      '<span class="v kv-wrap">' + esc(d.launchModeLabel) + '</span></div>');
  }
  if (d.repo) rows.push(kvRow('发布仓库', d.repo));
  if (d.checkIntervalMinutes) rows.push(kvRow('自动检查间隔', d.checkIntervalMinutes + ' 分钟'));
  // Network resilience. Only rendered when the server actually reports the block: an older
  // payload would otherwise grow five rows all reading "—", which looks like a broken config
  // rather than a version that simply predates the feature.
  var net = networkInfo(d);
  if (net) {
    rows.push(kvRow('下载镜像', net.downloadMirror ? net.downloadMirror : '直连 github.com'));
    rows.push(kvRow('代理', net.proxyConfigured === true ? '已配置' : '未配置'));
    rows.push(kvRow('失败重试', (net.retries == null ? 0 : net.retries) + ' 次'));
    // The reset time is only meaningful while the gate is actually closed; a stale timestamp
    // from an expired window would read as "limited right now" when it is not.
    if (net.rateLimitedUntil) rows.push(kvRow('限流截止', fmtTime(net.rateLimitedUntil)));
    var urls = net.apiBaseUrls || [];
    if (urls.length > 1) rows.push(kvRow('备用 API 源', (urls.length - 1) + ' 个'));
    if (net.activeApiBase && urls.length > 1) rows.push(kvRow('当前 API 源', net.activeApiBase));
  }

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
      // The reason alone leaves the user stuck ("so what do I do?"). The server also knows which
      // command would make auto-apply possible, so show it as a copyable command line.
      applyHintHtml(d) +
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
  var html = '';
  // Rate limiting comes first because it explains every other warning below it: a check that
  // "failed" while the quota was exhausted did not really fail, it was told to wait.
  var net = networkInfo(d);
  if (net && net.rateLimitedUntil) {
    html += '<div class="callout warning" style="margin-bottom:14px">' + icon('i-clock') + '<div>' +
      '<strong>GitHub 已限流</strong>' +
      '<div style="margin-top:4px">将于 ' + esc(fmtTime(net.rateLimitedUntil)) +
      ' 恢复。配置 GitHub token 可以提高额度，或降低自动检查频率。</div>' +
      (net.downloadBlocked
        ? '<div style="margin-top:4px">下载通道当前不可用' +
          (net.lastFailureHost ? '（' + esc(net.lastFailureHost) + '）' : '') + '。</div>'
        : '') +
      '</div></div>';
  } else if (net && net.downloadBlocked) {
    html += '<div class="callout warning" style="margin-bottom:14px">' + icon('i-alert') + '<div>' +
      '<strong>下载通道不可用</strong>' +
      '<div style="margin-top:4px">无法连接 ' +
      esc(net.lastFailureHost || net.activeApiBase || 'GitHub') +
      '。可以在配置中设置下载镜像或代理后重试。</div></div></div>';
  }
  var warnings = (d && d.warnings) || [];
  if (!warnings.length) return html;
  return html + '<div class="callout warning" style="margin-bottom:14px">' + icon('i-alert') + '<div>' +
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

  // The progress strips outlive the body rewrite below, so the early returns have to clear them
  // explicitly — otherwise "正在重启…" would stay on screen over an error card.
  var strip = $('upd-restart');
  if (strip && (!d || d.enabled === false)) {
    strip.innerHTML = '';
    strip.classList.add('hidden');
  }
  if (!d || d.enabled === false) clearUpdateProgress();

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
  // Three cases, and the difference between them is the whole point of the restart supervisor:
  //
  //   auto restart available  — the service will bring itself back; show progress, never commands;
  //   restart switched off    — keep the commands, plus a pointer at the switch that removes them;
  //   restart not supported   — keep the commands, plus the server's reason.
  //
  // The manual commands stay in the last two cases because hiding them would strand anyone whose
  // platform cannot hot-restart, which is the exact situation the fallback exists for.
  var pendingCallout = '';
  if (s.pendingMsg) {
    var pVer = esc(s.pendingMsg.version || '目标版本');
    var pKind = s.pendingMsg.kind === 'rollback';
    var autoRestart = autoRestartEnabled(d);
    var supported = restartSupported(d);
    var head = '<strong>' + (pKind ? '已回滚到 ' : '已替换为 ') + pVer + '</strong>';
    if (supported && autoRestart && restartActive(d)) {
      // Deliberately empty: the progress strip above (renderUpdateRestart) already says
      // "正在重启到 x…" with a spinner, and two spinners for one restart read as two operations.
      // Saying "restart it yourself" here would be worse still — it would have people racing the
      // process that is about to come back.
      pendingCallout = '';
    } else if (!supported) {
      pendingCallout = '<div class="callout warning" style="margin-bottom:14px">' + icon('i-alert') + '<div>' +
        head +
        '<div style="margin-top:4px">jar 已写入磁盘，但当前进程仍在用旧代码，且当前环境不支持自动重启，需要手动重启服务。</div>' +
        (restartInfo(d).unsupportedReason
          ? '<div style="margin-top:4px">原因：' + esc(restartInfo(d).unsupportedReason) + '</div>' : '') +
        '<div class="restart-cmd mono">POSIX：<code>./stop.sh &amp;&amp; ./start.sh</code></div>' +
        '<div class="restart-cmd mono">Windows：先运行 <code>stop.bat</code>，再运行 <code>start.bat</code></div>' +
        '</div></div>';
    } else {
      // autoRestart is on and the platform supports it, yet no restart is running: the supervisor
      // declined this one (it already had a restart in flight, or it stopped after a failure).
      // Telling the user to switch on a setting that is already on would be a dead end, so this
      // variant says what is actually true and offers the retry.
      var stalled = supported && autoRestart;
      pendingCallout = '<div class="callout warning" style="margin-bottom:14px">' + icon('i-alert') + '<div>' +
        head +
        '<div style="margin-top:4px">jar 已写入磁盘，但当前进程仍在用旧代码。重启服务后新版本才会生效。</div>' +
        (stalled
          ? '<div style="margin-top:4px">自动重启本次没有执行' +
            (restartInfo(d).message ? '：' + esc(restartInfo(d).message) : '') +
            '，可以手动重启，或再应用一次。</div>'
          : '<div style="margin-top:6px">可以在下方「自动更新设置」中开启「应用后自动重启」，之后就不需要手动执行这些命令。</div>') +
        '<div class="restart-cmd mono">POSIX：<code>./stop.sh &amp;&amp; ./start.sh</code></div>' +
        '<div class="restart-cmd mono">Windows：先运行 <code>stop.bat</code>，再运行 <code>start.bat</code></div>' +
        '</div></div>';
    }
  }
  html += pendingCallout;
  html += renderUpdateWarnings(d);
  html += renderUpdateSummary(d);
  html += renderAutoUpdateSettings(d);
  html += '<div class="section-label">历史版本</div>';
  html += releases.length
    ? '<div class="rel-list">' + releases.map(renderReleaseRow).join('') + '</div>'
    : emptyBlock('没有版本记录', '检查一次后这里会列出 GitHub 上发布的版本。');
  html += '<div class="section-label">本机版本记录</div>' + renderUpdateHistory();
  host.innerHTML = html;

  // Both progress strips are separate, stable nodes (see renderUpdateProgress / renderUpdateRestart),
  // so they are rendered outside the innerHTML assignment above.
  renderUpdateRestart(d);
  renderUpdateProgress(d);

  // Polling is armed here, not only from applyUpdate(), so a page reloaded mid-restart picks the
  // watch back up instead of sitting on a card that never moves. startRestartPoll() is a no-op
  // while a timer is already pending, which is what keeps the many renderAll() ticks from
  // stacking many chains; the tick budget itself lives in restartPollTicks and is not reset here.
  if (restartPollNeeded(d)) startRestartPoll();
  else if (restartPollTimer) stopRestartPoll();
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
    // While a restart is in flight, "connection refused" is the service being down on purpose.
    // Storing it as an error would replace the progress card with a red failure the user cannot
    // act on; the poll is already the thing that will recover the page.
    if (!restartPollNeeded(s.data)) {
      s.error = e && e.message ? e.message : String(e);
    }
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
  s.restartFailure = null;
  s.restartTimeout = false;
  stopRestartPoll();
  restartPollTicks = 0;
  renderUpdateBody();
  try {
    var res = await api('/update/apply', { method: 'POST' });
    // Remember when the swap happened (server clock): it is the only reliable
    // "not restarted yet" signal, see the guard in renderUpdateBody().
    s.pendingMsg = { kind: 'apply', version: res && res.version, backupPath: res && res.backupPath,
                     swappedAt: res && res.swappedAt };
    // The apply response carries the supervisor's own restart block, which is the authoritative
    // answer to "did it actually start". Adopting it here removes the guesswork: a supervisor that
    // declined (platform unsupported, another restart already running) reports phase=idle and the
    // card goes straight to the manual-command state instead of showing a spinner that never ends.
    if (s.data && res && res.restart && typeof res.restart === 'object') {
      s.data.restart = res.restart;
    }
    var selfRestart = restartActive(s.data);
    toast(selfRestart
      ? '已替换 jar，正在重启服务…'
      : '已替换 jar，请重启服务生效', 'success');
  } catch (e) {
    toast('应用更新失败：' + (e && e.message ? e.message : e), 'error');
  } finally {
    s.busy = false;
    // loadUpdate() during a self-restart usually fails with "connection refused" once the old
    // process exits; that is expected, and the poll below is what actually waits it out.
    try { await loadUpdate(); } catch (ignored) { renderUpdateBody(); }
    if (restartPollNeeded(s.data)) startRestartPoll();
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
  // 全量渲染把每张页面都重画了，之前攒下的脏标自然作废。
  state.stalePages = {};
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
  // Reset through the same switch the select uses, so labels, datalists and the preview cannot
  // drift out of sync with the mode after a modal was left in range mode.
  applyRunMode('GIT_DIFF');
  $('mr-error').style.display = 'none';
  openModal('modal-run');
  // Branches come from the repository itself; the pickers are suggestions, not free text.
  await Promise.all([loadRefs(current.id), renderPeerPicker(current.id), primeRunAiOption()]);
}

/**
 * The run dialog's optional AI checkbox.
 *
 * Three outcomes, and the middle one is why this is a function rather than a template line: a
 * configured installation gets the checkbox pre-set from `defaultForRuns` (still changeable for
 * this run), an unconfigured one gets it disabled with the reason, and a server that does not know
 * the endpoint at all hides the row - a dead checkbox in the dialog would look like a bug in the
 * run form rather than an optional feature nobody enabled.
 *
 * A failure to read /api/ai must never block starting an analysis, so every error path ends in
 * "hide the row".
 */
async function primeRunAiOption() {
  var wrap = $('mr-ai-wrap');
  var box = $('mr-ai');
  var hint = $('mr-ai-hint');
  if (!wrap || !box) return;
  var ai = state.ai;
  if (!ai.loaded) {
    try {
      ai.data = await api('/ai');
      ai.loaded = true;
      ai.error = null;
    } catch (e) {
      ai.error = e && e.message ? e.message : String(e);
    }
  }
  if (ai.error || !ai.data) {
    wrap.classList.add('hidden');
    return;
  }
  var ready = ai.data.ready === true;
  wrap.classList.remove('hidden');
  box.disabled = !ready;
  box.checked = ready && ai.data.defaultForRuns === true;
  if (hint) {
    hint.textContent = ready
      ? '只发送本次分析摘要（变更、影响路径与原因、盲区），不发送源码全文。'
      : 'AI 审核未配置完成：' + (ai.data.reason || '缺少接口地址或模型') + '。可在「设置 → AI 审核」中启用。';
  }
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

/* ------------------------------------------------------------- commit range */

/**
 * Commit suggestions for the range pickers.
 *
 * The list is filled from the ref currently in the target box, because that is the branch the user
 * is already thinking about; without it the picker would only offer the repository default and the
 * user would have to type a sha from memory.
 */
async function loadCommits(sourceId) {
  var list = $('mr-commits');
  if (!list || !sourceId) return;
  list.innerHTML = '';
  // The branch the user is already pointed at, or the source default before anything is typed.
  var ref = $('mr-target') ? $('mr-target').value.trim() : '';
  if (!ref) {
    var src = state.sources.filter(function (x) { return x.id === sourceId; })[0];
    ref = (src && src.defaultRef) || '';
  }
  var query = '/sources/' + sourceId + '/commits?limit=30' + (ref ? '&ref=' + encodeURIComponent(ref) : '');
  try {
    var payload = await api(query);
    var commits = (payload && payload.commits) || [];
    list.innerHTML = commits.map(function (c) {
      var sha = c.shortSha || String(c.sha || '').slice(0, 8);
      return '<option value="' + esc(sha) + '" label="' + esc(c.subject || '') + '"></option>';
    }).join('');
  } catch (e) {
    // Suggestions are a convenience; an unreadable repository must not block the modal. The
    // preview area is where the user learns why, so nothing is reported here.
  }
}

/**
 * The commits the current range would analyse, shown before the run starts.
 *
 * Debounced because both inputs fire on every keystroke and each call shells out to git; a range
 * half typed is also not worth a request, so it only runs once both ends are non-empty.
 */
var rangePreviewTimer = null;
/** Bumped per request so a slow answer for an old range cannot overwrite a newer one. */
var rangePreviewSeq = 0;
/**
 * The last range payload rendered, kept so a tick can update the hint without refetching.
 *
 * Without it every checkbox click would either re-run git or update nothing; the counts it needs
 * (total, note) are already in the payload that produced the rows on screen.
 */
var lastRangePayload = null;

function scheduleRangePreview() {
  if (rangePreviewTimer) clearTimeout(rangePreviewTimer);
  rangePreviewTimer = setTimeout(function () {
    rangePreviewTimer = null;
    loadRangePreview();
  }, 300);
}

async function loadRangePreview() {
  var host = $('mr-range-list');
  var hint = $('mr-range-hint');
  if (!host || !hint) return;
  var base = $('mr-base').value.trim();
  var target = $('mr-target').value.trim();
  if (!base || !target) {
    host.innerHTML = '';
    hint.textContent = '两端都填好后，这里会列出范围内的提交。';
    return;
  }
  var sourceId = Number($('mr-source').value);
  if (!sourceId) {
    host.innerHTML = '';
    hint.textContent = '请先选择代码源。';
    return;
  }
  var seq = ++rangePreviewSeq;
  hint.textContent = '读取中…';
  host.innerHTML = '';
  try {
    var payload = await api('/sources/' + sourceId + '/commits?base=' + encodeURIComponent(base) +
      '&target=' + encodeURIComponent(target) + '&limit=100');
    // A newer keystroke already asked for a different range; this answer is stale.
    if (seq !== rangePreviewSeq) return;
    renderRangePreview(payload || {});
  } catch (e) {
    if (seq !== rangePreviewSeq) return;
    // One line, no toast: the server answers 200 with a note for the expected failures (not a
    // repository, unknown ref), so a thrown error here is the unexpected path and still must not
    // turn into an error dialog.
    hint.textContent = '无法读取提交范围：' + e.message;
    host.innerHTML = '';
  }
}

function renderRangePreview(payload) {
  var host = $('mr-range-list');
  var hint = $('mr-range-hint');
  if (!host || !hint) return;
  var commits = payload.commits || [];
  if (!commits.length) {
    host.innerHTML = '';
    hint.textContent = payload.note || '这个范围内没有提交。';
    return;
  }
  var shown = commits.slice(0, 30);
  lastRangePayload = payload;
  var picked = state.rangePicked || {};
  // Every listed commit gets a checkbox: the range says what *could* be reviewed, and the ticks
  // say what actually is. Selection survives a re-render because it lives in state.rangePicked
  // keyed by full sha - rebuilding the list after each keystroke must not silently clear it.
  var rows = shown.map(function (c) {
    var sha = c.sha || '';
    var on = picked[sha] === true;
    return '<label class="commit-row selectable' + (on ? ' selected' : '') + '">' +
      '<input type="checkbox" data-range-commit="' + esc(sha) + '"' + (on ? ' checked' : '') + '/>' +
      '<span class="commit-sha">' + esc(c.shortSha || String(sha).slice(0, 8)) + '</span>' +
      '<span class="commit-subject">' + esc(c.subject || '') + '</span>' +
      '<span class="commit-meta">' + esc(c.author || '') + ' · ' + esc(fmtTime(c.date)) + '</span>' +
      '</label>';
  }).join('');
  host.innerHTML = rows + (commits.length > shown.length
    ? '<div class="commit-more">还有 ' + (commits.length - shown.length) + ' 个提交未列出，缩小范围可看到更多</div>'
    : '');
  renderRangePickHint(payload);
}

/** The full shas of the ticked commits, in list order (newest first). */
function selectedRangeCommits() {
  var picked = state.rangePicked || {};
  return Object.keys(picked).filter(function (sha) {
    return picked[sha] === true;
  });
}

/** How many commits are ticked, and what the run will therefore analyse. */
function renderRangePickHint(payload) {
  var hint = $('mr-range-hint');
  if (!hint) return;
  var count = Object.keys(state.rangePicked || {}).filter(function (k) {
    return state.rangePicked[k] === true;
  }).length;
  var total = payload && payload.count != null ? payload.count : 0;
  var base = '共 ' + total + ' 个提交';
  if (payload && payload.note) base += ' · ' + payload.note;
  // Ticking nothing is the normal state and means "review the whole range", so it must not read
  // like an error the user has to fix before starting.
  hint.textContent = count
    ? base + ' · 已选 ' + count + ' 个，将只分析这些提交'
    : base + ' · 勾选可只分析其中几个，不勾则分析整个范围';
}

/**
 * Switches the modal between "pick a branch" and "pick a commit range".
 *
 * Labels, datalist and placeholder all change together: leaving the old label ("基线") above a
 * commit-sha input would describe the wrong thing, since the start of a range is exclusive.
 */
function applyRunMode(mode) {
  var range = mode === 'COMMIT_RANGE';
  $('mr-pr-wrap').classList.toggle('hidden', mode !== 'PULL_REQUEST');
  $('mr-base-wrap').classList.toggle('hidden', mode === 'WORKTREE_DIFF');
  var preview = $('mr-range-preview');
  if (preview) preview.classList.toggle('hidden', !range);

  var baseLabel = $('mr-base-label');
  var targetLabel = $('mr-target-label');
  if (baseLabel) baseLabel.textContent = range ? '起始提交（不含）' : '基线';
  if (targetLabel) targetLabel.textContent = range ? '结束提交（含）' : '目标';

  $('mr-base').setAttribute('list', range ? 'mr-commits' : 'mr-refs');
  $('mr-target').setAttribute('list', range ? 'mr-commits' : 'mr-refs');
  if (range) {
    $('mr-base').placeholder = 'abc1234 或 HEAD~3';
    $('mr-target').placeholder = 'def5678 或 HEAD';
  } else {
    $('mr-base').placeholder = '留空 = 目标提交的父提交';
    $('mr-target').placeholder = 'HEAD';
  }

  if (range) {
    // Switching modes starts a new question, so a selection made for the previous range must not
    // carry over: the same sha would then be analysed against a different base.
    state.rangePicked = {};
    loadCommits(Number($('mr-source').value));
    scheduleRangePreview();
  } else {
    if (rangePreviewTimer) { clearTimeout(rangePreviewTimer); rangePreviewTimer = null; }
    // Invalidate any in-flight preview so its answer cannot land in a hidden, reused container.
    rangePreviewSeq++;
    var host = $('mr-range-list');
    var hint = $('mr-range-hint');
    if (host) host.innerHTML = '';
    if (hint) hint.textContent = '两端都填好后，这里会列出范围内的提交。';
  }
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
  var pickedCommits = mode === 'COMMIT_RANGE' ? selectedRangeCommits() : [];
  var body = {
    sourceId: sourceId,
    mode: mode,
    baseRef: mode === 'WORKTREE_DIFF' ? null : ($('mr-base').value.trim() || null),
    targetRef: $('mr-target').value.trim() || null,
    pullRequest: mode === 'PULL_REQUEST' ? Number($('mr-pr').value) || null : null,
    peerSourceIds: selectedPeerIds()
  };
  // Sent only when the user actually ticked something: an empty list would mean "no commits",
  // while the absent field means "analyse the range the two inputs describe".
  if (pickedCommits.length) body.commits = pickedCommits;
  // Sent only when the checkbox is actually usable: an unconfigured server would ignore it anyway,
  // and leaving the field out keeps the request identical to what it was before the feature.
  var aiBox = $('mr-ai');
  if (aiBox && !aiBox.disabled && !$('mr-ai-wrap').classList.contains('hidden')) {
    body.aiReview = !!aiBox.checked;
  }
  if (!body.peerSourceIds.length) {
    showModalError('mr-error', '请至少选择一个要扫描的其他程序');
    return;
  }
  if (mode === 'PULL_REQUEST' && !body.pullRequest) {
    showModalError('mr-error', '请填写 PR / MR 编号');
    return;
  }
  // A range with one end missing would silently fall back to the whole history on the server, so
  // both ends are required and the message names the one that is missing. A hand-picked selection
  // is exempt: it names its commits directly, and requiring two endpoints as well would force the
  // user to retype exactly what they just ticked.
  if (mode === 'COMMIT_RANGE' && !pickedCommits.length) {
    if (!body.baseRef) { showModalError('mr-error', '请填写起始提交（不含）'); return; }
    if (!body.targetRef) { showModalError('mr-error', '请填写结束提交（含）'); return; }
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
    var el = ev.target.closest('[data-nav],[data-impact-id],[data-analysis-id],[data-source-id],[data-module],[data-change-id],[data-confirm-impact],[data-dismiss-impact],[data-open-impact],[data-close],[data-open-run],[data-tab],[data-filter],[data-pick-dir],[data-pk-dir],[data-pk-root],[data-delete-source],[data-background-analysis],[data-update-check],[data-update-notes],[data-update-download],[data-update-apply],[data-update-rollback],[data-update-banner-view],[data-update-banner-skip],[data-update-banner-close],[data-update-history],[data-update-history-retry],[data-update-setting],[data-update-restart-retry],[data-edit-source],[data-tag-add],[data-tag-remove],[data-tag-group-toggle],[data-tag-filter],[data-tag-filter-clear],[data-export],[data-import-file],[data-review-page],[data-ai-save],[data-ai-test],[data-ai-clear-key],[data-ai-preset],[data-ai-generate]');
    if (!el) return;

    // AI actions. Each one owns its own busy state and re-renders only its own region, so a save
    // or a generation cannot leave the rest of the page showing a stale summary.
    if (el.hasAttribute('data-ai-save')) { saveAiSettings(); return; }
    if (el.hasAttribute('data-ai-test')) { testAiConnection(); return; }
    if (el.hasAttribute('data-ai-clear-key')) { clearAiKey(); return; }
    if (el.hasAttribute('data-ai-preset')) { applyAiPreset(el.getAttribute('data-ai-preset')); return; }
    if (el.hasAttribute('data-ai-generate')) { generateAiReview(); return; }

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

    // A switch carries the value its click should write, so the handler never has to read the
    // rendered state back out of the DOM (which would be wrong the moment a render is in flight).
    if (el.hasAttribute('data-update-setting')) {
      var setKey = el.getAttribute('data-update-setting');
      var setNext = el.getAttribute('data-update-setting-next') === 'true';
      saveUpdateSetting(setKey, setNext);
      return;
    }
    // After a poll that timed out, this is how the user asks again without reloading the page.
    if (el.hasAttribute('data-update-restart-retry')) {
      updateState().restartTimeout = false;
      restartPollTicks = 0;
      startRestartPoll();
      renderUpdateBody();
      return;
    }

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
      // 换标签等于换了一份数据集，停在第 7 页只会让人以为列表空了。
      state.reviewPage = 1;
      renderReview();
      return;
    }
    // 分页控件是动态生成的，没有静态 id，靠这个属性识别；只重建列表区域，不动页头和证据面板。
    if (el.hasAttribute('data-review-page')) {
      state.reviewPage = Number(el.getAttribute('data-review-page')) || 1;
      var pagedTab = state.reviewTab;
      renderReviewList(reviewRows(pagedTab, decisionsFor(state.analysisId)), pagedTab);
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
        // 选中只是"高亮一行 + 换右侧证据"，重建整页会让 50 行卡片全部重排，
        // 切一行就卡一下。这里只改 .selected 类，再单独刷证据面板。
        markSelectedReviewRow(state.selectedImpactId);
        renderReviewEvidence();
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
        // 复核状态整体清零后，停在第 9 页没有任何意义。
        state.reviewPage = 1;
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
        // resetSourceModal() clears state.editingSourceId before the toast is built, which made
        // every save report "已保存修改" as "代码源已添加". Remember the mode up front instead.
        var wasEditing = state.editingSourceId != null;
        await saveSourceForm(
          $('m-type').value, $('m-location').value.trim(),
          $('m-ref').value.trim(), $('m-token').value.trim(),
          tags
        );
        resetSourceModal();
        closeModal('modal-source');
        ['m-location', 'm-ref', 'm-token', 'm-name-preview'].forEach(function (f) { if ($(f)) $(f).value = ''; });
        resetRefSelect($('m-ref'), $('m-ref-hint'));
        toast(wasEditing ? '已保存修改' : '代码源已添加', 'success');
      } catch (e) {
        showModalError('m-error', '保存失败：' + e.message);
      } finally {
        mSave.disabled = false;
      }
    });
  }

  var mode = $('mr-mode');
  if (mode) {
    mode.addEventListener('change', function () { applyRunMode(mode.value); });
  }
  var mrSource = $('mr-source');
  if (mrSource) {
    mrSource.addEventListener('change', function () {
      var s = state.sources.filter(function (x) { return String(x.id) === mrSource.value; })[0];
      if (s) {
        $('mr-target').value = s.defaultRef || '';
        loadRefs(s.id);
        // Commit suggestions belong to the repository, so they must be re-read for the new one;
        // the range itself is now meaningless and is cleared rather than previewed.
        if (mode && mode.value === 'COMMIT_RANGE') {
          $('mr-base').value = '';
          loadCommits(s.id);
          scheduleRangePreview();
        }
        // The peer list must not offer the program now being analysed.
        renderPeerPicker(s.id);
      }
    });
  }
  // Both range inputs drive the preview; 'input' also catches a value picked from the datalist on
  // browsers that do not fire 'change' for it.
  ['mr-base', 'mr-target'].forEach(function (id) {
    var el = $(id);
    if (!el) return;
    el.addEventListener('input', function () {
      if (mode && mode.value === 'COMMIT_RANGE') scheduleRangePreview();
    });
    el.addEventListener('change', function () {
      if (mode && mode.value === 'COMMIT_RANGE') scheduleRangePreview();
    });
  });
  // The commit list is rebuilt on every preview, so the tick handler is delegated from the stable
  // container instead of being bound to rows that no longer exist a keystroke later.
  var rangeList = $('mr-range-list');
  if (rangeList) {
    rangeList.addEventListener('change', function (ev) {
      var box = ev.target.closest('input[data-range-commit]');
      if (!box) return;
      var sha = box.getAttribute('data-range-commit');
      if (!sha) return;
      state.rangePicked = state.rangePicked || {};
      if (box.checked) state.rangePicked[sha] = true;
      else delete state.rangePicked[sha];
      var row = box.closest('.commit-row');
      if (row) row.classList.toggle('selected', box.checked);
      // Only the hint changes, so re-rendering the whole list would drop keyboard focus from the
      // checkbox the user just pressed space on.
      renderRangePickHint(lastRangePayload);
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
      var decidedId = pendingDecision.impactId;
      pendingDecision = null;
      closeModal('modal-decision');
      // 只刷受决策影响的部分，不再整页 renderAll（详见 renderAfterDecision 的注释）。
      renderAfterDecision(decidedId);
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
        state.reviewPage = 1;
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
      state.reviewPage = 1;
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
