/* ==========================================================================
   神言 V7 · 离线可交互设计稿（docs/design/v7/mobile）
   - 唯一设计依据：docs/design/v7/V7-VISUAL-LANGUAGE.zh-CN.md
   - 唯一令牌来源：docs/design/v7/tokens.json
   - 本稿离线运行：不请求网络、不连接真实数据、不执行模型 / 插件 / 文件操作。
     所有对话、人物、任务、文件、用量均为模拟演示数据。
   ========================================================================== */
'use strict';

/* 与 docs/design/v7/tokens.json 内容一致的快照。
   直接双击 index.html 打开时使用它；app.html 由 build-standalone.py 注入 tokens.json。
   两者若发生漂移，`python3 docs/design/v7/build-standalone.py --check` 会报错。 */
const V7_TOKEN_SNAPSHOT = {"version": "v7", "status": "design-target", "note": "V7 视觉令牌目标值。三层结构：primitives（原始值）→ semantic（角色别名）→ components（组件令牌）。themes 段保留解析后的十六进制值，供离线设计稿与 CI 对比度校验直接消费；CI 必须校验 themes 与 primitives 一致。", "layers": ["primitives", "semantic", "components"], "primitives": {"color": {"light": {"paper-0": "#FFFFFF", "paper-50": "#FFFDF8", "paper-100": "#FCF9F3", "paper-200": "#FAF6EE", "paper-300": "#F8F4EC", "paper-400": "#F2EDE4", "paper-600": "#E5DCCB", "paper-700": "#D0C4AE", "ink-900": "#2A2621", "ink-700": "#575047", "ink-500": "#6A6154", "accent-600": "#9C4E1B", "accent-700": "#8A4416", "accent-100": "#F3E3D5", "on-accent": "#FFF7EE", "success-700": "#2A7059", "persona-700": "#6E56A6", "warning-700": "#8A5706", "danger-700": "#A83224"}, "dark": {"paper-0": "#302D2B", "paper-100": "#2B2927", "paper-200": "#262423", "paper-300": "#232120", "paper-400": "#1B1917", "paper-600": "#322F2C", "paper-700": "#423E3A", "ink-100": "#F4EFE7", "ink-200": "#C6BCAF", "ink-300": "#A79C8E", "accent-300": "#E08A4E", "accent-400": "#C97A42", "accent-700": "#3A2A1D", "on-accent": "#241608", "success-300": "#6FBFA2", "persona-300": "#B6A0E0", "warning-300": "#E0A458", "danger-300": "#E08578"}, "matte": {"paper-0": "#26262A", "paper-100": "#212124", "paper-200": "#1B1B1D", "paper-300": "#161618", "paper-400": "#0D0D0E", "paper-600": "#232326", "paper-700": "#33333A", "ink-100": "#F4F4F2", "ink-200": "#C2C2BE", "ink-300": "#96968F", "accent-300": "#D9884A", "accent-400": "#C77A3F", "accent-700": "#33231A", "on-accent": "#1A1005", "success-300": "#6FBFA2", "persona-300": "#B6A0E0", "warning-300": "#E0A458", "danger-300": "#E08578"}, "poles": {"light": "#FFFFFF", "dark": "#1A1613"}}, "accentCards": [{"id": "vermilion", "name": "朱砂", "light": "#A3401F", "dark": "#F0A48A"}, {"id": "amber", "name": "琥珀", "light": "#8A5706", "dark": "#E0A458"}, {"id": "moss", "name": "苔绿", "light": "#2F6B4F", "dark": "#6FBFA2"}, {"id": "teal", "name": "松石", "light": "#1F6070", "dark": "#6FC0CE"}, {"id": "indigo", "name": "靛蓝", "light": "#2C4E8F", "dark": "#8AA8E0"}, {"id": "violet", "name": "紫棠", "light": "#5E4493", "dark": "#B6A0E0"}, {"id": "rose", "name": "玫紫", "light": "#9B3A6B", "dark": "#E294B8"}, {"id": "graphite", "name": "石墨", "light": "#4A4740", "dark": "#B8B4AC"}], "space": {"1": 4, "2": 8, "3": 12, "4": 16, "6": 24, "8": 32}, "radius": {"sm": 8, "control": 12, "card": 16, "sheet": 20, "overlay": 16}, "fontSize": {"display": 28, "title": 22, "heading": 18, "bodyStrong": 16, "body": 16, "bodyQuiet": 15, "caption": 13, "mono": 14}, "fontWeight": {"regular": 400, "medium": 500, "semibold": 600, "bold": 700}, "lineHeight": {"display": 1.25, "title": 1.3, "heading": 1.4, "bodyStrong": 1.55, "body": 1.65, "bodyQuiet": 1.6, "caption": 1.45, "mono": 1.55}, "blur": {"overlay": 20}, "shadow": {"menu": 6, "sheet": 10, "dialog": 14}, "duration": {"press": 60, "select": 140, "push": 240, "pop": 200, "sheet": 240, "dialog": 200, "sidebar": 160, "fade": 110, "progress": 260}, "touchTarget": 48, "iconRange": [16, 24], "iconSizes": {"sm": 16, "md": 20, "lg": 24}}, "semantic": {"color": {"light": {"canvas": "paper-400", "surface": "paper-200", "bar": "paper-300", "overlay": "paper-100", "solid": "paper-50", "ink": "ink-900", "ink2": "ink-700", "ink3": "ink-500", "rule": "paper-600", "ruleStrong": "paper-700", "accent": "accent-600", "accentPressed": "accent-700", "onAccent": "on-accent", "accentSoft": "accent-100", "success": "success-700", "persona": "persona-700", "warning": "warning-700", "danger": "danger-700"}, "dark": {"canvas": "paper-400", "surface": "paper-300", "bar": "paper-200", "overlay": "paper-100", "solid": "paper-0", "ink": "ink-100", "ink2": "ink-200", "ink3": "ink-300", "rule": "paper-600", "ruleStrong": "paper-700", "accent": "accent-300", "accentPressed": "accent-400", "onAccent": "on-accent", "accentSoft": "accent-700", "success": "success-300", "persona": "persona-300", "warning": "warning-300", "danger": "danger-300"}, "matte": {"canvas": "paper-400", "surface": "paper-300", "bar": "paper-200", "overlay": "paper-100", "solid": "paper-0", "ink": "ink-100", "ink2": "ink-200", "ink3": "ink-300", "rule": "paper-600", "ruleStrong": "paper-700", "accent": "accent-300", "accentPressed": "accent-400", "onAccent": "on-accent", "accentSoft": "accent-700", "success": "success-300", "persona": "persona-300", "warning": "warning-300", "danger": "danger-300"}}, "spacing": {"xs": "space.1", "sm": "space.2", "md": "space.3", "lg": "space.4", "section": "space.6", "page": "space.8"}, "typography": {"display": "fontSize.display / lineHeight.display / fontWeight.bold", "title": "fontSize.title / lineHeight.title / fontWeight.semibold", "heading": "fontSize.heading / lineHeight.heading / fontWeight.semibold", "bodyStrong": "fontSize.bodyStrong / lineHeight.bodyStrong / fontWeight.semibold", "body": "fontSize.body / lineHeight.body / fontWeight.regular", "bodyQuiet": "fontSize.bodyQuiet / lineHeight.bodyQuiet / fontWeight.regular", "caption": "fontSize.caption / lineHeight.caption / fontWeight.regular", "mono": "fontSize.mono / lineHeight.mono / fontWeight.regular"}, "surface": {"L1_SECTION": {"fill": "canvas", "opacity": 1, "blur": 0, "shadow": 0, "border": "rule"}, "L2_BAR": {"fill": "bar", "opacity": 1, "blur": 0, "shadow": 0, "border": "rule"}, "L3_CARD": {"fill": "surface", "opacity": 1, "blur": 0, "shadow": 0, "border": "rule"}, "L4_OVERLAY": {"fill": "overlay", "opacity": 0.92, "blur": "blur.overlay", "border": "ruleStrong"}, "L5_SOLID": {"fill": "solid", "opacity": 1, "blur": 0, "shadow": 0, "border": "rule"}}, "motion": {"easing": "cubicBezier(0.22, 1, 0.36, 1)", "press": "duration.press", "select": "duration.select", "push": "duration.push", "pop": "duration.pop", "sheet": "duration.sheet", "dialog": "duration.dialog", "sidebar": "duration.sidebar", "fade": "duration.fade", "progress": "duration.progress", "pressedScale": 0.99}}, "components": {"button": {"primary": {"bg": "accent", "bgPressed": "accentPressed", "fg": "onAccent", "border": "none", "radius": "radius.control", "height": 48}, "secondary": {"bg": "none", "bgPressed": "canvas", "fg": "ink", "border": "ruleStrong", "radius": "radius.control", "height": 48}, "quiet": {"bg": "none", "bgPressed": "none", "fg": "accent", "border": "none", "radius": "radius.control", "height": 48}, "danger": {"bg": "none", "bgPressed": "none", "fg": "danger", "border": "none", "radius": "radius.control", "height": 48}, "disabled": {"bg": "none", "fg": "ink3", "border": "none"}}, "listRow": {"minHeight": 56, "paddingH": "space.4", "paddingV": "space.2", "divider": "rule", "title": "bodyStrong", "desc": "bodyQuiet", "meta": "caption", "icon": 24, "radius": "radius.sm"}, "bubble": {"user": {"bg": "accentSoft", "fg": "ink", "radius": "radius.card", "maxWidthRatio": 0.78}, "assistant": {"bg": "none", "fg": "ink", "radius": 0}}, "composer": {"bg": "bar", "border": "rule", "radius": "radius.card", "padding": "space.2", "text": "body", "actionMin": 48}, "menu": {"bg": "overlay", "radius": "radius.overlay", "shadow": "shadow.menu", "paddingH": "space.4", "paddingV": "space.3", "itemMinHeight": 48}, "sheet": {"bg": "overlay", "radius": "radius.sheet", "shadow": "shadow.sheet", "paddingH": "space.6", "paddingV": "space.4", "itemMinHeight": 48}, "dialog": {"bg": "overlay", "radius": "radius.overlay", "shadow": "shadow.dialog", "paddingH": "space.6", "paddingV": "space.6"}, "workTimeline": {"railWidth": 2, "railColor": "rule", "currentDot": "accent", "doneInk": "ink3", "currentTitle": "bodyStrong", "desc": "bodyQuiet"}, "fileCard": {"bg": "surface", "border": "rule", "radius": "radius.card", "icon": 24, "title": "bodyStrong", "meta": "caption"}, "statusPill": {"bg": "canvas", "fg": "ink2", "radius": "radius.sm", "text": "caption"}}, "themes": {"light": {"canvas": "#F2EDE4", "surface": "#FAF6EE", "bar": "#F8F4EC", "overlay": "#FCF9F3", "solid": "#FFFDF8", "ink": "#2A2621", "ink2": "#575047", "ink3": "#6A6154", "rule": "#E5DCCB", "ruleStrong": "#D0C4AE", "accent": "#9C4E1B", "accentPressed": "#8A4416", "onAccent": "#FFF7EE", "accentSoft": "#F3E3D5", "success": "#2A7059", "persona": "#6E56A6", "warning": "#8A5706", "danger": "#A83224"}, "dark": {"canvas": "#1B1917", "surface": "#232120", "bar": "#262423", "overlay": "#2B2927", "solid": "#302D2B", "ink": "#F4EFE7", "ink2": "#C6BCAF", "ink3": "#A79C8E", "rule": "#322F2C", "ruleStrong": "#423E3A", "accent": "#E08A4E", "accentPressed": "#C97A42", "onAccent": "#241608", "accentSoft": "#3A2A1D", "success": "#6FBFA2", "persona": "#B6A0E0", "warning": "#E0A458", "danger": "#E08578"}, "matte": {"canvas": "#0D0D0E", "surface": "#161618", "bar": "#1B1B1D", "overlay": "#212124", "solid": "#26262A", "ink": "#F4F4F2", "ink2": "#C2C2BE", "ink3": "#96968F", "rule": "#232326", "ruleStrong": "#33333A", "accent": "#D9884A", "accentPressed": "#C77A3F", "onAccent": "#1A1005", "accentSoft": "#33231A", "success": "#6FBFA2", "persona": "#B6A0E0", "warning": "#E0A458", "danger": "#E08578"}}, "type": {"display": {"size": 28, "lineHeight": 1.25, "weight": 700, "lineHeightSp": 36}, "title": {"size": 22, "lineHeight": 1.3, "weight": 600, "lineHeightSp": 29}, "heading": {"size": 18, "lineHeight": 1.4, "weight": 600, "lineHeightSp": 25}, "bodyStrong": {"size": 16, "lineHeight": 1.55, "weight": 600, "lineHeightSp": 25}, "body": {"size": 16, "lineHeight": 1.65, "weight": 400, "lineHeightSp": 26}, "bodyQuiet": {"size": 15, "lineHeight": 1.6, "weight": 400, "lineHeightSp": 24}, "caption": {"size": 13, "lineHeight": 1.45, "weight": 400, "lineHeightSp": 19}, "mono": {"size": 14, "lineHeight": 1.55, "weight": 400, "lineHeightSp": 22}}, "geometry": {"radiusSm": 8, "radiusControl": 12, "radiusCard": 16, "radiusSheet": 20, "radiusOverlay": 16, "gapXs": 4, "gapSm": 8, "gapMd": 12, "gapLg": 16, "gapSection": 24, "gapPage": 32, "touch": 48, "iconMin": 20, "iconMax": 24, "iconSm": 16, "iconMd": 20, "iconLg": 24}, "surfaces": {"L1_SECTION": {"fill": "canvas", "opacity": 1, "blur": 0, "shadow": 0, "border": "rule"}, "L2_BAR": {"fill": "bar", "opacity": 1, "blur": 0, "shadow": 0, "border": "rule"}, "L3_CARD": {"fill": "surface", "opacity": 1, "blur": 0, "shadow": 0, "border": "rule"}, "L4_OVERLAY": {"fill": "overlay", "opacity": 0.92, "blur": 20, "shadow": 6, "border": "ruleStrong"}, "L5_SOLID": {"fill": "solid", "opacity": 1, "blur": 0, "shadow": 0, "border": "rule"}}, "overlayVariants": {"menu": {"radius": 16, "shadow": 6, "paddingH": 16, "paddingV": 12}, "sheet": {"radius": 20, "shadow": 10, "paddingH": 20, "paddingV": 16}, "dialog": {"radius": 16, "shadow": 14, "paddingH": 24, "paddingV": 20}}, "motion": {"easing": "cubicBezier(0.22, 1, 0.36, 1)", "pressMs": 60, "selectMs": 140, "pushMs": 240, "popMs": 200, "sheetMs": 240, "dialogMs": 200, "sidebarMs": 160, "fadeMs": 110, "progressMs": 260, "pressedScale": 0.99}};

const TOKENS = window.V7_TOKENS || V7_TOKEN_SNAPSHOT;
const POLES = TOKENS.primitives.color.poles;
const POLE_LIGHT = POLES.light;
const POLE_DARK = POLES.dark;

/* ---------------------------------------------------------------- 资源 --- */
const ASSET_BASE = '../../v6/assets/';
const A = p => 'icons-actions/' + p + '.svg';
const M = p => 'icons-misc/' + p + '.svg';
const L = p => 'icons-legacy-redrawn/' + p + '.svg';
const S = p => 'icons-supplemental/' + p + '.svg';
const I = p => 'icons/' + p + '.svg';

const ICONS = {
  Menu: A('Menu'), Search: A('Search'), Plus: A('Plus'), MoreVertical: A('MoreVertical'),
  MessageCircle: A('MessageCircle'),
  ChevronRight: A('ChevronRight'), ChevronDown: A('ChevronDown'), ChevronLeft: A('ChevronLeft'),
  Check: A('Check'), CheckCircle: A('CheckCircle'), X: A('X'), Copy: A('Copy'),
  RefreshCw: A('RefreshCw'), GitBranch: A('GitBranch'), Clock: A('Clock'), Edit3: A('Edit3'),
  Trash2: A('Trash2'), Folder: A('Folder'), FileText: A('FileText'), Download: A('Download'),
  Share: A('Share'), Palette: A('Palette'), SunMoon: A('SunMoon'), Sliders: A('Sliders'),
  Sparkles: A('Sparkles'), Zap: A('Zap'), Tool: A('Tool'), Users: A('Users'), User: A('User'),
  Bell: A('Bell'), Shield: A('Shield'), Info: A('Info'), Terminal: A('Terminal'),
  BookOpen: A('BookOpen'), Eye: A('Eye'), Pin: A('Pin'), Paperclip: A('Paperclip'),
  List: A('List'), Globe: A('Globe'), Gauge: A('Gauge'), Code: A('Code'), Image: A('Image'),
  AlertTriangle: A('AlertTriangle'), Archive: A('Archive'), Camera: A('Camera'),
  Account: I('Account'), Advanced: I('Advanced'), Appearance: I('Appearance'),
  ChatStyle: I('ChatStyle'), Diary: I('Diary'), Gallery: I('Gallery'), GroupChat: I('GroupChat'),
  Memory: I('Memory'), Models: I('Models'), NewSession: I('NewSession'), Notifications: I('Notifications'),
  Performance: I('Performance'), Permissions: I('Permissions'), Persona: I('Persona'), Quota: I('Quota'),
  Reader: I('Reader'), SessionStorage: I('SessionStorage'), Settings: I('Settings'), Tasks: I('Tasks'),
  Tools: I('Tools'), Updates: I('Updates'), Usage: I('Usage'), WorkspaceFiles: I('WorkspaceFiles'),
  Skills: S('Skills'), Plugins: S('Plugins'), Connections: S('Connections'),
  Add: M('ic_ui_add'), Send: M('ic_ui_button_send_glyph'), Stop: M('ic_ui_button_stop_glyph'),
  CheckMark: M('ic_ui_check'), ModelGlyph: M('ic_ui_model'), PluginGlyph: M('ic_ui_plugin'),
  TaskEmpty: M('ic_ui_task_empty'), Subagent: M('ic_ui_task_subagent'),
  Approval: L('Approval'), Attachment: L('Attachment'), Automation: L('Automation'),
  FilePreview: L('FilePreview'), Plan: L('Plan'), ShareLegacy: L('Share'), Team: L('Team')
};

const PORTRAITS = [1, 2, 3, 4].map(n => 'portraits/persona-' + n + '.svg');

function assetUrl(rel) {
  const inline = window.V7_ASSETS && window.V7_ASSETS[rel];
  return inline || (ASSET_BASE + rel);
}
function ico(name, extra) {
  const rel = ICONS[name];
  if (!rel) return '';
  return '<span class="ico ' + (extra || '') + '" style="--m:url(\'' + assetUrl(rel) + '\')" aria-hidden="true"></span>';
}
function portraitUrl(i) {
  return assetUrl(PORTRAITS[Math.min(i, PORTRAITS.length - 1)]);
}
function fileIconUrl(name, theme) {
  const variant = theme === 'light' ? 'light' : 'dark';
  return assetUrl('file-icons/' + name + '_' + variant + '.svg');
}

/* ------------------------------------------------------------ 主题令牌 --- */
function hexToRgb(hex) {
  const h = hex.replace('#', '');
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)];
}
function rgba(hex, alpha) {
  const c = hexToRgb(hex);
  return 'rgba(' + c[0] + ',' + c[1] + ',' + c[2] + ',' + alpha + ')';
}
function mix(a, b, ratio) {
  const x = hexToRgb(a), y = hexToRgb(b);
  const m = i => Math.round(x[i] + (y[i] - x[i]) * ratio);
  return 'rgb(' + m(0) + ',' + m(1) + ',' + m(2) + ')';
}
function applyTheme(name) {
  const t = TOKENS.themes[name];
  const root = document.documentElement;
  Object.keys(t).forEach(role => root.style.setProperty('--' + role, t[role]));
  const s = TOKENS.surfaces, ov = TOKENS.overlayVariants, g = TOKENS.geometry, m = TOKENS.motion;
  root.style.setProperty('--overlay-bg', state.reduceTransparency ? t.solid : rgba(t.overlay, s.L4_OVERLAY.opacity));
  root.style.setProperty('--l4-blur', state.reduceTransparency ? '0px' : s.L4_OVERLAY.blur + 'px');
  root.style.setProperty('--radius-menu', ov.menu.radius + 'px');
  root.style.setProperty('--radius-sheet', ov.sheet.radius + 'px');
  root.style.setProperty('--radius-dialog', ov.dialog.radius + 'px');
  root.style.setProperty('--shadow-menu', '0 4px ' + ov.menu.shadow + 'px rgba(0,0,0,.22)');
  root.style.setProperty('--shadow-sheet', '0 -10px ' + ov.sheet.shadow + 'px rgba(0,0,0,.26)');
  root.style.setProperty('--shadow-dialog', '0 14px ' + ov.dialog.shadow + 'px rgba(0,0,0,.30)');
  Object.keys(g).forEach(k => root.style.setProperty('--' + k, g[k] + 'px'));
  Object.keys(TOKENS.type).forEach(k => {
    const t2 = TOKENS.type[k];
    root.style.setProperty('--type-' + k + '-size', t2.size + 'px');
    root.style.setProperty('--type-' + k + '-lh', String(t2.lineHeight));
    root.style.setProperty('--type-' + k + '-weight', String(t2.weight));
  });
  root.style.setProperty('--dur-press', m.pressMs + 'ms');
  root.style.setProperty('--dur-select', m.selectMs + 'ms');
  root.style.setProperty('--dur-push', m.pushMs + 'ms');
  root.style.setProperty('--dur-pop', m.popMs + 'ms');
  root.style.setProperty('--dur-sheet', m.sheetMs + 'ms');
  root.style.setProperty('--dur-dialog', m.dialogMs + 'ms');
  root.style.setProperty('--dur-sidebar', m.sidebarMs + 'ms');
  root.style.setProperty('--dur-fade', m.fadeMs + 'ms');
  root.style.setProperty('--dur-progress', m.progressMs + 'ms');
  root.style.setProperty('--ease', m.easing);
  root.style.setProperty('--pressedScale', String(m.pressedScale));
  const dark = TOKENS.themes.dark;
  root.style.setProperty('--stage', mix(dark.canvas, dark.ink, 0.12));
  root.style.setProperty('--stage-line', mix(dark.canvas, dark.ink, 0.22));
  root.dataset.theme = name;
}

/* --------------------------------------------------------------- 状态 --- */
const Q = new URLSearchParams(location.search);
const THEMES = [['light', '暖纸'], ['dark', '夜墨'], ['matte', '哑光']];
const ROUTES = ['HOME', 'PERSONA_GALLERY', 'DIARY', 'WORKSPACE', 'TASKS', 'TOOLS', 'SETTINGS'];
const ROUTE_LABEL = {
  HOME: '聊天', PERSONA_GALLERY: '人物图集', DIARY: '人物日记', WORKSPACE: '工作区',
  TASKS: '自动化任务', TOOLS: '工具与技能', SETTINGS: '设置'
};
const state = {
  route: ROUTES.indexOf(Q.get('route')) >= 0 ? Q.get('route') : 'HOME',
  mode: Q.get('mode') === 'WORK' ? 'WORK' : 'CHAT',
  theme: THEMES.some(t => t[0] === Q.get('theme')) ? Q.get('theme') : 'light',
  settings: 'ROOT',
  settingsStack: ['ROOT'],
  drawer: Q.get('drawer') === '1',
  sheet: Q.get('sheet') || '',
  dialog: '',
  menuOpen: false,
  toolTab: 'all',
  search: '',
  drawerSearch: '',
  selectedPerson: 0,
  running: true,
  reduceTransparency: false,
  draft: '',
  sent: []
};

const $ = id => document.getElementById(id);
const safe = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

/* --------------------------------------------------------- 组件语法 --- */
function btn(label, action, kind, extra) {
  return '<button class="btn btn-' + kind + ' ' + (extra || '') + '" data-action="' + action + '">' + label + '</button>';
}
function group(title, rows, note) {
  return '<div class="group-title">' + title + '</div>' +
    (note ? '<p class="group-note">' + note + '</p>' : '') +
    '<div class="rule-list">' + rows + '</div>';
}
function row(o) {
  const lead = o.lead ? '<span class="row-lead">' + o.lead + '</span>' : '';
  const desc = o.desc ? '<div class="row-desc">' + o.desc + '</div>' : '';
  const meta = o.meta ? '<div class="row-meta">' + o.meta + '</div>' : '';
  const trail = o.trail ? '<span class="row-trail">' + o.trail + '</span>' : '';
  const tag = o.action ? ' data-action="' + o.action + '"' : '';
  const cls = 'row' + (o.cls ? ' ' + o.cls : '');
  const inner = lead + '<span class="row-main"><span class="row-title">' + o.title + '</span>' + desc + meta + '</span>' + trail;
  return o.action
    ? '<button class="' + cls + '"' + tag + '>' + inner + '</button>'
    : '<div class="' + cls + '">' + inner + '</div>';
}
function switchRow(title, desc, checked, action) {
  return '<div class="row"><span class="row-main"><span class="row-title">' + title + '</span>' +
    (desc ? '<div class="row-desc">' + desc + '</div>' : '') + '</span>' +
    '<span class="row-actions"><button class="switch" role="switch" aria-checked="' + (checked ? 'true' : 'false') +
    '" data-action="' + action + '" aria-label="' + title + '"></button></span></div>';
}
function fileCard(name, meta, iconName, actions) {
  return '<div class="filecard"><img class="file-icon" src="' + fileIconUrl(iconName, state.theme) + '" alt="文件图标">' +
    '<span class="filecard-main"><span class="filecard-name">' + name + '</span>' +
    '<span class="filecard-meta">' + meta + '</span></span>' +
    '<span class="filecard-actions">' + actions + '</span></div>';
}
function emptyState(iconName, title, desc, action) {
  return '<div class="empty">' + ico(iconName, 'lg') + '<h3>' + title + '</h3><p>' + desc + '</p>' +
    (action ? '<div style="margin-top:var(--gapMd)">' + action + '</div>' : '') + '</div>';
}

/* =========================================================== HOME 聊天 === */
function renderChat() {
  let thread = '<div class="thread">';
  thread += '<div class="thread-date">今天 · 09:41</div>';
  thread += '<div class="msg msg-user"><div class="bubble-user">帮我把界面的视觉和交互整理成真正统一的一套，别再各写一份。</div>' +
    '<div class="msg-meta">09:41 · 模拟数据</div></div>';
  thread += '<div class="msg msg-bot"><div class="reply">' +
    '<p>可以。先把「层级」和「强调」拆开：层级交给排版与留白，强调全局只留一种颜色。</p>' +
    '<p>落到具体动作上，是三条：</p>' +
    '<ul><li>栏位与卡片改成不透明纸面，取消实时背景模糊；</li>' +
    '<li>每处交互最多两种强调手段，行内动作不再一律加底板；</li>' +
    '<li>正文回到 16sp / 1.65 的阅读节奏，代码用等宽 14sp。</li></ul>' +
    '<blockquote>规范原话：层级优先由排版、留白与分隔线表达，透明材质只留给真正浮在内容之上的东西。</blockquote>' +
    '<pre class="code"><code>btn--primary    实心 accent\nbtn--secondary  1px ruleStrong 描边\nbtn--quiet      纯文字 accent</code></pre>' +
    '</div><div class="msg-meta">09:41 · 神言 V7 预览 · 1.2s · 模拟回复</div>' +
    '<div class="msg-actions">' +
    '<button class="act-text" data-action="toast:已复制这条回复">'+ ico('Copy', 'sm') + '复制</button>' +
    '<button class="act-text" data-action="toast:已重新生成（模拟）">' + ico('RefreshCw', 'sm') + '重试</button>' +
    '<button class="act-text" data-action="toast:已创建分支会话（模拟）">' + ico('GitBranch', 'sm') + '分支</button>' +
    '<button class="act-icon" data-action="menu:reply" aria-label="更多操作">' + ico('MoreVertical', 'sm') + '</button>' +
    '</div></div>';
  thread += '<div class="msg msg-user"><div class="bubble-user">那人物、工作区和设置页呢？</div>' +
    '<div class="msg-meta">09:42 · 模拟数据</div></div>';
  thread += '<div class="msg msg-bot"><div class="reply">' +
    '<p>各自有身份，但共用同一套令牌：人物选中用描边加轻染，工作区成果用文件样式，设置页用分组标题加行列表，取消卡片套卡片。</p>' +
    '</div><div class="msg-meta">09:42 · 神言 V7 预览 · 0.8s · 模拟回复</div>' +
    '<div class="msg-actions">' +
    '<button class="act-text" data-action="toast:已复制这条回复">' + ico('Copy', 'sm') + '复制</button>' +
    '<button class="act-icon" data-action="menu:reply" aria-label="更多操作">' + ico('MoreVertical', 'sm') + '</button>' +
    '</div></div>';
  state.sent.forEach(m => {
    thread += '<div class="msg msg-user"><div class="bubble-user">' + safe(m) + '</div>' +
      '<div class="msg-meta">刚刚 · 模拟数据</div></div>';
  });
  thread += '</div>';
  return thread;
}

/* =========================================================== HOME 工作 === */
function renderWork() {
  let thread = '<div class="thread">';
  thread += '<div class="thread-date">工作会话 · 示例状态（模拟）</div>';
  thread += '<div class="msg msg-user"><div class="bubble-user">核对整个 V7 界面设计，检查残留问题。</div>' +
    '<div class="msg-meta">09:41 · 模拟数据</div></div>';
  thread += '<div class="rail">';
  thread += '<div class="rail-item" data-state="done"><div class="rail-track"><span class="rail-dot"></span></div>' +
    '<div class="rail-body"><div class="rail-title">已理解任务</div>' +
    '<div class="rail-sub">沿现有实现排查，不把旧入口带回来。</div></div></div>';
  thread += '<div class="rail-item" data-state="done"><div class="rail-track"><span class="rail-dot"></span></div>' +
    '<div class="rail-body"><div class="rail-title">已完成 · 梳理七条主路由</div>' +
    '<div class="rail-sub">聊天 / 工作 / 工具 / 任务 / 工作区 / 人物 / 设置各留一个身份。</div></div></div>';
  thread += '<div class="rail-item" data-state="current"><div class="rail-track"><span class="rail-dot"></span></div>' +
    '<div class="rail-body"><div class="rail-title">当前步骤 · ' + (state.running ? '运行中' : '已停止') + '</div>' +
    '<div class="rail-sub">' + (state.running
      ? '正在整理组件语法与排版阶梯；进度只映射真实执行阶段。'
      : '演示已停止，已有记录与成果保持不变。') + '</div>' +
    '<div class="rail-actions">' +
    '<button class="act-text" data-action="dialog:record">查看记录</button>' +
    '<button class="act-text" data-action="toggle-run">' + (state.running ? '停止' : '继续') + '</button>' +
    '</div></div></div>';
  thread += '<div class="rail-item" data-state="failed"><div class="rail-track"><span class="rail-dot">' + ico('X', 'sm') + '</span></div>' +
    '<div class="rail-body"><div class="rail-title">失败 · 读取样式表</div>' +
    '<div class="rail-sub">文件被占用，已保留输入并说明原因，可重试。</div>' +
    '<div class="rail-actions"><button class="act-text" data-action="toast:已重新执行该步骤（模拟）">重试这一步</button></div></div></div>';
  thread += '</div>';
  thread += '<div class="msg msg-bot"><div class="reply">' +
    '<p>失败的步骤会写明原因，不只靠颜色表达；成果以文件样式回到当前会话，不再塞进厚重卡片。</p>' +
    '</div><div class="msg-meta">09:43 · 神言 V7 预览 · 模拟回复</div>' +
    '<div class="msg-actions"><button class="act-text" data-action="toast:已复制这条回复">' + ico('Copy', 'sm') + '复制</button></div></div>';
  thread += fileCard('V7-视觉语言-摘要.pdf', 'PDF · 1.2 MB · 模拟文件', 'ic_ui_filepdf',
    btn('打开', 'dialog:file', 'secondary') + btn('加入工作区', 'toast:已加入工作区（模拟）', 'quiet'));
  thread += '<div class="notice-row"><span class="status-dot warning"></span>' +
    '<span class="notice-text">等待你确认 · 是否允许读取相册中的 3 张图片？</span>' +
    '<button class="act-text" data-action="dialog:approval">去确认</button></div>';
  thread += '</div>';
  return thread;
}

/* ============================================================== TOOLS === */
const TOOL_ITEMS = [
  { id: 'skills', icon: 'Skills', name: 'Skills', desc: '本地可用技能，支持导入、查看说明与管理。', state: '已安装', tab: 'skills' },
  { id: 'plugins', icon: 'Plugins', name: '插件目录', desc: '在支持的能力范围内搜索、安装与管理扩展。', state: '可安装', tab: 'plugins' },
  { id: 'github', icon: 'GitBranch', name: 'GitHub', desc: '仓库连接：分支、PR、代码修改与测试结果。', state: '已配置', tab: 'plugins' },
  { id: 'connections', icon: 'Connections', name: '连接与账户', desc: '按真实连接状态显示，不显示无效的假按钮。', state: '按需使用', tab: 'plugins' },
  { id: 'models', icon: 'Models', name: '模型能力', desc: '模型支持的多模态入口由统一底座承接。', state: '可配置', tab: 'plugins' },
  { id: 'tasks', icon: 'Tasks', name: '任务与自动化', desc: '定时安排与提醒的执行入口。', state: '按需使用', tab: 'plugins' }
];
function renderTools() {
  const q = state.search.trim().toLowerCase();
  const items = TOOL_ITEMS
    .filter(t => state.toolTab === 'all' || t.tab === state.toolTab)
    .filter(t => (t.name + t.desc + t.state).toLowerCase().indexOf(q) >= 0);
  let html = '<div class="page-head"><h2>工具与技能</h2>' +
    '<p>搜索优先，按真实安装状态展示；行式列表取代卡片套卡片。</p></div>';
  html += '<div class="field">' + ico('Search', 'sm') +
    '<input id="tool-search" type="search" placeholder="搜索名称或完整说明" value="' + safe(state.search) + '" aria-label="搜索工具与技能"></div>';
  html += '<div class="seg" role="tablist" style="margin-top:var(--gapSm)">' +
    [['all', '全部'], ['skills', '技能'], ['plugins', '插件']].map(t =>
      '<button role="tab" aria-selected="' + (state.toolTab === t[0]) + '" data-action="tool-tab:' + t[0] + '">' + t[1] + '</button>').join('') +
    '</div>';
  if (!items.length) {
    html += emptyState('TaskEmpty', '没有匹配的扩展', '换个关键词，或切回「全部」再看看。',
      btn('清空搜索', 'tool-clear', 'secondary'));
    return html;
  }
  html += group('可用扩展', items.map(t => row({
    lead: ico(t.icon),
    title: t.name,
    desc: t.desc,
    trail: '<span class="status-dot ' + (t.state === '已安装' ? 'success' : t.state === '已配置' ? 'accent' : '') + '"></span>' + t.state + ico('ChevronRight', 'sm'),
    action: 'toast:' + t.name + ' · ' + t.state + '（模拟）'
  })).join(''), '状态取自模拟数据；真实安装状态由运行时决定。');
  return html;
}

/* ============================================================== TASKS === */
const TASKS = [
  { name: '每日工作速览', desc: '每天早晨汇总新的项目动态和需要处理的事项。', on: true, next: '明天 09:00', state: '已启用' },
  { name: '周报整理', desc: '每周五把一周的会话与产出整理成一份简报。', on: true, next: '周五 18:30', state: '已启用' },
  { name: '相册备份检查', desc: '检测新拍摄的照片并提醒归档。', on: false, next: '未安排', state: '已暂停' }
];
function renderTasks() {
  let html = '<div class="page-head"><h2>自动化任务</h2>' +
    '<p>名称、启停状态、下次执行三件事说清楚；清单行式，不给每行加底板。</p></div>';
  html += group('我的任务', TASKS.map(t =>
    '<div class="row"><span class="row-main"><span class="row-title">' + t.name + '</span>' +
    '<div class="row-desc">' + t.desc + '</div>' +
    '<div class="row-meta">下次执行 · ' + t.next + '</div></span>' +
    '<span class="row-trail"><span class="status-dot ' + (t.on ? 'success' : '') + '"></span>' + t.state + '</span>' +
    '<span class="row-actions"><button class="switch" role="switch" aria-checked="' + (t.on ? 'true' : 'false') +
    '" data-action="toast:' + t.name + ' 已' + (t.on ? '暂停' : '启用') + '（模拟）" aria-label="' + t.name + ' 启停"></button></span></div>'
  ).join(''));
  html += group('任务记录', [
    row({ title: '已完成', desc: '今天 08:12 · 每日工作速览', trail: '<span class="status-dot success"></span>成功' }),
    row({ title: '等待许可', desc: '相册备份检查需要相册权限', trail: '<span class="status-dot warning"></span>待授权' }),
    row({ title: '失败', desc: '昨天 18:30 · 周报整理未完成', trail: '<span class="status-dot danger"></span>失败' })
  ].join(''), '状态同时有文字，不只靠颜色表达。');
  html += '<div class="btn-row" style="margin-top:var(--gapSection)">' +
    btn('新建任务', 'toast:新建任务（模拟）', 'primary') + btn('查看执行历史', 'toast:执行历史（模拟）', 'secondary') + '</div>';
  return html;
}

/* ========================================================== WORKSPACE === */
const TREE = [
  { depth: 0, type: 'folder', name: 'V7-设计稿', meta: '文件夹' },
  { depth: 1, type: 'file', name: 'V7-视觉语言.md', meta: '42 KB', icon: 'ic_ui_filetxt' },
  { depth: 1, type: 'file', name: 'tokens.json', meta: '3 KB', icon: 'ic_ui_filecode' },
  { depth: 1, type: 'folder', name: '素材', meta: '文件夹' },
  { depth: 2, type: 'file', name: '人物形象-岚.png', meta: '1.1 MB', icon: 'ic_ui_fileimage' },
  { depth: 2, type: 'file', name: '规范摘要.pdf', meta: '1.2 MB', icon: 'ic_ui_filepdf' },
  { depth: 0, type: 'folder', name: '归档', meta: '文件夹' },
  { depth: 1, type: 'file', name: '2026-11-会议记录.docx', meta: '88 KB', icon: 'ic_ui_fileword' },
  { depth: 0, type: 'file', name: '用量导出.xlsx', meta: '24 KB', icon: 'ic_ui_fileexcel' }
];
function renderWorkspace() {
  let html = '<div class="page-head"><h2>工作区</h2>' +
    '<p>文件层级、类型图标与预览；目录树行式，成果用文件样式卡片。</p></div>';
  html += group('目录', TREE.map(t => {
    const iconName = t.type === 'folder' ? 'ic_ui_filefolder' : t.icon;
    const lead = t.type === 'folder'
      ? '<span class="tree-indent">' + ico('Folder', 'sm') + '</span>'
      : '<img class="file-icon-sm" src="' + fileIconUrl(iconName, state.theme) + '" alt="">';
    return '<button class="tree-row" data-action="toast:' + t.name + '（模拟）">' +
      '<span style="flex:none;width:' + (t.depth * 16) + 'px"></span>' + lead +
      '<span class="tree-name">' + t.name + '</span><span class="tree-meta">' + t.meta + '</span>' +
      (t.type === 'folder' ? ico('ChevronRight', 'sm') : '') + '</button>';
  }).join(''));
  html += group('本会话成果', fileCard('V7-视觉语言-摘要.pdf', 'PDF · 1.2 MB · 模拟文件', 'ic_ui_filepdf',
    btn('预览', 'dialog:file', 'secondary') + btn('分享', 'toast:已发起分享（模拟）', 'quiet')),
    '成果在会话与工作区都可打开。');
  return html;
}

/* =========================================================== PERSONA === */
const PEOPLE = [
  ['岚', '日常故事 · 温柔陪伴', 0],
  ['小晴', '新的故事正在开始', 1],
  ['亦书', '专注故事线与记忆', 2],
  ['星遥', '人物档案 · 可编辑', 3]
];
function renderPersona() {
  let html = '<div class="page-head"><h2>人物图集</h2>' +
    '<p>头像、身份与文字气质；选中态改描边加轻染，不加色块。</p></div>';
  html += '<div class="person-grid">' + PEOPLE.map((p, i) =>
    '<button class="person-card" aria-pressed="' + (state.selectedPerson === i) + '" data-action="persona:' + i + '">' +
    '<span class="person-pic"><img src="' + portraitUrl(p[2]) + '" alt="' + p[0] + ' 的示意形象"></span>' +
    '<span class="person-name">' + p[0] + '</span>' +
    '<span class="person-sub">' + p[1] + '</span></button>').join('') + '</div>';
  html += group('继续故事', [
    row({ lead: ico('MessageCircle'), title: '继续和 ' + PEOPLE[state.selectedPerson][0] + ' 对话', desc: '沿用当前人物设定与记忆', trail: ico('ChevronRight', 'sm'), action: 'route:HOME' }),
    row({ lead: ico('GroupChat'), title: '创建群聊', desc: '选择至少两位人物后再确认创建', trail: ico('ChevronRight', 'sm'), action: 'toast:创建群聊（模拟）' }),
    row({ lead: ico('Diary'), title: '人物日记', desc: '记录属于她的生活与承诺', trail: ico('ChevronRight', 'sm'), action: 'route:DIARY' })
  ].join(''));
  html += group('人物档案', [
    row({ title: '设定与气质', desc: '人物卡只描述人物本身', trail: ico('ChevronRight', 'sm'), action: 'toast:设定与气质（模拟）' }),
    row({ title: '记忆与纠错', desc: '误解由对应故事机制管理', trail: ico('ChevronRight', 'sm'), action: 'toast:记忆与纠错（模拟）' })
  ].join(''));
  return html;
}

/* ============================================================== DIARY === */
function renderDiary() {
  let html = '<div class="page-head"><h2>人物日记</h2>' +
    '<p>沉淀经历与承诺，不再堆砌重复的角色资料区。</p></div>';
  html += group('最近记录', [
    row({ title: '窗边的午后', desc: '她记得那次关于未来的约定，也记得在对话结束时没有说出口的那句话。', meta: '10 月 14 日 · 来自人物故事（模拟）', trail: ico('ChevronRight', 'sm'), action: 'toast:打开日记（模拟）' }),
    row({ title: '雨天的公交', desc: '等车的时候想起你说过的下雨天要慢一点。', meta: '10 月 09 日 · 来自人物故事（模拟）', trail: ico('ChevronRight', 'sm'), action: 'toast:打开日记（模拟）' })
  ].join(''));
  html += group('记忆与故事', [
    row({ title: '记忆与纠错', desc: '真实经历、误会与修正由对应故事机制管理。', trail: ico('ChevronRight', 'sm'), action: 'toast:记忆与纠错（模拟）' })
  ].join(''));
  return html;
}

/* =========================================================== SETTINGS === */
const SETTINGS = {
  ROOT: '设置', SESSION: '会话', APPEARANCE: '外观', CHAT: '聊天', MODELS: '模型',
  MODEL_PERFORMANCE: '模型性能', ACCOUNT: 'ChatGPT 账户', MODEL_USAGE: '模型消耗',
  MINIMAX_QUOTA: 'MiniMax 套餐额度', PRICING: '计费价格', USAGE: '用量统计',
  USAGE_LOG: '用量日志', USAGE_DETAIL: '用量详情', MEMORY: '记忆', MEMORY_SETTINGS: '记忆设置',
  MEMORY_MANAGEMENT: '记忆管理', PERMISSIONS: '设备权限', NOTIFICATIONS: '通知',
  ADVANCED: '高级', SESSION_STORAGE: '会话存储', DIAGNOSTICS: '运行诊断'
};
const SETTINGS_GROUPS = [
  ['外观与体验', ['APPEARANCE', 'CHAT', 'SESSION', 'NOTIFICATIONS']],
  ['模型与账户', ['MODELS', 'MODEL_PERFORMANCE', 'ACCOUNT', 'MODEL_USAGE', 'MINIMAX_QUOTA', 'PRICING']],
  ['用量与统计', ['USAGE', 'USAGE_LOG', 'USAGE_DETAIL']],
  ['记忆与数据', ['MEMORY', 'MEMORY_SETTINGS', 'MEMORY_MANAGEMENT', 'SESSION_STORAGE']],
  ['系统与维护', ['PERMISSIONS', 'ADVANCED', 'DIAGNOSTICS']]
];
const SETTINGS_ICON = {
  APPEARANCE: 'Appearance', CHAT: 'ChatStyle', SESSION: 'ChatStyle', NOTIFICATIONS: 'Notifications',
  MODELS: 'Models', MODEL_PERFORMANCE: 'Performance', ACCOUNT: 'Account', MODEL_USAGE: 'Usage',
  MINIMAX_QUOTA: 'Quota', PRICING: 'Usage', USAGE: 'Usage', USAGE_LOG: 'Usage', USAGE_DETAIL: 'Usage',
  MEMORY: 'Memory', MEMORY_SETTINGS: 'Memory', MEMORY_MANAGEMENT: 'Memory', SESSION_STORAGE: 'SessionStorage',
  PERMISSIONS: 'Permissions', ADVANCED: 'Advanced', DIAGNOSTICS: 'Advanced'
};
const SETTINGS_DESC = {
  APPEARANCE: '主题、壁纸可见度与动效节奏', CHAT: '人物风格与消息阅读体验', SESSION: '会话组织与默认行为',
  NOTIFICATIONS: '任务与工作状态提示', MODELS: '已配置模型、额度与模型切换', MODEL_PERFORMANCE: '推理级别与模型表现',
  ACCOUNT: 'ChatGPT 官方账户连接', MODEL_USAGE: '各模型使用量切换显示', MINIMAX_QUOTA: '服务商套餐剩余查询',
  PRICING: '各模型价格或估算配置', USAGE: '选择模型、模式与时间范围', USAGE_LOG: '按时间查看请求记录',
  USAGE_DETAIL: '单次请求明细', MEMORY: '记忆规则、管理与纠错', MEMORY_SETTINGS: '记忆检索与上下文配置',
  MEMORY_MANAGEMENT: '查看、定位和修改记忆记录', SESSION_STORAGE: '会话存储和空间维护',
  PERMISSIONS: '系统能力与隐私授权', ADVANCED: '诊断、检查更新和维护', DIAGNOSTICS: '错误、异常与日志导出'
};
function renderSettings() {
  const key = state.settings;
  if (key === 'ROOT') {
    let html = '<div class="page-head"><h2>设置</h2><p>分组标题加行列表，取消卡片套卡片；21 项入口一个不少。</p></div>';
    SETTINGS_GROUPS.forEach(g => {
      html += group(g[0], g[1].map(k => row({
        lead: ico(SETTINGS_ICON[k]),
        title: SETTINGS[k],
        desc: SETTINGS_DESC[k],
        trail: ico('ChevronRight', 'sm'),
        action: 'settings:' + k
      })).join(''));
    });
    html += '<div class="btn-row" style="margin-top:var(--gapSection)">' +
      btn('清空演示数据', 'dialog:danger', 'quiet') + '</div>';
    html += '<p class="group-note" style="margin-top:var(--gapLg);text-align:center">神言 V7 · 离线设计稿 · 所有条目为模拟</p>';
    return html;
  }
  return renderSettingsSub(key);
}
function renderSettingsSub(key) {
  const head = '<div class="page-head"><h2>' + SETTINGS[key] + '</h2><p>' + (SETTINGS_DESC[key] || '此页面在稿子中尚未接入真实内容。') + '</p></div>';
  if (key === 'APPEARANCE') {
    return head + group('主题', THEMES.map(t =>
      '<div class="row"><span class="row-lead">' +
      '<span class="swatch-chip" style="background:' + TOKENS.themes[t[0]].canvas + '"></span></span>' +
      '<span class="row-main"><span class="row-title">' + t[1] + '</span>' +
      '<div class="row-meta">canvas ' + TOKENS.themes[t[0]].canvas + ' · accent ' + TOKENS.themes[t[0]].accent + '</div></span>' +
      '<span class="row-actions"><button class="btn btn-' + (state.theme === t[0] ? 'primary' : 'secondary') +
      '" data-action="theme:' + t[0] + '">' + (state.theme === t[0] ? '使用中' : '切换') + '</button></span></div>').join('')) +
      group('壁纸可见度', [
        '<div class="row"><span class="row-main"><span class="row-title">背景图片</span>' +
        '<div class="row-desc">选择背景后，文字对比度按最终合成像素校验。</div></span>' +
        '<span class="row-actions"><button class="switch" role="switch" aria-checked="false" data-action="toast:选择背景图片（模拟）" aria-label="背景图片"></button></span></div>',
        '<div class="row"><span class="row-main"><span class="row-title">壁纸可见度</span>' +
        '<div class="row-desc">低于安全下限时自动升级为 solid 兜底，滑块显示真实生效范围。</div></span>' +
        '<span class="row-trail">40%</span></div>'
      ].join('')) +
      group('动效与无障碍', [
        '<div class="row"><span class="row-main"><span class="row-title">减少动效</span>' +
        '<div class="row-desc">非必要时长归零，状态语义与可操作性保留。</div></span>' +
        '<span class="row-actions"><button class="switch" role="switch" aria-checked="false" data-action="toast:已切换减少动效（模拟）" aria-label="减少动效"></button></span></div>',
        '<div class="row"><span class="row-main"><span class="row-title">减少透明度</span>' +
        '<div class="row-desc">浮层退化为 solid，不出现白底。</div></span>' +
        '<span class="row-actions"><button class="switch" role="switch" aria-checked="' + (state.reduceTransparency ? 'true' : 'false') +
        '" data-action="toggle-transparency" aria-label="减少透明度"></button></span></div>'
      ].join(''));
  }
  if (key === 'MODELS') {
    return head + group('已配置模型', [
      row({ lead: ico('Models'), title: 'MiniMax 官方模型', desc: '已配置 · 额度入口按真实账户出现', trail: ico('ChevronRight', 'sm'), action: 'toast:模型详情（模拟）' }),
      row({ lead: ico('Models'), title: 'DeepSeek', desc: '已配置模型 · 消耗单独统计', trail: ico('ChevronRight', 'sm'), action: 'toast:模型详情（模拟）' })
    ].join('')) + group('用量与性能', [
      row({ lead: ico('Usage'), title: '模型消耗', desc: '各模型使用量切换显示', trail: ico('ChevronRight', 'sm'), action: 'settings:MODEL_USAGE' }),
      row({ lead: ico('Performance'), title: '模型性能', desc: '推理档位与连接行为', trail: ico('ChevronRight', 'sm'), action: 'settings:MODEL_PERFORMANCE' })
    ].join(''));
  }
  if (key === 'MODEL_PERFORMANCE') {
    return head + group('推理强度', ['自动', '轻量', '标准', '深入'].map(v =>
      '<div class="row"><span class="row-main"><span class="row-title">' + v + '</span></span>' +
      '<span class="row-actions"><button class="btn btn-secondary" data-action="toast:推理强度 ' + v + '（模拟）">选择</button></span></div>').join(''),
      '可用上限由已配置模型的能力决定。') +
      group('生成偏好', '<div class="row"><span class="row-main"><span class="row-title">随机性设置</span>' +
        '<div class="row-meta">1.00</div></span><span class="row-trail">不会修改已配置模型</span></div>');
  }
  if (key === 'MODEL_USAGE' || key === 'USAGE') {
    return head + group('统计范围', '<div class="seg"><button aria-selected="true">7 天</button><button aria-selected="false">30 天</button><button aria-selected="false">90 天</button></div>' +
      '<div class="seg" style="margin-top:var(--gapSm)"><button aria-selected="true">聊天</button><button aria-selected="false">工作</button></div>') +
      group('汇总', '<div class="row"><span class="row-main"><span class="row-title">总 Token</span><div class="row-desc">未加载真实日志时显示无记录状态</div></span><span class="row-trail">—</span></div>' +
        '<div class="row"><span class="row-main"><span class="row-title">今日</span></span><span class="row-trail">—</span></div>') +
      group('查看记录', row({ lead: ico('Usage'), title: '用量日志', desc: '每次请求可查看上下文与 Tokens', trail: ico('ChevronRight', 'sm'), action: 'settings:USAGE_LOG' }));
  }
  if (key === 'USAGE_LOG') {
    return head + group('日志筛选', '<div class="field">' + ico('Search', 'sm') +
      '<input type="search" placeholder="模型 / 请求 ID / 会话" aria-label="搜索用量记录（设计示意）"></div>') +
      group('请求列表', emptyState('Reader', '当前演示没有真实请求记录', '生产端按真实请求列表分组，点击记录进入详情。'));
  }
  if (key === 'USAGE_DETAIL') {
    return head + group('请求概要',
      row({ title: '模型', trail: '未选择请求' }) + row({ title: '请求来源', trail: '—' }) +
      row({ title: '输入 / 输出 / 缓存', trail: '— / — / —' }) + row({ title: '思考 Tokens', trail: '—' }),
      '不伪造任何模型用量、时间或计费结果。');
  }
  if (key === 'MINIMAX_QUOTA') {
    return head + group('已连接模型', row({ lead: ico('Models'), title: 'MiniMax 模型', desc: '尚未读取任何账号信息', trail: ico('ChevronRight', 'sm'), action: 'settings:MODELS' })) +
      group('官方套餐剩余',
        '<div class="row"><span class="row-main"><span class="row-title">间隔额度</span></span><span class="row-trail">尚未查询</span></div>' +
        '<div class="row"><span class="row-main"><span class="row-title">周额度</span></span><span class="row-trail">尚未查询</span></div>') +
      '<div class="btn-row" style="margin-top:var(--gapSection)">' + btn('查询额度（演示）', 'toast:已发起额度查询（模拟）', 'secondary') + '</div>';
  }
  if (key === 'PRICING') {
    return head + group('模型价格',
      row({ title: '输入 Token 单价', trail: '未读取' }) + row({ title: '输出 Token 单价', trail: '未读取' }) +
      row({ title: '缓存命中单价', trail: '未读取' }),
      '这里展示尚未连接计费数据时的价格配置页面状态。');
  }
  if (key === 'MEMORY' || key === 'MEMORY_SETTINGS' || key === 'MEMORY_MANAGEMENT') {
    let body;
    if (key === 'MEMORY') {
      body = group('记忆', '<div class="row"><span class="row-main"><span class="row-title">记忆规则</span>' +
        '<div class="row-desc">记录经历与承诺时保留来源，纠正后同步误解状态。</div></span></div>') +
        group('入口', row({ lead: ico('Memory'), title: '记忆管理', desc: '查看、定位和修改记录', trail: ico('ChevronRight', 'sm'), action: 'settings:MEMORY_MANAGEMENT' }) +
          row({ lead: ico('Memory'), title: '记忆设置', desc: '配置记忆检索与上下文', trail: ico('ChevronRight', 'sm'), action: 'settings:MEMORY_SETTINGS' }));
    } else if (key === 'MEMORY_SETTINGS') {
      body = group('召回与保存', switchRow('自动召回', '根据权限和当前场景检索相关记忆。', true, 'toast:自动召回（模拟）') +
        switchRow('自动记忆', '持久化前遵守真实记录与用户表达边界。', true, 'toast:自动记忆（模拟）')) +
        group('个人规则', row({ lead: ico('Memory'), title: '用户规则', desc: '打开编辑弹窗并保留当前真实设置', trail: ico('ChevronRight', 'sm'), action: 'toast:用户规则（模拟）' }));
    } else {
      body = group('检索和筛选', '<div class="field">' + ico('Search', 'sm') +
        '<input type="search" placeholder="查找记忆内容或来源" aria-label="搜索记忆记录（设计示意）"></div>') +
        group('记录列表', emptyState('Reader', '当前没有导入真实记忆', '你可以根据来源、类型或关键词查找记忆；当前没有可显示的记录。'));
    }
    return head + body;
  }
  if (key === 'NOTIFICATIONS') {
    return head + group('通知偏好', ['重要工作结果', '待确认事项', '自动化任务', '一般消息'].map((n, i) =>
      switchRow(n, '', i !== 3, 'toast:' + n + '（模拟）')).join(''), '以下仅为配置呈现示例。');
  }
  if (key === 'PERMISSIONS') {
    return head + group('设备授权',
      row({ lead: ico('Image'), title: '照片与文件', desc: '按需向系统请求许可', trail: ico('ChevronRight', 'sm'), action: 'toast:照片与文件权限（模拟）' }) +
      row({ lead: ico('Notifications'), title: '通知', desc: '操作结果不取代系统授权', trail: ico('ChevronRight', 'sm'), action: 'settings:NOTIFICATIONS' }));
  }
  if (key === 'ADVANCED' || key === 'DIAGNOSTICS') {
    return head + group('诊断和系统',
      row({ lead: ico('Advanced'), title: '运行诊断', desc: '错误、异常、关联操作和日志导出', trail: ico('ChevronRight', 'sm'), action: 'settings:DIAGNOSTICS' }) +
      row({ lead: ico('Updates'), title: '检查更新', desc: '在当前设置页发起，无独立更新页面', trail: ico('ChevronRight', 'sm'), action: 'toast:检查更新（模拟）' })) +
      group('系统信息', '<div class="row"><span class="row-main"><span class="row-title">本机信息</span><div class="row-desc">日志与故障排查路径由原生诊断模块负责，本演示不访问设备。</div></span></div>');
  }
  if (key === 'SESSION_STORAGE') {
    return head + group('会话存储',
      row({ lead: ico('SessionStorage'), title: '空间与缓存', desc: '保留原有会话和记录', trail: ico('ChevronRight', 'sm'), action: 'toast:空间与缓存（模拟）' }),
      '存储维护只在确有可执行权限时提供。');
  }
  return head + group(SETTINGS[key],
    '<div class="row"><span class="row-main"><span class="row-title">此页面尚未连接真实内容</span>' +
    '<div class="row-desc">相关设置在可用时展示；本设计稿不伪造数据。</div></span></div>') +
    '<div class="btn-row" style="margin-top:var(--gapSection)">' + btn('返回设置', 'settings-back', 'secondary') + '</div>';
}

/* ------------------------------------------------------------- 顶栏 --- */
function topbarHTML() {
  const back = state.route === 'SETTINGS' && state.settings !== 'ROOT';
  const title = state.route === 'SETTINGS' ? SETTINGS[state.settings] : (state.route === 'HOME' ? (state.mode === 'CHAT' ? '聊天' : '工作') : ROUTE_LABEL[state.route]);
  const sub = state.route === 'HOME'
    ? (state.mode === 'CHAT' ? '连续阅读，消息角色清晰' : '当前步骤突出，成果可见')
    : state.route === 'SETTINGS' ? (SETTINGS_DESC[state.settings] || '体验和账户的掌控权') : ({
      PERSONA_GALLERY: '头像、身份与文字气质', DIARY: '记录属于她的生活', WORKSPACE: '文件层级、类型图标与预览',
      TASKS: '名称、启停状态与下次执行', TOOLS: '搜索优先，按真实安装状态展示'
    })[state.route] || '';
  return '<button class="icon-button" data-action="' + (back ? 'settings-back' : 'drawer') + '" aria-label="' + (back ? '返回上一级' : '打开侧栏') + '">' +
    ico(back ? 'ChevronLeft' : 'Menu') + '</button>' +
    '<div class="top-copy"><div class="top-title">' + title + '</div><div class="top-sub">' + sub + '</div></div>' +
    '<button class="icon-button" data-action="menu:top" aria-label="更多操作">' + ico('MoreVertical') + '</button>' +
    '<button class="icon-button" data-action="theme-next" aria-label="切换主题">' + ico('SunMoon') + '</button>';
}

/* ------------------------------------------------------------- 输入 --- */
function renderDock() {
  const dock = $('dock');
  if (state.route !== 'HOME') { dock.innerHTML = ''; return; }
  const work = state.mode === 'WORK';
  dock.innerHTML = '<div class="composer"><div class="composer-box">' +
    '<textarea id="composer-input" rows="2" aria-label="输入消息" placeholder="' + (work ? '描述你要完成的工作…' : '发消息…') + '">' + safe(state.draft) + '</textarea>' +
    '<div class="composer-bar">' +
    '<button class="icon-button" data-action="sheet:plus" aria-label="添加内容">' + ico('Plus') + '</button>' +
    '<button class="chip" data-action="sheet:model">' + ico('ModelGlyph', 'sm') + '模型</button>' +
    '<span class="composer-hint">' + (work ? '工作模式 · 运行中可继续排队指令' : '演示数据 · 不连接真实模型') + '</span>' +
    (work && state.running
      ? '<button class="icon-button" data-action="toggle-run" aria-label="停止">' + ico('Stop') + '</button>' +
        '<button class="send-btn" data-action="send">排队</button>'
      : '<button class="send-btn" data-action="send">发送</button>') +
    '</div></div></div>';
  const input = $('composer-input');
  if (input) {
    input.addEventListener('input', e => {
      state.draft = e.target.value;
      const pos = e.target.selectionStart;
      renderDock();
      const next = $('composer-input');
      if (next) { next.focus(); next.setSelectionRange(pos, pos); }
    });
  }
}

/* ------------------------------------------------------------- 抽屉 --- */
const DRAWER_GROUPS = [
  ['功能入口', [
    ['聊天', 'route:HOME', 'MessageCircle'], ['工作', 'route:HOME:WORK', 'Zap'], ['工具与技能', 'route:TOOLS', 'Tools'],
    ['工作区', 'route:WORKSPACE', 'Folder'], ['自动化任务', 'route:TASKS', 'Tasks'], ['设置', 'route:SETTINGS', 'Settings']
  ]],
  ['会话', [
    ['今天的聊天', 'route:HOME', 'NewSession'], ['V7 视觉方向', 'route:HOME', 'Palette'], ['昨天的故事', 'route:HOME', 'BookOpen']
  ]],
  ['人物', [
    ['岚', 'persona:0', 'Persona'], ['小晴', 'persona:1', 'Persona'], ['亦书', 'persona:2', 'Persona']
  ]],
  ['任务', [
    ['每日工作速览', 'route:TASKS', 'Tasks'], ['周报整理', 'route:TASKS', 'Tasks']
  ]],
  ['文件', [
    ['V7 视觉语言.md', 'route:WORKSPACE', 'FileText'], ['素材目录', 'route:WORKSPACE', 'Folder']
  ]]
];
function renderDrawer() {
  const q = state.drawerSearch.trim().toLowerCase();
  let html = '<div class="drawer-top"><div class="drawer-brand"><strong>神言 <span>V7</span></strong>' +
    '<button class="icon-button" data-action="close-drawer" aria-label="关闭侧栏">' + ico('X') + '</button></div>' +
    '<div class="drawer-mode"><button aria-selected="' + (state.mode === 'CHAT') + '" data-action="mode:CHAT">聊天</button>' +
    '<button aria-selected="' + (state.mode === 'WORK') + '" data-action="mode:WORK">工作</button></div></div>' +
    '<div class="drawer-scroll"><div class="field">' + ico('Search', 'sm') +
    '<input id="drawer-search" type="search" placeholder="搜索会话、人物、任务、文件" value="' + safe(state.drawerSearch) + '" aria-label="侧栏搜索"></div>';
  let any = false;
  DRAWER_GROUPS.forEach(g => {
    const items = g[1].filter(i => i[0].toLowerCase().indexOf(q) >= 0);
    if (!items.length) return;
    any = true;
    html += '<div class="drawer-group">' + g[0] + '</div><div class="drawer-list">' +
      items.map(i => row({ lead: ico(i[2]), title: i[0], trail: ico('ChevronRight', 'sm'), action: i[1] })).join('') + '</div>';
  });
  if (!any) html += emptyState('Search', '没有匹配项', '换个关键词再试试。');
  html += '</div>';
  $('drawer').innerHTML = html;
  $('drawer').classList.toggle('visible', state.drawer);
  $('scrim-drawer').classList.toggle('visible', state.drawer);
  const search = $('drawer-search');
  if (search) {
    search.addEventListener('input', e => {
      state.drawerSearch = e.target.value;
      const pos = e.target.selectionStart;
      renderDrawer();
      const next = $('drawer-search');
      if (next) { next.focus(); next.setSelectionRange(pos, pos); }
    });
  }
}

/* ------------------------------------------------------- 浮层：Sheet --- */
const SHEETS = {
  plus: { title: '添加内容', desc: '四项入口，选定后再进入下一步。', grid: [['Attachment', '拍照'], ['Image', '图片'], ['FilePreview', '文件'], ['Automation', '多模态创作']] },
  model: { title: '选择模型', desc: '可用模型、思考级别与多模态能力以已连接模型实际返回为准。', rows: [['MiniMax 官方模型', '已配置'], ['DeepSeek', '已配置']] },
  capability: { title: '可用能力', desc: '所有能力入口调用同一业务底座；仅支持的模型展示可操作项。', rows: [['联网搜索', '按模型能力'], ['图像理解', '按模型能力']] },
  plan: { title: '工作计划', desc: '工作模式下可先列出步骤，再交给执行。', rows: [['列出步骤', '直接描述'], ['沿用上次', '来自会话']] }
};
function renderSheet() {
  const sheet = $('sheet');
  if (!state.sheet) {
    sheet.classList.remove('visible');
    sheet.setAttribute('aria-hidden', 'true');
    $('scrim-sheet').classList.remove('visible');
    return;
  }
  const conf = SHEETS[state.sheet] || { title: '操作详情', desc: '此交互是当前界面的离线设计投影，真实状态与动作由对应 Feature 提供。' };
  let html = '<div class="sheet-handle"></div><h3 class="sheet-title">' + conf.title + '</h3><p class="sheet-desc">' + conf.desc + '</p>';
  if (conf.grid) {
    html += '<div class="sheet-grid">' + conf.grid.map(g =>
      '<button class="sheet-tile" data-action="toast:' + g[1] + '（模拟）">' + ico(g[0]) + '<span>' + g[1] + '</span></button>').join('') + '</div>';
  }
  if (conf.rows) {
    html += '<div class="rule-list">' + conf.rows.map(r =>
      row({ title: r[0], trail: r[1], action: 'toast:' + r[0] + '（模拟）' })).join('') + '</div>';
  }
  html += '<div class="sheet-foot">' + btn('关闭', 'close-sheet', 'secondary') + '</div>';
  sheet.innerHTML = html;
  sheet.classList.add('visible');
  sheet.removeAttribute('aria-hidden');
  $('scrim-sheet').classList.add('visible');
}

/* ------------------------------------------------------ 浮层：Dialog --- */
const DIALOGS = {
  approval: { title: '需要你的确认', desc: '是否允许读取相册中的 3 张图片用于本次工作？确认只在本演示中生效，不会真正授权。', ok: '仅演示确认' },
  record: { title: '工作记录与交付', desc: '当前会话的历史、目标步骤及成果来源；示意状态不代表真实执行结果。', ok: '知道了' },
  file: { title: '查看成果', desc: '成果可从会话原位置或工作区访问，设计稿里不伪造已交付状态。', ok: '在工作区打开' },
  danger: { title: '清空演示数据？', desc: '会移除本设计稿中的模拟会话与待发送内容。该操作只在演示中生效。', ok: '确认清空' }
};
function renderDialog() {
  const layer = $('dialog-layer');
  if (!state.dialog) { layer.classList.remove('visible'); layer.innerHTML = ''; return; }
  const conf = DIALOGS[state.dialog] || { title: '确认', desc: '此操作只在演示中生效。', ok: '确认' };
  layer.innerHTML = '<div class="dialog" role="alertdialog" aria-modal="true" aria-label="' + conf.title + '">' +
    '<h3>' + conf.title + '</h3><p>' + conf.desc + '</p>' +
    '<div class="dialog-actions">' + btn('取消', 'close-dialog', 'secondary') + btn(conf.ok, 'dialog-ok', 'primary') + '</div></div>';
  layer.classList.add('visible');
}

/* ------------------------------------------------------- 浮层：Menu --- */
const MENUS = {
  top: [['NewSession', '新建会话', 'route:HOME'], ['Search', '搜索全部', 'focus-search'], ['WorkspaceFiles', '工作区文件', 'route:WORKSPACE'], ['Settings', '设置', 'route:SETTINGS']],
  reply: [['Copy', '复制', 'toast:已复制这条回复'], ['GitBranch', '从这里分支', 'toast:已创建分支会话（模拟）'], ['RefreshCw', '重新生成', 'toast:已重新生成（模拟）'], ['Trash2', '删除这条回复', 'dialog:danger']]
};
function openMenu(key, anchor) {
  const items = MENUS[key] || MENUS.top;
  const layer = $('menu-layer');
  const menu = document.createElement('div');
  menu.className = 'menu';
  menu.setAttribute('role', 'menu');
  menu.innerHTML = items.map((it, i) =>
    (i ? '<div class="menu-sep"></div>' : '') +
    '<button class="menu-item" role="menuitem" data-action="' + it[2] + '">' + ico(it[0], 'sm') + '<span>' + it[1] + '</span></button>').join('');
  layer.innerHTML = '';
  layer.appendChild(menu);
  layer.classList.add('visible');
  state.menuOpen = true;
  const phone = $('phone');
  const pr = phone.getBoundingClientRect();
  const ar = anchor.getBoundingClientRect();
  const mr = menu.getBoundingClientRect();
  let left = ar.right - pr.left - mr.width;
  let top = ar.bottom - pr.top + 4;
  if (top + mr.height > pr.height - 12) top = ar.top - pr.top - mr.height - 4;
  menu.style.left = Math.max(12, Math.min(left, pr.width - mr.width - 12)) + 'px';
  menu.style.top = Math.max(96, top) + 'px';
}
function closeMenu() {
  $('menu-layer').classList.remove('visible');
  $('menu-layer').innerHTML = '';
  state.menuOpen = false;
}

/* ------------------------------------------------------------- Toast --- */
let toastTimer = null;
function toast(message) {
  const n = $('toast');
  n.textContent = message;
  n.classList.add('visible');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => n.classList.remove('visible'), 1900);
}

/* ------------------------------------------------------ 设计参数面板 --- */
function srgb(c) { c /= 255; return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); }
function luminance(hex) {
  const c = hexToRgb(hex);
  return 0.2126 * srgb(c[0]) + 0.7152 * srgb(c[1]) + 0.0722 * srgb(c[2]);
}
function contrast(a, b) {
  const l1 = luminance(a), l2 = luminance(b);
  const hi = Math.max(l1, l2), lo = Math.min(l1, l2);
  return (hi + 0.05) / (lo + 0.05);
}
function hexToHsl(hex) {
  const c = hexToRgb(hex).map(v => v / 255);
  const max = Math.max.apply(null, c), min = Math.min.apply(null, c);
  let h = 0, s = 0; const l = (max + min) / 2;
  if (max !== min) {
    const d = max - min;
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
    if (max === c[0]) h = (c[1] - c[2]) / d + (c[1] < c[2] ? 6 : 0);
    else if (max === c[1]) h = (c[2] - c[0]) / d + 2;
    else h = (c[0] - c[1]) / d + 4;
    h *= 60;
  }
  return [h, s, l];
}
function hslToHex(h, s, l) {
  const c = [0, 0, 0];
  const k = n => (n + h / 30) % 12;
  const a = s * Math.min(l, 1 - l);
  const f = n => l - a * Math.max(-1, Math.min(k(n) - 3, Math.min(9 - k(n), 1)));
  c[0] = f(0); c[1] = f(8); c[2] = f(4);
  return '#' + c.map(v => Math.round(v * 255).toString(16).padStart(2, '0')).join('').toUpperCase();
}
function solveAccent(hex, lightTheme) {
  const on = contrast(POLE_LIGHT, hex) >= contrast(POLE_DARK, hex) ? POLE_LIGHT : POLE_DARK;
  const hsl = hexToHsl(hex);
  const l = Math.max(0.02, Math.min(0.98, lightTheme ? hsl[2] - 0.08 : hsl[2] + 0.08));
  const pressed = hslToHex(hsl[0], hsl[1], l);
  return { on, pressed, onRatio: contrast(on, hex), pressedRatio: contrast(on, pressed) };
}
function renderInspector() {
  const t = TOKENS.themes[state.theme];
  const g = TOKENS.geometry, m = TOKENS.motion;
  let html = '<div class="panel-head"><div class="panel-eyebrow">设计参数 · tokens.json ' + TOKENS.version + '</div>' +
    '<h3>' + (THEMES.find(x => x[0] === state.theme) || ['', ''])[1] + '主题</h3></div>';
  html += '<div class="p-block"><div class="p-title">主题</div><div class="pill-row">' +
    THEMES.map(x => '<button class="pill" aria-pressed="' + (state.theme === x[0]) + '" data-action="theme:' + x[0] + '">' + x[1] + '</button>').join('') +
    '</div></div>';
  html += '<div class="p-block"><div class="p-title">色板 · ' + Object.keys(t).length + ' 个角色</div><div class="swatch-grid">' +
    Object.keys(t).map(k => '<div class="swatch"><span class="swatch-chip" style="background:' + t[k] + '"></span>' +
      '<span class="swatch-name">' + k + '</span><span class="swatch-hex">' + t[k] + '</span></div>').join('') + '</div></div>';
  html += '<div class="p-block"><div class="p-title">材质层级 L1 → L5</div>' +
    Object.keys(TOKENS.surfaces).map(k => {
      const s = TOKENS.surfaces[k];
      return '<div class="layer-row"><span class="layer-chip" style="background:' + t[s.fill] + '"></span>' +
        '<div><div class="layer-name">' + k.replace('_', ' ') + '</div>' +
        '<div class="layer-meta">fill ' + s.fill + ' · α ' + s.opacity + ' · blur ' + s.blur + 'dp · shadow ' + s.shadow + 'dp · border ' + s.border + '</div></div></div>';
    }).join('') + '</div>';
  html += '<div class="p-block"><div class="p-title">排版阶梯</div>' +
    Object.keys(TOKENS.type).map(k => {
      const v = TOKENS.type[k];
      return '<div class="type-row"><span class="type-tag">' + k + '</span>' +
        '<span class="type-sample" style="font-size:' + v.size + 'px;line-height:' + v.lineHeight + ';font-weight:' + v.weight + '">神言正文 Aa</span>' +
        '<span class="type-meta">' + v.size + '/' + v.lineHeight + '/' + v.weight + '</span></div>';
    }).join('') + '</div>';
  html += '<div class="p-block"><div class="p-title">圆角</div>' +
    ['radiusSm', 'radiusControl', 'radiusCard', 'radiusSheet', 'radiusOverlay'].map(k =>
      '<div class="kv"><span>' + k + '</span><span>' + g[k] + 'dp</span></div>').join('') + '</div>';
  html += '<div class="p-block"><div class="p-title">间距</div>' +
    ['gapXs', 'gapSm', 'gapMd', 'gapLg', 'gapSection', 'gapPage', 'touch'].map(k =>
      '<div class="kv"><span>' + k + '</span><span>' + g[k] + 'dp</span></div>').join('') +
    '<div class="gap-strip">' + ['gapXs', 'gapSm', 'gapMd', 'gapLg', 'gapSection'].map(k =>
      '<i style="width:' + g[k] + 'px;height:' + g[k] + 'px"></i>').join('') + '</div></div>';
  html += '<div class="p-block"><div class="p-title">动效时长</div>' +
    Object.keys(m).filter(k => /Ms$/.test(k)).map(k =>
      '<div class="kv"><span>' + k.replace('Ms', '') + '</span><span>' + m[k] + 'ms</span></div>').join('') +
    '<div class="kv"><span>easing</span><span>' + m.easing + '</span></div>' +
    '<div class="kv"><span>pressedScale</span><span>' + m.pressedScale + '</span></div></div>';
  html += '<div class="p-block"><div class="p-title">八色卡 · 前景与按下态求解</div>' +
    TOKENS.primitives.accentCards.map(a => {
      const hex = a[state.theme === 'light' ? 'light' : 'dark'];
      const s = solveAccent(hex, state.theme === 'light');
      return '<div class="accent-card"><span class="accent-chip" style="background:' + hex + '"></span>' +
        '<span><span class="accent-name">' + a.name + '</span><br><span class="accent-meta">on ' + s.on + ' ' + s.onRatio.toFixed(1) +
        ' · pressed ' + s.pressed + ' ' + s.pressedRatio.toFixed(1) + '</span></span></div>';
    }).join('') + '</div>';
  html += '<div class="p-block"><div class="p-title">降级与无障碍</div>' +
    '<div class="kv"><span>减少透明度</span><span>' + (state.reduceTransparency ? 'solid 兜底' : 'overlay + blur 20dp') + '</span></div>' +
    '<div class="kv"><span>最小点击区</span><span>' + g.touch + 'dp</span></div>' +
    '<div class="kv"><span>正文对比门槛</span><span>≥4.5:1</span></div>' +
    '<button class="btn btn-secondary" style="width:100%;margin-top:10px" data-action="toggle-transparency">' +
    (state.reduceTransparency ? '恢复 overlay + blur' : '模拟减少透明度') + '</button></div>';
  $('inspector').innerHTML = html;
}

/* ------------------------------------------------------------- 渲染 --- */
function renderRoute() {
  switch (state.route) {
    case 'HOME': return state.mode === 'WORK' ? renderWork() : renderChat();
    case 'TOOLS': return renderTools();
    case 'TASKS': return renderTasks();
    case 'WORKSPACE': return renderWorkspace();
    case 'PERSONA_GALLERY': return renderPersona();
    case 'DIARY': return renderDiary();
    case 'SETTINGS': return renderSettings();
    default: return '';
  }
}
function drawPage() {
  applyTheme(state.theme);
  $('topbar').innerHTML = topbarHTML();
  $('page').innerHTML = renderRoute();
  renderDock();
  renderDrawer();
  renderSheet();
  renderDialog();
  renderInspector();
  $('board-nav').innerHTML = ROUTES.map(r =>
    '<button data-action="route:' + r + '" aria-current="' + (state.route === r) + '">' +
    '<span style="width:6px;height:6px;border-radius:50%;background:' + (state.route === r ? 'var(--accent)' : 'var(--ruleStrong)') + '"></span>' +
    ROUTE_LABEL[r] + '</button>').join('');
  const toolSearch = $('tool-search');
  if (toolSearch) {
    toolSearch.addEventListener('input', e => {
      state.search = e.target.value;
      const pos = e.target.selectionStart;
      drawPage();
      const next = $('tool-search');
      if (next) { next.focus(); next.setSelectionRange(pos, pos); }
    });
  }
}

/* ------------------------------------------------------------- 行为 --- */
function goRoute(arg) {
  const parts = arg.split(':');
  state.route = parts[0];
  if (parts[0] === 'HOME' && parts[1] === 'WORK') state.mode = 'WORK';
  if (parts[0] === 'HOME') state.mode = 'CHAT';
  if (parts[0] === 'SETTINGS') { state.settings = 'ROOT'; state.settingsStack = ['ROOT']; }
  state.drawer = false;
  state.sheet = '';
  closeMenu();
  drawPage();
  $('page').scrollTop = 0;
}
function backSettings() {
  if (state.settingsStack.length > 1) {
    state.settingsStack.pop();
    state.settings = state.settingsStack[state.settingsStack.length - 1];
  } else if (state.settings !== 'ROOT') {
    state.settings = 'ROOT';
    state.settingsStack = ['ROOT'];
  } else {
    state.route = 'HOME';
  }
  drawPage();
}
function dispatch(code) {
  const parts = code.split(':');
  const verb = parts[0];
  if (verb === 'route') { goRoute(parts.slice(1).join(':')); return; }
  if (verb === 'settings') {
    if (state.route !== 'SETTINGS') state.settingsStack = ['ROOT'];
    if (state.settings !== parts[1]) state.settingsStack.push(parts[1]);
    state.route = 'SETTINGS';
    state.settings = parts[1];
    state.drawer = false;
    state.sheet = '';
    closeMenu();
    drawPage();
    $('page').scrollTop = 0;
    return;
  }
  if (verb === 'persona') {
    state.selectedPerson = Number(parts[1]);
    if (state.route !== 'PERSONA_GALLERY') { state.route = 'PERSONA_GALLERY'; }
    state.drawer = false;
    drawPage();
    return;
  }
  if (verb === 'mode') {
    state.mode = parts[1];
    state.route = 'HOME';
    state.drawer = false;
    drawPage();
    return;
  }
  if (verb === 'theme') {
    state.theme = parts[1];
    drawPage();
    toast('已切换到' + (THEMES.find(x => x[0] === state.theme) || ['', ''])[1] + '主题');
    return;
  }
  if (verb === 'theme-next') {
    const idx = THEMES.findIndex(x => x[0] === state.theme);
    state.theme = THEMES[(idx + 1) % THEMES.length][0];
    drawPage();
    toast('已切换到' + THEMES[(idx + 1) % THEMES.length][1] + '主题');
    return;
  }
  if (verb === 'tool-tab') { state.toolTab = parts[1]; drawPage(); return; }
  if (verb === 'sheet') { state.drawer = false; state.sheet = parts[1]; closeMenu(); renderDrawer(); renderSheet(); return; }
  if (verb === 'dialog') { state.dialog = parts[1]; closeMenu(); renderDialog(); return; }
  if (verb === 'menu') { openMenu(parts[1], document.querySelector('[data-action="menu:' + parts[1] + '"]') || $('topbar')); return; }
  if (verb === 'toast') { toast(parts.slice(1).join(':')); return; }
  switch (code) {
    case 'drawer': state.drawer = true; renderDrawer(); break;
    case 'close-drawer': state.drawer = false; renderDrawer(); break;
    case 'close-sheet': state.sheet = ''; renderSheet(); break;
    case 'close-dialog': state.dialog = ''; renderDialog(); break;
    case 'dialog-ok':
      state.dialog = ''; renderDialog();
      toast('演示确认，不改变任何真实状态');
      break;
    case 'settings-back': backSettings(); break;
    case 'toggle-run':
      state.running = !state.running;
      drawPage();
      toast(state.running ? '已继续运行（模拟）' : '已停止，记录仍保留');
      break;
    case 'toggle-transparency':
      state.reduceTransparency = !state.reduceTransparency;
      drawPage();
      toast(state.reduceTransparency ? '浮层退化为 solid 兜底' : '浮层恢复 overlay + blur');
      break;
    case 'tool-clear': state.search = ''; drawPage(); break;
    case 'focus-search': state.route = 'TOOLS'; state.drawer = false; drawPage(); setTimeout(() => { const i = $('tool-search'); if (i) i.focus(); }, 30); break;
    case 'send':
      if (state.draft.trim()) {
        state.sent.push(state.draft);
        state.draft = '';
        drawPage();
        $('page').scrollTop = $('page').scrollHeight;
        toast('已发送（模拟）');
      } else {
        toast('请输入内容');
      }
      break;
    default: break;
  }
}
document.addEventListener('click', e => {
  if (e.target.id === 'dialog-layer') { state.dialog = ''; renderDialog(); return; }
  const control = e.target.closest('[data-action]');
  if (control) {
    e.preventDefault();
    dispatch(control.dataset.action);
    return;
  }
  if (e.target.closest('#menu-layer') && !e.target.closest('.menu')) closeMenu();
});
document.addEventListener('keydown', e => {
  if (e.key === 'Escape') {
    if (state.dialog) { state.dialog = ''; renderDialog(); return; }
    if (state.sheet) { state.sheet = ''; renderSheet(); return; }
    if (state.menuOpen) { closeMenu(); return; }
    if (state.drawer) { state.drawer = false; renderDrawer(); return; }
    if (state.route === 'SETTINGS' && state.settings !== 'ROOT') backSettings();
  }
});
window.addEventListener('resize', closeMenu);

/* --------------------------------------------------------------- 启动 --- */
function init() {
  if (Q.get('settings') && SETTINGS[Q.get('settings')]) {
    state.route = 'SETTINGS';
    state.settings = Q.get('settings');
    state.settingsStack = ['ROOT', state.settings];
  }
  drawPage();
  if (state.drawer) renderDrawer();
  if (state.sheet) renderSheet();
}
init();
