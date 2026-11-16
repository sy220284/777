# 777 UI 视觉审计与升级方案

> 审计日期：2026-11-16
> 基线：main `4e2e208`（Merge PR #926 ui-full-review-20261116）/ 发布包 777.397
> 方法：四路并行只读代码审计（令牌与主题层 / 聊天·工作区 / 组件·次级界面 / 文档·历史台账）+ 关键论断抽样复核
> 说明：本轮**未修改任何产品代码**，也**未编译、未真机复验**（本机无 Android SDK，见文末"验证方式"）

---

## 一、为什么"看起来丑"：七条根因

| # | 根因 | 证据 |
| --- | --- | --- |
| 1 | **设计系统建好了却没人用**：10 个组件 0 调用，次级界面绕过材质层手画卡片 | `SyGroupCard` / `SyListItem` / `SyNote` / `SyFileRow` / `SyInput` / `SyModalSheet` / `SyConfirmDialog` / `SySwitch` / `SyLoadingStates` / `SyApproveCard` 在 `app/src/main/java/com/labteto/dshmobile/**` 的调用文件数**全为 0**（实测）；裸 `material3.Surface` **45 处**，而 `SyMaterialSurface`/`SyGlassCard` 只覆盖 17 个文件 |
| 2 | **令牌没被强制**：阶梯齐全，但代码大量写字面值 | 令牌本身完整（`ShenyanFoundation.kt:160-170` gap 2/6/10/14/22/34/54dp、radius 9/13/18/24dp），但 main 里仍有 **599 行**字面 dp（其中 `ui/**` 448 行）、**226 行**字面 `Color(0x…)`（其中 125 行是遗留调色板 `ui/theme/Color.kt:12-175`，**生产零引用**；其余 101 行散落各处）。例：`PluginInventoryBrowser.kt:732,786,850,942`、`ExtensionCatalogCard.kt:55` 直写 24/18/16.dp 绕过 `radiusCard` |
| 3 | **两套 Material3 配色方案并存**：默认色卡与换色卡走不同分支 | `ShenyanFoundation.kt:235-247` 的 scheme **未设** `outline`/`outlineVariant`/`surfaceTint`/`primaryContainer` → M3 基线紫可能从组件默认色漏出；`Theme.kt:216-250` 另一条 scheme 已设 `outline` → **换色卡改描边**，与默认观感不一致 |
| 4 | **遗留第二套调色板与圆角/字阶双轨** | `ui/theme/Color.kt:12-175`（125 个色值）生产零引用；暗色值自相矛盾（labelDimmed 冷灰 `#424A47`、toast/代码块冷灰 vs 暖画布）。圆角双轨：SyMetric 9/13/18/24 vs `DsShapes` 12/20/999（`Shape.kt:37-42` 零引用）。字阶名值不符：`title22=20sp`、`base16=15.5sp`、`caption11=11.5sp`（`Type.kt:58,64,71`） |
| 5 | **双轨系统并存**：`com.shenyan.designsystem`（新）＋ `ui/shenyan`（迁移外观层，91 文件引用）＋ 旧 `Ds` 色板 | `ui/shenyan/SyControls.kt:49` 再把组件转调回 designsystem；同一控件多处定义，层级与材质随页面而变 |
| 6 | **语言与层级不统一**：三套顶栏、四套分组标题、两套列表行、三套左右基准 | 聊天自绘人格头（`LocalConversationHeaders.kt:63`）/ 工作区居中 `SyTopBar`（`ShenyanChrome.kt:47`）/ 抽屉场景面（`LocalHarnessDrawer.kt:382`）；分组标题 4 套（`AppSettingsComponents.kt:52,61`、`PersonaGalleryOverviewComponentsV3.kt:348`）；列表行 56/64dp 与 48dp 两套（`AppSettingsComponents.kt:79` 26 处 vs `ShenyanCompound.kt:64` 6 处）；边缘基准 22dp（`LocalConversationSurface.kt:960`）/14dp（:1145-1153）/26dp（发送键）三套 |
| 7 | **状态靠中文文案判定 + 浮层一刀切 + 中文回落系统字体** | 状态色靠中文前缀匹配（`LocalConversationHeaders.kt:191-193`、`LocalHarnessMessageComponents.kt:301`），改文案即静默失效；`ShenyanMaterial.kt:124-129` 中 L4_OVERLAY 固定 16dp（V6.4 目标：菜单 8dp / Sheet 12dp / Dialog 16dp）；`Type.kt:31-37` `contentFont` 只挂 Outfit（拉丁），中文正文回落系统字体，与 UI 字体不是一套 |

**一句话**：不是配色难看，而是**同一视觉概念有 2–4 套实现**，界面由"每页自己画"堆出来——观感必然不统一。升级重点不是换皮，而是**收敛到单一事实源**。

---

## 二、问题清单（按域）

### 2.1 令牌与主题层
| 位置 | 现象 | 级别 |
| --- | --- | --- |
| `ShenyanMaterial.kt:124-129` | L4 菜单/Sheet/Dialog 共用单一 16dp 外观，V6.4 语义层级未落地 | P1 |
| `ShenyanMaterialColor.kt:15` | 平台控件取色默认中段（.5f），未按控件真实区域坐标采样 | P1 |
| `docs/design/v6/STATUS.zh-CN.md:34` | 玻璃透明度"最终合成后对比度"规范已定义、**代码待改** | P1 |
| `ShenyanFoundation.kt:235-247` vs `Theme.kt:216-250` | 两条 M3 scheme，默认分支缺 outline/surfaceTint/primaryContainer | P1 |
| `ui/theme/Color.kt:12-175`、`Theme.kt:22-199` | 遗留调色板 125 色值生产零引用，暗色值与暖画布自相矛盾 | P1 |
| 全库 | 599 行字面 dp / 101 行非遗留字面色；CI 无令牌比对门禁 | P1 |
| `ui/theme/Type.kt:31-37,58,64,71` | `contentFont` 无中文兜底；令牌名与实际值不符 | P2 |
| `ui/theme/Shape.kt:37-42`、`Theme.kt:394` | `DsMaterialShapes` 零引用、`shapes` 自引用；extraSmall/extraLarge 仍是 M3 默认 4/28dp | P2 |

### 2.2 聊天与工作区
| 位置 | 现象 | 级别 |
| --- | --- | --- |
| `LocalConversationComposer.kt:400-483`、`LocalConversationComposerActions.kt:55-66` | 聚焦后发送键跳到第二行；5 个 48dp 动作在 320dp 宽下需 296dp > 可用 268dp，末位被压到约 20dp，**触摸目标失守** | **P0** |
| `LocalConversationSurface.kt:960` vs `:1145-1153` | 左右边缘三套基准（22/14/26dp） | P1 |
| `LocalWorkspaceFilesDialog.kt:781-858` vs `LocalConversationHeaders.kt:63` | 工作区居中顶栏+搜索+溢出，与聊天左对齐头像头两套语言；工作区把 4/5 操作藏进 MoreVertical | P1 |
| `LocalWorkspaceFilesDialog.kt:1836,1849-1856` | 文件图标 38×50dp 彩色，与全站 24dp 线性图标不统一；路径+大小单行截断；分组头不吸顶 | P1 |
| `LocalHarnessDrawer.kt:581,608,893` | 抽屉混用 TextButton / SyCategoryRow / 自绘行三种行样式；分组标题随滚动消失 | P1 |
| `LocalConversationHeaders.kt:191-193`、`LocalHarnessMessageComponents.kt:301` | 状态色由中文前缀判定 | P1 |
| `LocalConversationHeaders.kt:73,90-91,188-196` | 人名 188dp 封顶 + maxLines=1，0.9–1.5 倍字号下必截断 | P1 |
| `LocalConversationSurface.kt:944-951` | 每次流式更新都 animateScrollToItem，读长回复抢滚动 | P2 |
| `Shape.kt:19-20` | bubble/characterBubble 尾巴圆角定义了但全项目 0 使用，气泡与设计稿不一致 | P2 |

### 2.3 组件与次级界面
| 位置 | 现象 | 级别 |
| --- | --- | --- |
| `.github/workflows/**`（零引用） | `check-ui-design-system-boundaries.py`、`audit-all-icons.py` 未接 CI；实测前者**失败**（离线原型与设计源分叉：PR #926 的原型改动未回填可编辑源），后者**通过**（128 项） | **P0** |
| `ShenyanFoundation.kt:268` / `SyControls.kt:92` | `SyPhase` 标签双源：硬编码中文 vs stringResource | P0 |
| `LocalAgentTeamUi.kt:155,550,813,1061`、`TaskCardComponents.kt:100` | 手写 Surface+BorderStroke，不走玻璃材质与自适应对比度 | P1 |
| `AppSettingsComponents.kt:79`（26 处）/ `ShenyanCompound.kt:64`（6 处） | 列表行两套：56/64dp 对 48dp、图标 23 对 20dp | P1 |
| `PluginInventoryBrowser.kt:732,786,850,942`、`ExtensionCatalogCard.kt:55`、`PersonaGalleryOverviewComponentsV3.kt:151` | 硬编码 24/18/16.dp 与 raw color，绕过 `radiusCard` | P1 |
| `docs/design/v6/assets/**/*.svg` | 描边 176×1.75 之外还有 8×2、4×3.2、2×3；portraits 缺 linejoin；icons 硬编码 `#302B27` | P2 |
| `app/build.gradle.kts:124-136` | 中文子集字体不入库，动态中文回落系统字体 | P2 |

### 2.4 文档与流程
| 位置 | 现象 | 级别 |
| --- | --- | --- |
| `docs/design/v6/STATUS.zh-CN.md:15` | PR #926 未编译、未设备复验（按指令跳过） | **P0** |
| `docs/design/v6/STATUS.zh-CN.md:37` | 文档参数与实现分离，CI 令牌比对门禁待扩展 | P1 |
| `CHANGELOG.md:6,63` vs `.github/release-version:1` | CHANGELOG 停在 0.11.4，PR #926 无记录 | P2 |

---

## 三、升级方案（分四批，每批可独立评审）

### 批次 0 · 基线（前置，不改产品代码）
1. 补 777.397 的**编译 + 真机视觉基线**：现有 4 个 `*VisualTest*` 与 `ShenyanReadableInkTest` 全量跑通并留存截图；矩阵 320/360/393/412dp × 暖纸/烟熏/哑光 × 默认/放大字体。
2. 收益：PR #926 之后第一次有可验证起点——**没有这条，后面每一批视觉改动都无法证明变好了**。
3. 交付物：PR（CI 配置与基线脚本 + 台账回写）。

### 批次 1 · 收敛一致性（把"丑"的根因拆掉）
| 改动 | 文件 | 说明 |
| --- | --- | --- |
| 接线 CI 门禁 | `.github/workflows/`、`.github/scripts/check-ui-design-system-boundaries.py`、`audit-all-icons.py` | 门禁先接上并修到绿，再新增"禁裸 Surface / 禁字面圆角"规则 |
| `SyPhase` 标签单一来源 | `ShenyanFoundation.kt:268`、`SyControls.kt:92` | 删硬编码中文，统一 stringResource |
| 统一 M3 scheme | `ShenyanFoundation.kt:235-247`、`Theme.kt:216-250,389-392` | 默认分支补 outline/outlineVariant/surfaceTint/primaryContainer，两条路径共用一套 → 换色卡不再改描边 |
| 删遗留调色板 | `ui/theme/Color.kt:12-175`、`Theme.kt:22-199` | 生产零引用，直接删（legacy-theme-consumers.json 已声明基线为空） |
| 边缘基准统一 14dp | `LocalConversationSurface.kt:960,1145-1153`、`SyConversation.kt:137,148` | 顶栏/列表/气泡同一基准 |
| 状态色改枚举判定 | `LocalConversationHeaders.kt:191`、`LocalHarnessMessageComponents.kt:301` | 不再靠中文前缀 |
| 输入区触摸目标 | `LocalConversationComposer.kt:400-483`、`LocalConversationComposerActions.kt:55-66` | FlowRow 或次要动作收入"更多"，320dp 下每项 ≥48dp |
| 子代理/任务卡走材质层 | `LocalAgentTeamUi.kt:155,550,813,1061`、`TaskCardComponents.kt:100` | 换 `SyMaterialSurface`/`SyGlassCard` |
| 设置/图集归并 | `AppSettingsComponents.kt:52,61,79`、`PersonaGalleryOverviewComponentsV3.kt:348`、`ShenyanCompound.kt:64` | 统一到 `SySectionHeader` + `SyCategoryRow` |
| 工作区文件行 | `LocalWorkspaceFilesDialog.kt:1836,1849-1856` | 24dp 线性文件图标 + 路径两行 |

### 批次 2 · 材质与观感
1. **L4 语义化**：菜单 8dp / Sheet 12dp / Dialog 16dp 落进 `ShenyanMaterial.kt`（V6.4 已定义）。
2. **取色改真实区域**：`ShenyanMaterialColor.kt:15` 去掉 .5f 默认，用控件实际坐标采样。
3. **玻璃合成后对比**：把 STATUS 里"规范已定义、代码待改"的透明度目标落到代码，并在最终合成像素上断言对比度。
4. **中文字体兜底**：`ui/theme/Type.kt:31-37` 给 `contentFont` 挂中文子集；同步 `app/build.gradle.kts:124-136` 的字体生成。
5. **圆角双轨收敛**：`DsShapes` 12/20/999 改引 `SyMetric.radius*`；`DsMaterialShapes` 接入 `MaterialTheme.shapes` 或删除。
6. **字阶对齐**：令牌名与实际值对齐（`title22=20sp` 等），正文层级统一。
7. **气泡尾巴**：`Shape.kt:19-20` 启用或删除（当前是死代码）。
8. **流式滚动**：改为"用户离底才回底"（`LocalConversationSurface.kt:944-951`）。

### 批次 3 · 质感与一致性收尾
- 图标描边归一 1.75 + currentColor，portraits 补 linejoin，接入 `audit-all-icons.py`。
- 材质光线层次与场景动效（V6.4 P2 项）。
- `CHANGELOG.md` 与 STATUS 台账回写 PR #926 记录。

---

## 四、验证方式与约束

- **本机没有 Android SDK**（`ANDROID_HOME` 未设置），手机上无法本地编译。可用路径：开 PR 触发 CI（`.github/workflows/runtime-user-journey.yml` 对 `pull_request` 生效；`release.yml` 仅在 push 到 main 时出包）。
- 仓库硬性要求（AGENTS.md / docs/design/v6）：
  - 只改全局来源（`ShenyanFoundation` / `ShenyanMaterial` / `ShenyanMaterialColor`），禁止页面自造色板与曲线；
  - 改入口或统一视觉 Token **必须同步**：STATUS 台账 + 现行规范 + `current-ui.json` + 离线原型；
  - 验收矩阵：320/360/393/412dp × 暖纸/烟熏/哑光 × 默认/放大字体；
  - 仅"美化局部"不足以宣称优化完成；
  - 多 PR 按依赖推进，横切改动必须回到最新主线复验；
  - PR 标题与描述用中文。
- 仓库暂无截图金像基建（仅 4 个 androidTest `*VisualTest*` + 1 个 `ShenyanReadableInkTest`），批次 0 建议补最小金像集。

---

## 四点五、本轮已落地（批次 1 第一项）

- 新增 `.github/workflows/ui-visual-gates.yml`：PR 上依次执行离线原型同源、设计系统边界、V6.3 图标契约三项检查（此前无任何工作流执行它们）。
- 修复门禁红灯根因：把 PR #926 的三处原型改动回填到可编辑源 `mobile/v6.css` / `mobile/v6.js`（分段控件滚动规则追加在样式表末尾以保持层叠顺序），`current-ui.json` 的 `baselineCommit` 更新为 `4e2e208`，离线页由 `build-standalone.py` 重新生成——**#926 的原型改进被完整保留，没有被回退**。
- 三项门禁本地全部通过；台账见 `docs/design/v6/STATUS.zh-CN.md` 的同日小节。

## 五、建议的第一刀

先做 **批次 0**，紧接着做 **批次 1 的前三项**（CI 门禁 + SyPhase 标签 + 统一 M3 scheme / 删遗留调色板）。理由：风险最低、收益立刻可见，且能把后续每一批"变好看"都变成可验证的事实，而不是主观判断。
