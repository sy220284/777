# 777 × Kimi 3.1.3 前端复刻基线

> 来源：用户提供的 `kimi_3.1.3.apk`。本文只记录可复现的界面结构、设计 token、交互和路由。
> 777 不复制 Kimi Logo、品牌插画、头像或其他专有视觉资产；功能名称、数据源和业务能力继续属于 777。

## 1. APK 中确认的基础组件

Dex/Compose 资源可直接确认下列 Kimi 基础组件：

- `KimiBottomSheet`
- `KimiCommonDialog`
- `KimiDialogContainer / KimiDialogFrame`
- `KimiLargeTopAppBar`
- `KimiTitleBar`
- `KimiStateTextField`
- `KimiOutlinedButton`
- `KimiTab`
- `KimiTooltip / KimiTooltipBox`
- `KimiLazyRow`
- `kimiPullToRefresh`
- `kimiShadow`

777 对应保持 `Ds*` API，内部视觉与交互按上述 Kimi 组件收口，避免业务页面直接依赖另一套 UI API。

## 2. 颜色真值

APK 内置 `widget_foundation.css`：

### Light

- Accent / KMBlue: `#1783FF`
- Accent hover: `#167FF7`
- Primary background: `#FFFFFF`
- Secondary background: `#F5F5F5`
- Primary label: `rgba(0,0,0,.90)`
- Secondary label: `rgba(0,0,0,.60)`
- Tertiary label: `rgba(0,0,0,.45)`
- Quaternary label: `rgba(0,0,0,.27)`
- Fill F1 / hover / active: `3% / 6% / 7.9%` black
- Fill F2 / hover / active: `5% / 7.9% / 9.8%` black
- Separator S1: `13%` black
- User/gray bubble: `#F5F5F5`
- Error: `#FF3849`
- Success: `#16C456`
- Warning/Orange: `#FF9500`

### Dark

- Accent: `#1A88FF`
- Primary background: `#121212`
- Secondary/group background: `#1F1F1F`
- Primary label: `84%` white
- Secondary label: `56%` white
- Tertiary label: `42%` white
- Quaternary label: `28%` white
- Separator: `12%` white
- Gray bubble: `#292929`

## 3. 字体

- UI B1: `15 / 22`
- Markdown H1: `20 / 32`
- Markdown H2: `18 / 28`
- Markdown B1: `16 / 26`
- Markdown B3: `14 / 22`
- Code: `14 / 22`

系统字体栈：Inter / SF Pro Text / Segoe UI / Roboto / Helvetica Neue / Arial。

## 4. 动效

APK foundation token：

- micro: 60ms
- fast: 120ms
- normal: 200ms
- slow: 300ms
- popover: 140ms
- dialog enter: 180ms
- dialog exit: 135ms
- view/page: 180ms
- panel: 240ms
- list: 180ms

Easing：

- standard: `cubic-bezier(.4,0,.2,1)`
- motion-out: `cubic-bezier(.23,1,.32,1)`
- panel: `cubic-bezier(.32,.72,0,1)`

系统“减少动态效果”必须关闭装饰性动画。

## 5. APK 中确认的页面层级

### 主层

- Main / Chat
- Project
- Task Center
- Plugins
- Agent Skills
- Remote Control
- Profile / My
- Message Center

### 设置层

- Setting
- General
- Appearance
- Theme
- Font Scale
- Language
- Memory
- Push
- Voice Broadcast
- Workspace
- Chat Setting
- Account Safety
- Login Devices
- About

### 工作/内容层

- Project Files
- Project Memory
- Task Detail
- Task Editor
- Plugin Detail
- Skill Manage
- Artifact Preview
- Output Preview
- PPT Preview / Slides
- File Preview
- Remote Control Detail

## 6. 777 入口映射

| Kimi 页面角色 | 777 当前功能 |
|---|---|
| Main / Chat | 本地聊天 / Work 主界面 |
| History sidebar | 会话历史、置顶、搜索、重命名、删除 |
| Project | 工作区文件 |
| Project Memory | 角色记忆 / 日记 / 相关记忆入口 |
| Task Center | 任务与自动化 |
| Task Detail | 自动化执行详情 / Work 运行详情 |
| Plugins / Skills | 工具与能力、技能、连接器 |
| Agent Skills | 智能体/工具能力入口 |
| Remote Control | 远程 Relay / 设备 |
| My / Profile | 777 账号/本地配置入口 |
| Setting | 777 设置 |
| Memory Setting | 记忆设置 |
| Appearance / Theme / Font | 777 外观、主题、字号 |
| Workspace Setting | 本地工作区/存储相关设置 |
| Chat Setting | 会话设置 |
| File / Artifact Preview | 777 文件、图片、产物预览 |
| KimiPlus/Agent entry | 角色库 / 智能体入口 |
| Task/Trigger | 自动化 |

## 7. 侧栏规则

777 的现有功能入口按 Kimi 信息架构搬运：

1. 会话历史继续是侧栏主体。
2. 工作区、任务、工具作为跨聊天/Work 的常驻全局入口。
3. 聊天模式追加：角色库、角色日记、群聊。
4. Work 模式追加：运行中心。
5. 设置/远程/账号类入口放在侧栏底部全局区。
6. 选中态依赖中性填充与文字权重；普通功能图标保持单色，不按功能族染色。

## 8. 工具运行态

APK 含 Rive/WebP 工具动效资源：

- search / browser / web
- code
- image
- ppt
- task / todo
- memory
- mcp
- location
- sound
- ask_user
- create_subagent
- find_asset

777 不直接复制这些专有动画资源；运行态复用现有语义图标与状态数据，并按 Kimi 的 120/180/240ms 动效节奏实现等价交互。

## 9. 交互原则

- 主页面内容优先，常态弱边框。
- Hover/Press 由低 alpha 黑/白填充表达。
- 主操作使用 KMBlue；危险/成功/警告只用于真实语义状态。
- 普通入口图标单色。
- Bottom Sheet 用于选择器、工具面板和轻编辑。
- Dialog 只用于确认、阻断性错误和需要聚焦的短流程。
- Push/Pop 页面使用 180ms 轻位移；同级切换主要 Crossfade。
- Panel/Drawer 类使用 240ms motion-panel 曲线。
- Predictive Back 保留 777 已有系统手势状态机，视觉节奏收口到 Kimi token。
