# Kimi 3.1.3 APK 动画、图标与组件线索清单

> 来源：用户提供的 `kimi_3.1.3(1).apk`，SHA-256 `cd8c9cc0255803607ab1f8a3608b173978e61c9dd151f8096adae72c92b8afc1`。本清单为提取基准，不代表 777 已完整复刻。资源原件仅保留在用户交付包中，不提交第三方原始文件到公开仓库。

## 资产核查与提取结果

| 类别 | 数量 | 已取得的形式 |
|---|---:|---|
| APK ZIP 条目 | 1639 | 文件索引 |
| Compose 资源 | 743 | 其中字体 6 个仅记录，未随交付包提供 |
| Rive 动画 | 21 | 原始 `.riv` |
| 逐帧动画 | 30 | 28 WebP + 2 APNG，全部动帧保留 |
| 图标及相关资源 | 636 | 512 XML（其中矢量 510）、124 位图 |
| 转换 SVG | 510 | 均通过渲染测试；需与 Android 原图视觉核对 |
| Android 编译资源 | 477 | 原件留存，部分 XML 为二进制格式 |
| Compose 其他视觉 | 33 | PNG/JPG 等原件 |
| 本地化编译资源 | 17 | `.cvr` 原件 |
| 能识别的 Kimi 包名类 | 38 | DEX 类名索引；不等于实际 UI 组件数 |

### Rive：21 项

- `discover_bg.riv`
- `icon-browser.riv`
- `icon-code.riv`
- `icon-k2thinking.riv`
- `icon-lightbulb-loading.riv`
- `icon-searchloading2.riv`
- `k1loading.riv`
- `kimiavator_homepage.riv`
- `kimiavator_topbar.riv`
- `likeanimation.riv`
- `likeanimationred.riv`
- `tool-data.riv`
- `tool-doc.riv`
- `tool-image.riv`
- `tool-locate.riv`
- `tool-mcp.riv`
- `tool-memory.riv`
- `tool-ppt.riv`
- `tool-sounds.riv`
- `tool-todo.riv`
- `tool-web.riv`

### WebP/APNG 动画：30 项

- `anim_tool_ask_user.webp`
- `anim_tool_browser.webp`
- `anim_tool_code.webp`
- `anim_tool_create_subagent.webp`
- `anim_tool_data.webp`
- `anim_tool_file.webp`
- `anim_tool_find_asset.webp`
- `anim_tool_image.webp`
- `anim_tool_location.webp`
- `anim_tool_mcp.webp`
- `anim_tool_memory.webp`
- `anim_tool_ppt.webp`
- `anim_tool_search.webp`
- `anim_tool_sound.webp`
- `anim_tool_task.webp`
- `anim_tool_think.webp`
- `anim_tool_todo.webp`
- `anim_tool_web.webp`
- `animation_guesture_fling.webp`
- `animation_moon_dark_v2.webp`
- `animation_moon_light_v2.webp`
- `call_animated_box.apng`
- `icon_kimicall_avatar.webp`
- `light_call_animated_box.apng`
- `okc_helloworld.webp`
- `okc_loading_audio.webp`
- `okc_loading_default.webp`
- `okc_loading_image.webp`
- `okc_loading_jindutiao.webp`
- `okc_startingup.webp`

## 777 对照（基于代码文件与命名用途，待运行验收）

基线：777 主线 `59662deeb229af527cf4a9c8d319f943b8fd8e67`；#518 `8d5b8df83ef6d640fc737960e5e8065ba8534e72`。

Kimi 的 18 个 `anim_tool_*` 中，777 主线存在 **10 个对应命名用途动画**：`browser`、`code`、`create_subagent`、`file`、`image`、`mcp`、`search`、`task`、`think`、`web`。

**8 个未发现对应命名资源**：`ask_user`、`data`、`find_asset`、`location`、`memory`、`ppt`、`sound`、`todo`。这些缺口是文件命名比对结论；需按实际 UI 行为确认是否需要接入，不应直接判定功能缺失。

| 场景 | 777 对照代码 | 核查结论 |
|---|---|---|
| 主界面和侧栏 | LocalHarnessDrawer.kt / LocalHarnessDrawerComponents.kt | 布局/菜单/历史状态需真机核验 |
| Composer 和附件 | DsConversationComposer.kt / LocalConversationComposer.kt | 底层旧组件已改造；需核对 APK 输入状态 |
| Agent 集群 | LocalAgentTeamUi.kt | 专用组件存在，Rive 状态动画未见原件接入 |
| 工具执行 | LocalWorkProcessComponents.kt | 18 个工具动画中有 10 个同名用途文件 |
| 文件/项目/工作区 | LocalWorkspaceFilesDialog.kt | 文件图标及详情入口待逐项核对 |
| 模型选择 | LocalModelPickerSheet.kt | 视觉、滚动与选择行为待核验 |
| 设置及二三级页 | SettingsScreen.kt / AppSettingsComponents.kt | 仍处于 #517/#518 对齐过程 |
| 插件与技能 | ToolsScreen.kt / LocalToolsUiContribution.kt | 入口、列表、分类与反馈待核验 |
| 定时任务 | TasksScreen.kt / TaskEditorComponents.kt | 执行数据、状态面板、编辑保存待核验 |
| 基础弹层与控件 | DsBottomSheet.kt / DsButton.kt / GroupedSurfaces.kt | 主线保留大量 Ds 原组件 |
| 语音/媒体 | LocalConversationSurface.kt | 通话与加载状态按当前产品边界评估 |
| 内容渲染 | Markdown.kt / WorkContentBlock.kt | 在线组件与远端动态页面不属于纯 APK 离线内容 |

## 组件提取边界

APK 的 Compose UI 已编译至 3 个 DEX（类定义共 30501 项，其中大部分是第三方代码/混淆类）。已识别 38 个 Kimi 原包名类，可见 `MainActivity`、`KimiFilePreviewActivity`、`PullToRefreshElement`、`ScrollThumbElement` 等。**不能把 APK 的类索引视为恢复 Kotlin Compose 原始组件源码**。

### 后续验收要求

- [ ] 逐项验证原始图标与 777 图标是否同源、同色、同尺寸，不能仅按名字判定。
- [ ] 对 21 个 Rive 动画检查动画状态机、触发条件及移动端播放效果。
- [ ] 对 30 个 WebP/APNG 验证循环、帧速、尺寸和状态绑定。
- [ ] 对 Composer / Agent / Tool / File / Model / Persona 的状态组件做 APK 真机对照。
- [ ] 对侧栏、设置、插件、任务及二三级页验证字号、字重、间距、转场、手势、键盘行为。
- [ ] 形成资源—页面—代码调用的实证对应表，注明未覆盖的在线页面及外部功能。

**交付定位：** 完整逐文件清单、原始可提取资源、SVG 和 DEX 类索引在本次单独生成的 Excel 与 ZIP 中。公开仓库仅提交审计元数据，保留 777 原有能力与代码所有权。
