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
  picker: { target: null, path: null, selected: null }
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

async function loadAnalyses() {
  try { state.analyses = await api('/analyses?limit=30'); } catch (e) { state.analyses = []; }
}

async function openAnalysis(id) {
  state.analysisId = id;
  try {
    state.report = await api('/analyses/' + id + '/report');
  } catch (e) {
    state.report = null;
    toast('读取报告失败：' + e.message, 'error');
  }
  // The report is the authority on which source it belongs to.
  if (state.report) {
    var owner = state.analyses.filter(function (a) { return a.id === id; })[0];
    if (owner) state.sourceId = owner.sourceId;
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
  await Promise.all([loadSources(), loadAnalyses()]);
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

function renderNavSources() {
  var host = $('nav-sources');
  if (!host) return;
  var list = state.sources.slice();
  if (state.search) {
    var q = state.search.toLowerCase();
    list = list.filter(function (s) {
      return (String(s.name) + ' ' + String(s.location)).toLowerCase().indexOf(q) >= 0;
    });
  }
  if (!list.length) {
    host.innerHTML = '<p class="muted" style="font-size:11.5px;padding:4px 8px">没有匹配的代码源</p>';
    return;
  }
  host.innerHTML = list.map(function (s) {
    var tone = s.status === 'FAILED' ? 'danger' : 'brand';
    var active = state.sourceId != null && s.id === state.sourceId;
    return '<button class="nav-item' + (active ? ' active' : '') + '" data-source-id="' + s.id + '">' +
      '<span class="row-icon ' + tone + '" style="width:22px;height:22px;border-radius:6px;margin:0">' + icon('i-git') + '</span>' +
      '<span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">' + esc(s.name) + '</span>' +
      '<span class="count">' + esc(sourceTypeLabel(s.type)) + '</span>' +
      '</button>';
  }).join('');
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
      var tone = done ? (a.summary && a.summary.highRisk > 0 ? 'danger' : 'success') : '';
      return '<div class="tl-item">' +
        '<div class="tl-rail"><div class="tl-dot ' + tone + '"></div>' + (idx < rows.length - 1 ? '<div class="tl-line"></div>' : '') + '</div>' +
        '<div class="tl-body">' +
          '<div class="tl-title">' + esc(a.sourceName || '分析 #' + a.id) +
            pill(STATUS_LABEL[a.status] || a.status, STATUS_TONE[a.status] || 'neutral') + '</div>' +
          '<div class="tl-meta">' + esc(fmtTime(a.createdAt)) + ' · ' + esc(relTime(a.createdAt)) +
            (a.summary ? ' · 影响 ' + (a.summary.impactedSymbols || 0) + ' · 高风险 ' + (a.summary.highRisk || 0) : '') + '</div>' +
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
    return '<div class="tr tr-history" data-analysis-id="' + a.id + '">' +
      '<div class="cell mono" style="color:var(--text-2)">#' + a.id + '</div>' +
      '<div class="cell"><div style="font-weight:550">' + esc(a.sourceName || '') + '</div>' +
        '<div class="sub">' + esc(MODE_LABEL[a.mode] || a.mode || '') + ' · ' + esc(a.targetRef || '') + '</div></div>' +
      '<div class="cell optional">' + pill(STATUS_LABEL[a.status] || a.status, STATUS_TONE[a.status] || 'neutral', true) + '</div>' +
      '<div class="cell optional" style="font-size:11.5px;color:var(--text-2)">对比 ' + (s.comparedPrograms || 0) +
        ' · 契约 ' + (s.matchedContracts || 0) + ' · 影响 ' + (s.impactedSymbols || 0) + '</div>' +
      '<div class="cell optional" style="font-size:11.5px;color:var(--text-2)">' + esc(fmtTime(a.createdAt)) + '</div>' +
      '<div class="cell" style="text-align:right"><span class="row-arrow">' + icon('i-chevron-right') + '</span></div>' +
      '</div>';
  }).join('');
}

/* ------------------------------------------------------------------ sources */

function renderSourcesPage() {
  var host = $('src-recent');
  if (!host) return;
  if (!state.sources.length) {
    host.innerHTML = emptyBlock('还没有代码源', '在上方表单中添加第一个代码源。');
    return;
  }
  host.innerHTML = state.sources.map(function (s) {
    var last = state.analyses.filter(function (a) { return a.sourceId === s.id; })[0];
    var status = last ? last.status : 'PENDING';
    return '<div class="tr tr-history" data-source-id="' + s.id + '">' +
      '<div class="cell mono" style="color:var(--text-2)">' + esc(sourceTypeLabel(s.type)) + '</div>' +
      '<div class="cell"><div style="font-weight:550">' + esc(s.name) + '</div>' +
        '<div class="sub mono">' + esc(s.location) + '</div></div>' +
      '<div class="cell optional">' + pill(STATUS_LABEL[status] || status, STATUS_TONE[status] || 'neutral', true) + '</div>' +
      '<div class="cell optional" style="font-size:11.5px;color:var(--text-2)">' +
        (last ? '对比 ' + ((last.summary && last.summary.comparedPrograms) || 0) +
          ' · 影响 ' + ((last.summary && last.summary.impactedSymbols) || 0) : '尚未分析') + '</div>' +
      '<div class="cell optional" style="font-size:11.5px;color:var(--text-2)">' + esc(relTime(s.lastSyncedAt)) + '</div>' +
      '<div class="cell actions">' +
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
  renderAnalysisProgress();
  setText('an-crumb-source', report ? report.sourceName : (pickedSource ? pickedSource.name : '—'));
  setText('an-title', report ? '分析 #' + state.analysisId + ' · ' + (MODE_LABEL[report.mode] || report.mode) : '分析详情');

  // One page-level empty state beats five identical per-card ones.
  var emptyHost = $('an-empty');
  var body = $('an-body');
  if (emptyHost) emptyHost.classList.toggle('hidden', !!report);
  if (body) body.classList.toggle('hidden', !report);
  if (!report && emptyHost) {
    var emptySub = $('an-empty-sub');
    if (emptySub) {
      emptySub.textContent = pickedSource
        ? '为 ' + pickedSource.name + ' 运行一次扫描后，这里会展示变更清单、其他程序影响与风险构成。'
        : '添加代码源并运行一次扫描后，这里会展示变更清单、其他程序影响与风险构成。';
    }
  }
  var anState = $('an-state');
  if (anState) {
    anState.textContent = report ? '已完成' : (state.liveAnalysis ? '分析中' : '暂无数据');
  }
  setHtml('an-sub', report
    ? esc(report.baseLabel || '') + ' → ' + esc(report.headLabel || '') + ' <span class="sep">·</span> ' + esc(fmtTime(report.generatedAt))
    : '—');
  setText('an-files', report ? fmtNum(s.parsedFiles) : '—');
  setText('an-duration', report ? fmtDuration(s.indexMillis) : '—');

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
  setHtml('an-blindspots', blind.length ? blind.slice(0, 30).map(function (b) {
    return '<div class="ev-row" style="margin-bottom:6px"><div class="ev-type">未解析调用</div>' +
      '<div class="ev-main">' + esc(b.module) + ' · ' + esc(shortFile(b.file)) + ':' + b.line + '</div>' +
      '<div class="ev-meta">' + esc(b.expression) + ' — ' + esc(b.reason) + '</div></div>';
  }).join('') : (report
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
 * Live analysis progress. The server persists the real stage and percentage, so this panel remains
 * correct after a reload and never advances on a client-side timer.
 */
function renderAnalysisProgress() {
  var host = $('an-progress-card');
  if (!host) return;
  var live = state.liveAnalysis;
  var active = live && (live.status === 'RUNNING' || live.status === 'PENDING');
  var failed = live && live.status === 'FAILED';
  host.classList.toggle('hidden', !(active || failed));
  if (!(active || failed)) return;

  var percent = Math.max(0, Math.min(100, Number(live.progressPercent) || 0));
  var stage = live.progressStage || 'PENDING';
  var label = live.progressLabel || (live.status === 'PENDING' ? '排队中' : '分析中');
  var detail = live.progressDetail || '';
  var completed = live.progressCompleted || [];
  var skipped = live.progressSkipped || [];

  var pillEl = $('an-progress-pill');
  if (pillEl) pillEl.textContent = failed ? '失败' : percent + '% · ' + label;
  var detailEl = $('an-progress-detail');
  if (detailEl) detailEl.textContent = detail || '正在处理…';
  var bar = $('an-progress-bar');
  if (bar) {
    bar.style.width = (failed ? 0 : percent) + '%';
    bar.className = failed ? 'danger' : '';
  }

  var stepsHost = $('an-progress-steps');
  if (stepsHost) {
    stepsHost.innerHTML = PROGRESS_STEPS.map(function (step, idx) {
      var isDone = completed.indexOf(step.id) >= 0;
      var isSkipped = skipped.indexOf(step.id) >= 0;
      var isActive = !isDone && !isSkipped && step.id === stage;
      var tone = failed && step.id === stage ? 'danger' : (isDone ? 'success' : (isSkipped ? 'neutral' : (isActive ? 'brand' : '')));
      return '<div class="step ' + tone + '">' +
        '<div class="step-dot">' + (isDone ? icon('i-check') : (isSkipped ? '—' : (idx + 1))) + '</div>' +
        '<div class="step-body"><div class="step-title">' + esc(step.title) + '</div>' +
          '<div class="step-meta">' + esc(isSkipped ? '已跳过' : (isActive ? label : step.meta)) + '</div></div>' +
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
  var err = $('m-error');
  if (err) err.style.display = 'none';
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
    state.picker.selected = data.path;
    $('pk-path').value = data.path;
    $('pk-selected').textContent = '已选：' + data.path;
    $('pk-up').disabled = !data.parent;
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

async function saveSourceForm(type, location, ref, token) {
  var body = {
    type: type, location: location,
    defaultRef: ref || null, credentialEnv: token || null
  };
  if (!body.location) throw new Error('位置必填');
  var created = await api('/sources', { method: 'POST', body: JSON.stringify(body) });
  await loadSources();
  renderAll();
  return created;
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
    var el = ev.target.closest('[data-nav],[data-impact-id],[data-analysis-id],[data-source-id],[data-module],[data-change-id],[data-confirm-impact],[data-dismiss-impact],[data-open-impact],[data-close],[data-open-run],[data-tab],[data-filter],[data-pick-dir],[data-pk-dir],[data-pk-root],[data-delete-source],[data-background-analysis]');
    if (!el) return;

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

    if (el.hasAttribute('data-close')) { closeModal(el.getAttribute('data-close')); return; }
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
    if (el.hasAttribute('data-analysis-id')) { openAnalysis(Number(el.getAttribute('data-analysis-id'))); return; }
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

  document.querySelectorAll('.overlay').forEach(function (overlay) {
    overlay.addEventListener('click', function (ev) {
      if (ev.target === overlay) overlay.classList.add('hidden');
    });
  });

  document.addEventListener('keydown', function (ev) {
    if (ev.key === 'Escape') {
      document.querySelectorAll('.overlay:not(.hidden)').forEach(function (o) { o.classList.add('hidden'); });
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
      var path = state.picker.path;
      if (!path) return;
      var parent = path.replace(/[\/][^\/]+$/, '');
      loadDir(parent || path);
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
        await saveSourceForm($('src-type').value, location, $('src-ref').value.trim(), $('src-token').value.trim());
        state.importStep = 4;
        renderImportStepper();
        ['src-location', 'src-ref', 'src-token', 'src-name-preview'].forEach(function (f) { if ($(f)) $(f).value = ''; });
        resetRefSelect($('src-ref'), $('src-ref-hint'));
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
        await saveSourceForm(
          $('m-type').value, $('m-location').value.trim(),
          $('m-ref').value.trim(), $('m-token').value.trim()
        );
        closeModal('modal-source');
        ['m-location', 'm-ref', 'm-token', 'm-name-preview'].forEach(function (f) { if ($(f)) $(f).value = ''; });
        resetRefSelect($('m-ref'), $('m-ref-hint'));
        toast('代码源已添加', 'success');
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
  bindEvents();
  await refreshAll(false);
  var remembered = null;
  try { remembered = localStorage.getItem(PAGE_KEY); } catch (e) { /* ignore */ }
  go(remembered && remembered !== 'impact' ? remembered : 'overview');
  renderAll();
})();
