/* 神言 V8 · 界面逻辑
 * 令牌唯一来源：docs/design/v8/tokens.json（构建时注入 window.V8_TOKENS）
 * 素材：docs/design/v6/assets/**.svg（品牌纸墨图标 / 动作图标 / 肖像 / 文件图标）
 * 文案：取自 App 真实 strings.xml，不使用设计说明文字。
 */
const Q = new URLSearchParams(location.search);
const THEMES = [['light', '暖纸'], ['dark', '夜墨'], ['matte', '哑光']];
const ROUTES = ['HOME', 'PERSONA_GALLERY', 'DIARY', 'WORKSPACE', 'TASKS', 'TOOLS', 'SETTINGS'];
const ROUTE_LABEL = {
  HOME: '聊天', PERSONA_GALLERY: '人物图集', DIARY: '人物日记', WORKSPACE: '工作区',
  TASKS: '定时任务', TOOLS: '能力中心', SETTINGS: '设置',
};

const state = {
  route: ROUTES.indexOf(Q.get('route')) >= 0 ? Q.get('route') : 'HOME',
  mode: Q.get('mode') === 'WORK' ? 'WORK' : 'CHAT',
  theme: THEMES.some(t => t[0] === Q.get('theme')) ? Q.get('theme') : 'light',
  settings: 'ROOT',
  drawer: Q.get('drawer') === '1',
  sheet: '',
  dialog: '',
  running: true,
  toolTab: 'all',
  selectedPerson: 0,
  tasks: { morning: true, weekly: true, backup: false },
  draft: '',
  sent: [],
  panel: false,
};

const PEOPLE = [
  { key: 'lan', name: '岚', sub: '日常故事 · 温柔陪伴', portrait: 'persona-1.svg' },
  { key: 'xiaoqing', name: '小晴', sub: '新的故事正在开始', portrait: 'persona-2.svg' },
  { key: 'yishu', name: '亦书', sub: '专注故事线与记忆', portrait: 'persona-3.svg' },
  { key: 'xingyao', name: '星遥', sub: '人物档案 · 可编辑', portrait: 'persona-4.svg' },
];

const ASSETS = window.V8_ASSETS || {};
const TOKENS = window.V8_TOKENS || null;

function assetUrl(rel) { return ASSETS[rel] || ''; }
function ico(name, extra) {
  const rel = 'icons-actions/' + name + '.svg';
  const url = assetUrl(rel);
  if (!url) return '';
  return '<span class="ico ' + (extra || '') + '" style="--m:url(\'' + url + '\')" aria-hidden="true"></span>';
}
function brandIco(name, extra) {
  const url = assetUrl('icons/' + name + '.svg');
  if (!url) return '';
  return '<span class="ico ' + (extra || '') + '" style="--m:url(\'' + url + '\')" aria-hidden="true"></span>';
}
function miscIco(name, extra) {
  const url = assetUrl('icons-misc/' + name + '.svg');
  if (!url) return '';
  return '<span class="ico ' + (extra || '') + '" style="--m:url(\'' + url + '\')" aria-hidden="true"></span>';
}
function fileIcon(name, extra) {
  const variant = state.theme === 'light' ? 'light' : 'dark';
  const url = assetUrl('file-icons/' + name + '_' + variant + '.svg');
  if (!url) return '';
  return '<span class="ico ' + (extra || '') + '" style="--m:url(\'' + url + '\')" aria-hidden="true"></span>';
}
function esc(s) { return String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }
function $(id) { return document.getElementById(id); }

/* ------------------------------------------------------------------ 顶栏 --- */
function topbar() {
  if (state.route === 'HOME') {
    const work = state.mode === 'WORK';
    const title = work ? 'Harness' : 'Harness';
    const sub = work
      ? (state.running ? '正在执行 · 第 2 步 / 共 4 步' : '已完成 · 4 步')
      : (state.running ? '执行中' : '就绪');
    return {
      cls: state.running ? '' : 'ghost',
      back: false,
      title: title, sub: sub, serif: false,
      right: state.running
        ? '<button class="icon-btn quiet" data-action="toast:已加入待处理回合" aria-label="待处理回合">' + ico('Clock') + '</button>'
        : '',
    };
  }
  const map = {
    PERSONA_GALLERY: ['人物图集', '四位人物，随时切换'],
    DIARY: ['人物日记', '记录属于她的生活'],
    WORKSPACE: ['工作区', '文件、产物与素材'],
    TASKS: ['定时任务', '查看计划任务、周期任务和最近执行结果'],
    TOOLS: ['能力中心', '管理网络搜索、技能与外部连接'],
    SETTINGS: ['设置', '体验和账户的掌控权'],
  };
  const [title, sub] = map[state.route] || ['神言神语', ''];
  return { cls: 'ghost', back: false, title: title, sub: sub, serif: state.route === 'PERSONA_GALLERY' || state.route === 'DIARY', right: '' };
}

function topbarHTML() {
  const t = topbar();
  return '<header class="topbar ' + t.cls + '">' +
    '<button class="icon-btn" data-action="drawer" aria-label="打开侧栏">' + ico('Menu') + '</button>' +
    '<div class="top-copy"><div class="top-title' + (t.serif ? ' serif' : '') + '">' + esc(t.title) + '</div>' +
    (t.sub ? '<div class="top-sub">' + esc(t.sub) + '</div>' : '') + '</div>' +
    (t.right || '') + '</header>' +
    (state.route === 'HOME' && state.running ? '<div class="runbar"></div>' : '');
}

/* --------------------------------------------------------------- HOME 聊天 --- */
function chatHTML() {
  const suggestions = state.sent.map(text => ({
    role: 'user', text: text,
  }));
  const bubble = suggestions.map(item =>
    '<div class="msg user"><div class="bubble">' + esc(item.text) + '</div></div>').join('');

  return '<div class="thread">' +
    '<div class="context-bar">' + ico('Activity', 'sm') +
    '<span>上下文 <b>6.9K</b></span><span>·</span>' +
    '<span>待办 <b>2</b></span><span>·</span>' +
    '<span>记忆已连接</span></div>' +
    '<div class="day">今天</div>' +
    bubble +
    '<div class="msg"><div class="reply">' +
    '<p>我把上一轮的内容重新梳理了一遍，按你要的三块拆开：</p>' +
    '<ul><li>先给结论，再给依据</li><li>每条只留一个动作</li><li>没有把握的地方直接标注</li></ul>' +
    '<blockquote>需要我先看哪一块？或者直接说下一步做什么。</blockquote>' +
    '<div class="code"><div class="code-head">' + ico('Code', 'sm') + 'summarize.py</div>' +
    '<div class="code-body"><code>for note in notes:\n    summary = model.summarize(note, tone="calm")\n    report.add(summary)</code></div></div>' +
    '</div>' +
    '<div class="msg-meta">刚刚 · Harness</div>' +
    '<div class="msg-actions">' +
    '<button class="act" data-action="toast:已复制这条回复">' + ico('Copy', 'sm') + '复制</button>' +
    '<button class="act" data-action="toast:已重新生成">' + ico('RefreshCw', 'sm') + '重试</button>' +
    '<button class="act" data-action="toast:已创建分支会话">' + ico('GitBranch', 'sm') + '分支</button>' +
    '<button class="act icon" data-action="menu:reply" aria-label="更多操作">' + ico('MoreVertical', 'sm') + '</button>' +
    '</div></div></div>';
}

function workHTML() {
  const steps = [
    { state: 'done', title: '读取工作区文件', sub: '7 个文件 · 用时 1.2s' },
    { state: 'running', title: '整理成结构化报告', sub: '正在生成第 2 节…', chip: '执行中' },
    { state: 'waiting', title: '等待你确认发送范围', sub: '涉及 2 个外部地址，需要你点头', chip: '等待批准' },
    { state: 'done', title: '写入成果文件', sub: 'report-2026-11-16.md · 12 KB' },
  ];
  const items = steps.map(step =>
    '<div class="step" data-state="' + step.state + '">' +
    '<div class="step-rail"><span class="step-dot"></span></div>' +
    '<div class="step-body"><div class="step-card">' +
    '<div class="step-title">' + esc(step.title) + (step.chip ? ' <span class="chip' + (step.state === 'running' ? '' : ' plain') + '">' + step.chip + '</span>' : '') + '</div>' +
    '<div class="step-sub">' + esc(step.sub) + '</div>' +
    (step.state === 'running'
      ? '<div class="step-actions"><button class="act" data-action="toast:已插入引导指令">' + ico('Zap', 'sm') + '引导</button>' +
        '<button class="act" data-action="toast:已停止当前步骤">' + ico('Square', 'sm') + '停止这一步</button></div>'
      : '') +
    (step.state === 'waiting'
      ? '<div class="step-actions"><button class="btn btn-primary" data-action="toast:已确认发送范围">去确认</button>' +
        '<button class="btn btn-secondary" data-action="toast:已忽略该请求">忽略</button></div>'
      : '') +
    '</div></div></div>').join('');

  return '<div class="page-head"><h2>每日工作速览</h2><p>把昨天的事接着做完。</p></div>' +
    '<div class="goal"><div class="goal-main"><div class="goal-title">整理上周的会议记录并发给团队</div>' +
    '<div class="goal-sub">4 步 · 已完成 2 · 用时 3 分 12 秒</div></div>' +
    '<span class="chip"><span class="dot run"></span>执行中</span></div>' +
    '<div class="timeline">' + items + '</div>' +
    '<div class="sec-title">成果</div>' +
    '<div class="artifact">' + fileIcon('ic_ui_filetxt', 'file-icon') +
    '<div class="artifact-main"><div class="artifact-name">report-2026-11-16.md</div>' +
    '<div class="artifact-meta">Markdown · 12 KB · 刚刚</div></div>' +
    '<button class="btn btn-quiet" data-action="toast:正在打开成果">查看产物</button></div>';
}

/* ------------------------------------------------------------------ TOOLS --- */
const TOOL_TABS = [['all', '全部'], ['skill', '技能'], ['plugin', '插件'], ['server', '连接']];
function toolsHTML() {
  const rows = {
    all: [
      { icon: 'Skills', name: '网页检索', desc: '搜索并抓取公开网页', trail: '已启用' },
      { icon: 'Plugins', name: '日历插件', desc: '读写本机日历事件', trail: '未连接' },
      { icon: 'Connections', name: '公司内网 MCP', desc: 'stdio 服务 · 需授权', trail: '高风险' },
    ],
    skill: [
      { icon: 'Skills', name: '网页检索', desc: '搜索并抓取公开网页', trail: '已启用' },
      { icon: 'Skills', name: '文档摘要', desc: '把长文档压成要点', trail: '已启用' },
    ],
    plugin: [
      { icon: 'Plugins', name: '日历插件', desc: '读写本机日历事件', trail: '未连接' },
    ],
    server: [
      { icon: 'Connections', name: '公司内网 MCP', desc: 'stdio 服务 · 需授权', trail: '高风险' },
    ],
  };
  const list = rows[state.toolTab];
  return '<div class="search">' + ico('Search', 'sm') + '<input placeholder="搜索插件" aria-label="搜索插件"></div>' +
    '<div class="segs" role="tablist">' + TOOL_TABS.map(t =>
      '<button role="tab" aria-selected="' + (state.toolTab === t[0]) + '" data-action="tool-tab:' + t[0] + '">' + t[1] +
      '<span class="count">' + rows[t[0]].length + '</span></button>').join('') + '</div>' +
    '<div class="rows">' + list.map(item =>
      '<button class="row" data-action="toast:' + esc(item.name) + '（模拟）">' +
      '<span class="row-icon">' + brandIco(item.icon) + '</span>' +
      '<span class="row-main"><span class="row-title">' + esc(item.name) + '</span>' +
      '<span class="row-desc">' + esc(item.desc) + '</span></span>' +
      '<span class="row-trail">' + esc(item.trail) + ico('ChevronRight', 'sm') + '</span></button>').join('') + '</div>';
}

/* ------------------------------------------------------------------ TASKS --- */
function tasksHTML() {
  const list = [
    { key: 'morning', name: '每日晨报', desc: '每天 08:00 · 最近成功 2 小时前' },
    { key: 'weekly', name: '周报整理', desc: '每周一 09:30 · 最近成功 3 天前' },
    { key: 'backup', name: '工作区备份', desc: '每天 23:00 · 上次失败' },
  ];
  return '<div class="rows">' + list.map(t =>
      '<div class="row"><span class="row-icon">' + brandIco('Tasks') + '</span>' +
      '<span class="row-main"><span class="row-title">' + esc(t.name) + '</span>' +
      '<span class="row-desc">' + esc(t.desc) + '</span></span>' +
      '<span class="row-trail">' +
      (t.key === 'backup' ? '<span class="chip bad">失败</span>' : '') +
      '<button class="switch" role="switch" aria-checked="' + state.tasks[t.key] + '" data-action="task:' + t.key + '" aria-label="' + esc(t.name) + ' 启停"></button>' +
      '</span></div>').join('') + '</div>' +
    '<div class="btn-row" style="margin-top:16px"><button class="btn btn-primary" data-action="toast:已创建定时任务">' + miscIco('ic_ui_add', 'sm') + '创建定时任务</button></div>';
}

/* --------------------------------------------------------------- WORKSPACE --- */
function workspaceHTML() {
  const files = [
    { name: 'report-2026-11-16.md', meta: '12 KB', type: 'ic_ui_filecode' },
    { name: '会议记录 11-15.md', meta: '8 KB', type: 'ic_ui_filetext' },
    { name: '素材', meta: '目录', type: 'ic_ui_filefolder' },
    { name: '导出结果.pdf', meta: '1.4 MB', type: 'ic_ui_filearchive' },
  ];
  return '<div class="crumb">' + ico('Folder', 'sm') + '<span>工作区</span>' + ico('ChevronRight', 'sm') + '<span>本机</span></div>' +
    '<div class="rows>' + files.map(f =>
      '<button class="row" data-action="toast:' + esc(f.name) + '（模拟）">' +
      '<span class="row-icon">' + fileIcon(f.type, 'file-icon') + '</span>' +
      '<span class="row-main"><span class="row-title">' + esc(f.name) + '</span></span>' +
      '<span class="row-trail"><span class="row-meta">' + esc(f.meta) + '</span>' + ico('ChevronRight', 'sm') + '</span></button>').join('') + '</div>' +
    '<div class="btn-row" style="margin-top:16px"><button class="btn btn-secondary" data-action="toast:正在打开成果">' + ico('Folder', 'sm') + '查看产物</button></div>';
}

/* ----------------------------------------------------------------- PERSONA --- */
function personaHTML() {
  return '<div class="people">' + PEOPLE.map((p, i) =>
      '<button class="person" aria-pressed="' + (state.selectedPerson === i) + '" data-action="persona:' + i + '">' +
      '<span class="person-pic"><img src="' + assetUrl('portraits/' + p.portrait) + '" alt="' + esc(p.name) + '"></span>' +
      '<span class="person-dot" style="background:var(--persona-' + p.key + ')"></span>' +
      '<span class="person-meta"><span class="person-name">' + esc(p.name) + '</span>' +
      '<span class="person-sub">' + esc(p.sub) + '</span></span></button>').join('') + '</div>' +
    '<div class="sec-title">继续</div>' +
    '<div class="rows">' +
    '<button class="row" data-action="toast:与 ' + esc(PEOPLE[state.selectedPerson].name) + ' 对话">' +
    '<span class="row-icon">' + brandIco('Persona') + '</span>' +
    '<span class="row-main"><span class="row-title">继续和 ' + esc(PEOPLE[state.selectedPerson].name) + ' 对话</span>' +
    '<span class="row-desc">沿用当前人物设定与记忆</span></span>' +
    '<span class="row-trail">' + ico('ChevronRight', 'sm') + '</span></button>' +
    '<button class="row" data-action="toast:打开人物日记">' +
    '<span class="row-icon">' + brandIco('Diary') + '</span>' +
    '<span class="row-main"><span class="row-title">人物日记</span>' +
    '<span class="row-desc">记录属于她的生活与承诺</span></span>' +
    '<span class="row-trail">' + ico('ChevronRight', 'sm') + '</span></button>' +
    '</div>';
}

/* ------------------------------------------------------------------ DIARY --- */
function diaryHTML() {
  return '<div class="diary"><div class="diary-date">11 月 15 日 · 岚</div>' +
    '<h2>雨停之后</h2>' +
    '<p>今天下午三点，雨忽然停了。她把窗推开一条缝，说楼下的桂花被雨打落了一半，香味比昨天淡。</p>' +
    '<p>我把这句话记下来。她说不用记，太小了。可我还是记了——因为她说话的时候，窗外的光正好落在她手背上。</p>' +
    '<p>晚上我们聊到很晚。她说如果有一天不再写故事了，就去开一家很小的书店。我说那我要做第一个客人。</p>' +
    '<p>她笑了。这是今天第二次。</p></div>';
}

/* ---------------------------------------------------------------- SETTINGS --- */
const SETTINGS = [
  { group: '外观与体验', items: [
    { icon: 'Appearance', name: '外观', desc: '主题、配色、背景与阅读', trail: '暖纸' },
    { icon: 'ChatStyle', name: '聊天', desc: '人物风格与消息阅读体验' },
    { icon: 'SessionStorage', name: '会话', desc: '会话组织与默认行为' },
    { icon: 'Notifications', name: '通知', desc: '任务与工作状态提示' },
  ]},
  { group: '模型与账户', items: [
    { icon: 'Models', name: '模型', desc: '已配置模型、额度与模型切换' },
    { icon: 'Performance', name: '模型性能', desc: '推理级别与模型表现' },
    { icon: 'Account', name: 'ChatGPT 账户', desc: '官方账户连接' },
    { icon: 'Usage', name: '模型消耗', desc: '各模型用量与估算显示' },
  ]},
  { group: '能力与权限', items: [
    { icon: 'Tools', name: '能力中心', desc: '技能、插件与外部连接' },
    { icon: 'Permissions', name: '权限', desc: '工具、文件与网络访问' },
    { icon: 'Memory', name: '记忆', desc: '长期记忆与上下文用量' },
    { icon: 'Quota', name: '配额', desc: '额度与重置时间' },
  ]},
  { group: '系统', items: [
    { icon: 'Advanced', name: '高级', desc: '诊断、引擎与开发者选项' },
    { icon: 'Updates', name: '检查更新', desc: '当前版本与更新通道' },
  ]},
];
function settingsHTML() {
  return SETTINGS.map(section =>
    '<div class="sec-title">' + section.group + '</div><div class="rows">' +
    section.items.map(item =>
      '<button class="row" data-action="toast:' + esc(item.name) + '（模拟）">' +
      '<span class="row-icon">' + brandIco(item.icon) + '</span>' +
      '<span class="row-main"><span class="row-title">' + esc(item.name) + '</span>' +
      '<span class="row-desc">' + esc(item.desc) + '</span></span>' +
      '<span class="row-trail">' + (item.trail ? esc(item.trail) : '') + ico('ChevronRight', 'sm') + '</span></button>').join('') +
    '</div>').join('') +
    '<div class="sec-title">账户</div><div class="rows">' +
    '<div class="row danger"><span class="row-icon">' + brandIco('Advanced') + '</span>' +
    '<span class="row-main"><span class="row-title">重置本机数据</span>' +
    '<span class="row-desc">会删除会话、记忆与工作区文件</span></span></div></div>';
}

/* ------------------------------------------------------------------ 抽屉 --- */
function drawerHTML() {
  const groups = [
    { title: '今天', items: [['聊天', 'route:HOME', 'MessageCircle'], ['人物日记', 'route:DIARY', 'Diary']] },
    { title: '最近', items: [['V7 视觉方向', 'route:HOME', 'Palette'], ['周报整理', 'route:TASKS', 'Tasks'], ['导出结果', 'route:WORKSPACE', 'FileText']] },
    { title: '全部', items: [['人物图集', 'route:PERSONA_GALLERY', 'Persona'], ['工作区', 'route:WORKSPACE', 'Folder'], ['能力中心', 'route:TOOLS', 'Tools'], ['定时任务', 'route:TASKS', 'Tasks'], ['设置', 'route:SETTINGS', 'Settings']] },
  ];
  return '<div class="brand"><span class="seal">言</span>' +
    '<span class="brand-text"><span class="brand-name">神言神语</span><span class="brand-ver">777 · V8 视觉草案</span></span></div>' +
    '<div class="drawer-search">' + ico('Search', 'sm') + '<input placeholder="搜索会话、人物、任务、文件" aria-label="搜索"></div>' +
    '<div class="drawer-scroll">' + groups.map(g =>
      '<div class="drawer-group">' + g.title + '</div>' +
      g.items.map(i => '<button class="drawer-item' + (i[1] === 'route:' + state.route ? ' active' : '') + '" data-action="' + i[1] + '">' +
        brandIco(i[2]) + '<span>' + esc(i[0]) + '</span></button>').join('')).join('') + '</div>';
}

/* ------------------------------------------------------------------ 底部面板 --- */
function sheetHTML() {
  if (state.sheet === 'attach') {
    const items = [['图片', 'Image'], ['文件', 'Paperclip'], ['技能', 'Code'], ['子代理', 'Users']];
    return '<div class="sheet-handle"></div><h3>附加内容</h3><p class="hint">图片、文件、技能与子代理。</p>' +
      '<div class="sheet-grid">' + items.map(i =>
        '<button class="sheet-tile" data-action="toast:' + i[0] + '（模拟）">' + ico(i[1]) + '<span>' + i[0] + '</span></button>').join('') + '</div>';
  }
  if (state.sheet === 'context') {
    return '<div class="sheet-handle"></div><h3>上下文</h3><p class="hint">本轮使用的 token 与记忆占用。</p>' +
      '<div class="card flat"><div class="kv"><span>对话</span><span>4.2K</span></div>' +
      '<div class="kv"><span>记忆</span><span>1.8K</span></div>' +
      '<div class="kv"><span>工具</span><span>0.9K</span></div>' +
      '<div class="kv"><span>合计</span><span>6.9K</span></div></div>';
  }
  return '';
}

/* ------------------------------------------------------------------ 菜单 --- */
function menuHTML() {
  return '<div class="menu" style="left:150px;top:120px">' +
    '<button data-action="toast:已复制链接">' + ico('Copy', 'sm') + '复制链接</button>' +
    '<button data-action="toast:已导出为图片">' + ico('Download', 'sm') + '导出图片</button>' +
    '<div class="sep"></div>' +
    '<button data-action="toast:已删除这条回复">' + ico('Trash2', 'sm') + '删除</button>' +
    '</div>';
}

/* ------------------------------------------------------------------ 对话框 --- */
function dialogHTML() {
  return '<div class="dialog"><h3>停止当前执行？</h3>' +
    '<p>正在进行的回合会立刻结束，已完成的步骤会保留在记录里。</p>' +
    '<div class="dialog-actions">' +
    '<button class="btn btn-secondary" data-action="dialog:close">继续执行</button>' +
    '<button class="btn btn-danger" data-action="toast:已停止">停止</button></div></div>';
}

/* ------------------------------------------------------------------ 输入区 --- */
function dockHTML() {
  if (state.route !== 'HOME') return '';
  const work = state.mode === 'WORK';
  return '<div class="dock-wrap"><div class="dock">' +
    '<textarea id="composer" rows="1" aria-label="输入消息" placeholder="' + (work ? '补充说明或新的指令…' : '给 Harness 发消息…') + '">' + esc(state.draft) + '</textarea>' +
    '<div class="dock-bar">' +
    '<button class="dock-btn" data-action="sheet:attach" aria-label="附加内容">' + miscIco('ic_ui_add', 'sm') + '</button>' +
    '<button class="dock-chip" data-action="toast:选择模型（模拟）">' + miscIco('ic_ui_model', 'sm') + 'Harness-4</button>' +
    (work ? '<button class="dock-chip" data-action="toast:已切换为引导模式">' + ico('Zap', 'sm') + '引导</button>' : '') +
    '<span class="spacer"></span>' +
    (state.running
      ? '<button class="send stop" data-action="stop" aria-label="停止">' + miscIco('ic_ui_button_stop_glyph', 'sm') + '</button>'
      : '<button class="send" data-action="send" aria-label="发送">' + miscIco('ic_ui_button_send_glyph', 'sm') + '</button>') +
    '</div></div></div>';
}

/* ------------------------------------------------------------------ 渲染 --- */
function screenHTML() {
  if (state.route === 'HOME') return state.mode === 'WORK' ? workHTML() : chatHTML();
  if (state.route === 'TOOLS') return toolsHTML();
  if (state.route === 'TASKS') return tasksHTML();
  if (state.route === 'WORKSPACE') return workspaceHTML();
  if (state.route === 'PERSONA_GALLERY') return personaHTML();
  if (state.route === 'DIARY') return diaryHTML();
  return settingsHTML();
}

function render() {
  document.body.dataset.theme = state.theme;
  $('topbar').outerHTML = topbarHTML();
  $('screen').innerHTML = screenHTML();
  $('screen').className = 'screen' + (state.route === 'HOME' ? ' with-dock' : '');
  $('dock').innerHTML = dockHTML();
  $('drawer').innerHTML = drawerHTML();
  $('drawer').classList.toggle('show', state.drawer);
  $('scrim-drawer').classList.toggle('show', state.drawer);
  $('sheet').innerHTML = sheetHTML();
  $('sheet').classList.toggle('show', !!state.sheet);
  $('scrim-sheet').classList.toggle('show', !!state.sheet);
  $('menu-layer').innerHTML = state.dialog === 'menu' ? menuHTML() : '';
  $('menu-layer').classList.toggle('show', state.dialog === 'menu');
  $('dialog-layer').innerHTML = state.dialog === 'stop' ? dialogHTML() : '';
  $('dialog-layer').classList.toggle('show', state.dialog === 'stop');
  renderStageNav();
}

function renderStageNav() {
  const nav = $('stage-nav');
  if (nav) nav.innerHTML = ROUTES.map(r =>
    '<button data-action="route:' + r + '" aria-current="' + (state.route === r) + '">' + ROUTE_LABEL[r] + '</button>').join('');
}

/* ------------------------------------------------------------------ 交互 --- */
function toast(text) {
  const el = $('toast');
  el.textContent = text;
  el.classList.add('show');
  clearTimeout(el._t);
  el._t = setTimeout(() => el.classList.remove('show'), 2200);
}

function go(route, mode) {
  if (route) state.route = route;
  if (mode) state.mode = mode;
  state.drawer = false;
  state.dialog = '';
  render();
  $('screen').scrollTop = 0;
}

document.addEventListener('click', ev => {
  const btn = ev.target.closest('[data-action]');
  if (!btn) return;
  const action = btn.dataset.action;
  if (action.startsWith('route:')) {
    const [route, mode] = action.slice(6).split(':');
    go(route, mode === 'WORK' ? 'WORK' : undefined);
  } else if (action === 'drawer') { state.drawer = true; render(); }
  else if (action === 'close-drawer') { state.drawer = false; render(); }
  else if (action.startsWith('sheet:')) { state.sheet = action.slice(6); render(); }
  else if (action === 'close-sheet') { state.sheet = ''; render(); }
  else if (action.startsWith('menu:')) { state.dialog = 'menu'; render(); }
  else if (action === 'dialog:close') { state.dialog = ''; render(); }
  else if (action.startsWith('dialog:')) { state.dialog = action.slice(7); render(); }
  else if (action.startsWith('toast:')) { toast(action.slice(6)); }
  else if (action === 'send') {
    const input = $('composer');
    const text = (input.value || '').trim();
    if (!text) { toast('先写点什么'); return; }
    state.sent.push(text);
    state.draft = '';
    render();
    toast('已发送');
  } else if (action === 'stop') { state.running = false; render(); toast('已停止'); }
  else if (action.startsWith('tool-tab:')) { state.toolTab = action.slice(9); render(); }
  else if (action.startsWith('task:')) {
    const key = action.slice(5);
    state.tasks[key] = !state.tasks[key];
    render();
    toast(state.tasks[key] ? '已启用' : '已暂停');
  } else if (action.startsWith('persona:')) {
    state.selectedPerson = Number(action.slice(7));
    render();
    toast('已切换到 ' + PEOPLE[state.selectedPerson].name);
  }
});

document.addEventListener('input', ev => {
  if (ev.target.id === 'composer') state.draft = ev.target.value;
});

document.addEventListener('keydown', ev => {
  if (ev.key === 'Escape') { state.sheet = ''; state.dialog = ''; state.drawer = false; render(); }
});

render();

// 供设计稿画廊切换变体 / 主题 / 路由使用（仅评审工具，不进入产品）
window.V8_APP = { state: state, render: render };
