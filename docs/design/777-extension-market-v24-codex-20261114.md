# 777 技能与插件市场：V2.4 UI × Codex 源码复核与实现契约

> 日期：2026-11-14。实现分支：`ui/skill-plugin-simple-flow-20261114`，PR [#916](https://github.com/sy220284/777/pull/916)。
> 本文记录本次源码改造后的操作路径和已知边界。实际通过状态以该 PR 最新提交的 GitHub Actions 回执为准。

## 设计依据

- 本机设计技能原始包：资料库 `/技能/设计技能2.4.zip`，包括 `手机UI设计技能/SKILL.md`、`references/手机页面规划与简洁性.md`、`references/安卓原生与接口契约.md`、`references/质量门禁与设计审计.md`。
- 777 [现行 UI 规范](../UI-UX.zh-CN.md)和 [V6.4 设计目标](v6/V6.4-VISUAL-MATERIAL-GUIDELINES.zh-CN.md)。
- [Codex 核心插件管理器](https://github.com/openai/codex/blob/main/codex-rs/core-plugins/src/manager.rs)、[市场解析器](https://github.com/openai/codex/blob/main/codex-rs/core-plugins/src/marketplace.rs)、[清单协议](https://github.com/openai/codex/blob/main/codex-rs/core-plugins/src/manifest.rs)、[搜索实现](https://github.com/openai/codex/blob/main/codex-rs/app-server/src/request_processors/plugins/search.rs)。
- [OpenAI 官方插件目录](https://github.com/openai/plugins/blob/main/.agents/plugins/marketplace.json)、[Figma 插件元数据](https://github.com/openai/plugins/blob/main/plugins/figma/.codex-plugin/plugin.json)。

## 产品路径与页面职责

```text
能力中心
├─ 插件：以插件包为主条目，显示真实安装及连接状态
│  └─ 插件详情：官方品牌图标、介绍、使用示例、所含技能折叠组
│     ├─ 安装所含 Android 可用技能：跳过已安装部分
│     ├─ 授权：使用清单预置的公开 HTTPS MCP 配置，浏览器 OAuth
│     └─ 连接管理：依实际连接状态进入原有连接器
├─ 技能：本地技能、预置技能、官方精选技能
│  └─ 技能详情：安装／使用、依赖折叠组、编辑、升级、恢复、分享、卸载
└─ 连接器：已有 MCP 服务连接与管理（单独页面）
```

首页只保留分类导航和轻量条目。搜索、导入、手动创建、刷新目录移至按需操作入口；不把一包插件的子技能、OAuth 地址、依赖元数据、钩子和内部安装状态逐项铺在首页。列表条目最低触控目标沿用现行 Compose 规范，页面继承现有配色、材质和安全区。全屏详情沿用 `SyTopBar` 返回、滚动内容与底部唯一主要动作。

## Codex 源码对照：能直接复用什么

| Codex 原始做法 | 777 本轮实现 |
|---|---|
| 一个插件包可声明多个 skills、MCP、App 和 Hooks | 以插件包为一条目录记录；详情折叠列出技能；可用技能组通过一个主按钮触发安装 |
| `plugin/install` 安装与后续授权分为不同事实 | 安装来源由 `LocalGitHubSkillImporter` 持有，OAuth 由本机 MCP 协调器持有；同步结果后再呈现已连接 |
| 安装识别依靠插件来源、固定位置 | 通过 `sourceUrl` 和官方声明的目录映射判定该插件技能，避免同名误判 |
| 官方 manifest 的 `interface.logo` 和 `defaultPrompt` | 对可验证的本地官方包按需读取其发布方 PNG/WebP/JPG 资源和 1–3 条示例；无资源时用应用内图标 |
| 市场搜索区分来源、搜索范围、分页与策略 | 777 目前以经过校验的 `openai-curated` 目录为主；结果仅表示发现，下载和授权由用户操作触发 |
| 安装策略、认证策略、运行可用性分别表达 | 不将“已下载技能”直接标记为“官方 App/Hooks 全部可运行”；详情显示具体能力和来源 |

**重要范围边界：** Android 777 当前可直接安装本机技能和连接适用的 HTTPS MCP 服务；Codex Desktop 专属应用、插件钩子、其它进程宿主功能仍按真实支持能力显示。一个 Codex 插件元数据里声明了 App，并不意味着 Android 已加载该 App 的执行器。对于第三方 Git 插件，当前以可审核的单技能来源导入，不将第三方插件目录冒充已经完整安装。

## UI 与运行时所有权

| 用户动作 | 唯一执行者 | 最终核验 |
|---|---|---|
| 浏览市场 | `CodexOfficialPluginCatalog.list` | 官方身份、真实分类、可用策略、缓存和读取失败 |
| 打开详情 | `codexPluginDetail` | 内容来源、技能目录映射、MCP 配置、品牌素材和示例 |
| 一键安装插件中的可用技能 | `ToolsViewModel.installCodexPlugin` → `LocalToolsUiFacade` → importer | 只跳过具有该插件固定来源的技能，中断后再次安装继续缺失项 |
| 浏览器 OAuth | `LocalMcpOAuthCoordinator` | 真实浏览器授权、连接同步、取消与失败提示 |
| 展示状态 | `ToolsUiState`，原有本地技能和服务数据投影 | 下载与授权有不同回执；“已连接”要求真实服务信息 |
| 更新/恢复/卸载 | 本地技能管理器及原有确认路径 | 版本固定、来源及执行结果 |

## V2.4 质量门禁与验证范围

1. **信息简洁：** 主屏以技能或插件对象为主；详情承担介绍、依赖、示例和操作；子技能和运行依赖默认折叠。
2. **状态可信：** 列表/详情展示已安装、可安装、已连接、连接失败等可核验状态；网络读取失败保留原有目录并给出重试入口。
3. **操作有效：** 安装、授权、搜索、升级、恢复和卸载均调用已有 owner 接口；禁止以“打开设置页面”冒充授权成功。
4. **恢复路径：** 批量技能包安装按已固定来源判断缺失，可再次点击补齐；已成功下载的技能留在本地。拒绝授权后详情仍可再次触发。
5. **设备交互：** Android 36 Compose 测试覆盖列表打开、详情、安装/授权、子技能收纳与 320dp 窄屏；新改动交由 PR 最新 Actions 再次执行。

## 安装来源与版本核验

已安装状态依据本机技能导入器的固定版本来源记录判定：GitHub 主机须为 `github.com`，仓库须为 `openai/plugins`，版本须为完整 40 位提交 SHA，目录须与插件清单声明的技能目录一致。其他仓库的同名技能、仅路径相同的链接或可变的 `main` 分支均不会被识别为官方已安装。列表和详情统一采用这项核验规则。

## 图标规则

官方清单中的 `interface.logo` 是优先来源。通过受约束的本地官方插件路径和 `raw.githubusercontent.com/openai/plugins` 读取；图标大小限制 256 KiB、尺寸最多 1024×1024，在可见条目首次请求并只驻留内存。无法取得已验证品牌素材时，回退到仓库内置的专属矢量图标；未知品牌按技能语义使用精细图标。官方来源名称与身份由清单保持，不改写持久化标识。

## Figma 设计文件

设计文件：[777 技能插件体验重构](https://www.figma.com/design/8WPMsAY8NnecMcVoCwz1AH)。本次会话通过 Figma 官方连接确认：Starter 计划的 MCP 调用额度已满，无法在线读取或写入画布节点。因此本轮 **Figma 画布不可作为高保真还原的验收证据**，源码、原始 V2.4 技能和 Android CI 为本次实际复核依据；不把离线 PNG/SVG 声称为已写入 Figma 的可编辑节点。
