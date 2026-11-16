# 777 UI 视觉问题处理方案

> 日期：2026-11-16
> 配套审计：`docs/audits/2026-11-16-777-ui-visual-audit-and-upgrade.zh-CN.md`（rev 2，叠加态基线）
> 叠加基线：main `4e2e208` ＋ PR #932 ＋ #933 ＋ #934（本地零冲突合并，69 文件 / +2123 / −245）
> 适用范围：只改全局来源（`ShenyanFoundation` / `ShenyanMaterial` / `ShenyanMaterialColor`），页面层只做"接线"，不自造色板与曲线

---

## 〇、目标观感（可判定的标准）

> **一句话**：让"同一个视觉概念只有一套实现"，页面只负责内容与信息层级。

验收时逐条能截图核对：

| # | 标准 | 判定方式 |
| --- | --- | --- |
| G1 | 任取两个页面的**卡片、列表行、按钮、浮层**，肉眼可判定为同一套 | 截图并排比对 |
| G2 | 界面里**没有** M3 默认色（基线紫）残留 | 全量截图取色审计 + `colorScheme` 角色全覆盖断言 |
| G3 | 同一层级的标题/正文/辅助文字**只有一套字号与行高** | `DsType` 令牌名值一一对应 |
| G4 | 中文正文与 UI 同族字体 | 混排截图无跳字 |
| G5 | 所有可点元素 **≥48dp** | 320dp 宽 + 最大字号下的原生巡检 |
| G6 | 状态颜色**不依赖文案** | 文案改成任意文字，状态色不变 |

---

## 一、六层处理清单

### L1 颜色与材质（根因 3 / 4 / 7）

| 现状 | 处理 | 涉及文件 |
| --- | --- | --- |
| 两条 M3 scheme：默认色卡走 `ShenyanFoundation.kt:235-247`（**缺** outline / outlineVariant / surfaceTint / primaryContainer），换色卡走 `Theme.kt:216-250`（只补了 outline） | **单一构造器**：新增 `syColorScheme(colors: SyColors, mode: ShenyanMode): ColorScheme`，输入只有 SyColors；默认色卡与换色卡都调它 | `designsystem/foundation/ShenyanFoundation.kt`、`ui/theme/Theme.kt` |
| 缺角色导致 M3 默认紫漏出 | 补齐 `outline`（= `colors.border`）、`outlineVariant`（border 半透明度）、`surfaceTint`（= `colors.canvas`，去紫）、`primaryContainer`（= `colors.selected`）、`onPrimaryContainer`（= `colors.text`）、`scrim`（= `colors.overlay`）、`inverseSurface`/`inverseOnSurface` | 同上 |
| 遗留调色板 `ui/theme/Color.kt:12-175`（125 色值）**并非零引用**：`DsColors` 被 4 个文件消费（`AccentTheme.kt`、`AppBackground.kt`、`ShenyanColorHarmony.kt`、`Theme.kt`，共 18 处） | **先迁消费再删**：4 个文件改为直接消费 SyColors（`harmonizeV6` / `withV6Accent` 已是现成桥），迁完再删 `Color.kt` 与 `DsColors` | `ui/theme/{Color,Theme,AccentTheme,AppBackground,ShenyanColorHarmony}.kt` |
| 暗色遗留值用冷灰（labelDimged `#424A47`、toast / 代码块冷灰）与暖画布冲突 | 迁消费时统一到 SyColors 的暖灰阶（`textSecondary` / `textTertiary`） | 同上 |

### L2 字阶与中文字体（根因 4 / 7）

| 现状 | 处理 | 涉及文件 |
| --- | --- | --- |
| 令牌名值不符：`title22=20sp`、`base16=15.5sp`、`caption11=11.5sp` | 令牌名与实际值对齐（改名，不改值，避免影响既有排版） | `ui/theme/Type.kt` |
| `contentFont` 只挂 Outfit（拉丁），中文回落系统字体 → 中英混排跳字 | `contentFont` 补中文子集（与 `uiFont` 同源 `ui_noto_sc_*`），并让构建把中文子集产物纳入字体流程 | `ui/theme/Type.kt`、`app/build.gradle.kts` |

### L3 间距与圆角（根因 2 / 6）

| 现状 | 处理 | 涉及文件 |
| --- | --- | --- |
| 圆角双轨：SyMetric 9/13/18/24 vs `DsShapes` 12/20/999（`Shape.kt:37-42` 零引用、`shapes` 自引用） | `DsShapes` 改引 `SyMetric.radius*`；`DsMaterialShapes` 接入 `MaterialTheme.shapes` 或删除 | `ui/theme/{Shape,Theme}.kt` |
| 左右边缘三套基准：22dp（顶栏）/ 14dp（列表与气泡）/ 26dp（发送键） | 统一 **14dp**；顶栏与列表共用一个常量 | `ui/screens/local/LocalConversationSurface.kt`、`ui/shenyan/SyConversation.kt` |
| 603 行字面 dp、226 行字面色（含遗留调色板 125 行） | 门禁**棘轮**式收紧：先记录当前基线值，之后只许减少不许增加 | `.github/scripts/check-ui-design-system-boundaries.py` |

### L4 组件归位（根因 1 / 5）

| 现状 | 处理 | 涉及文件 |
| --- | --- | --- |
| 10 个设计系统组件 **0 调用**（SyGroupCard / SyListItem / SyNote / SyFileRow / SyInput / SyModalSheet / SyConfirmDialog / SySwitch / SyLoadingStates / SyApproveCard） | 分批接线，优先级按复用面：①设置与人物图集 → SySectionHeader + SyCategoryRow + SyListItem；②子代理/任务卡 → SyMaterialSurface / SyGlassCard；③确认与开关 → SyConfirmDialog / SySwitch / SyModalSheet | `ui/screens/**` |
| 裸 `material3.Surface` **45 处** | 逐面替换为 Sy 组件，棘轮式把 45 往下压 | 同上 |
| 分组标题 4 套、列表行 2 套（56/64dp vs 48dp，图标 23 vs 20dp） | 统一到 `SySectionHeader` + `SyCategoryRow` | `ui/screens/settings/AppSettingsComponents.kt`、`ui/screens/local/PersonaGalleryOverviewComponentsV3.kt`、`designsystem/components/ShenyanCompound.kt` |

### L5 交互与状态（根因 7）

| 现状 | 处理 | 涉及文件 |
| --- | --- | --- |
| 状态色靠中文前缀（`statusText.startsWith("执行异常")`） | 改用 phase 枚举判定，标签与颜色解耦 | `ui/screens/local/LocalConversationHeaders.kt`、`LocalHarnessMessageComponents.kt` |
| 输入区聚焦后发送键跳到第二行；320dp 下 5 个 48dp 动作需 296dp > 可用 268dp，末位被压到约 20dp | FlowRow 自适应；次要动作收进"更多"；保证每项 ≥48dp | `ui/screens/local/LocalConversationComposer.kt`、`LocalConversationComposerActions.kt` |
| 抽屉混用 `TextButton`(581/608/893) 与 `SyCategoryRow`(483/500/513)；分组标题随滚动消失 | 行样式统一；分组标题吸顶 | `ui/screens/local/LocalHarnessDrawer.kt`、`LocalHarnessDrawerComponents.kt` |
| 流式更新每次 `animateScrollToItem`，读长回复抢滚动 | 改为"用户离底才回底" | `ui/screens/local/LocalConversationSurface.kt` |
| 工作区文件图标 38×50dp 彩色，与全站 24dp 线性图标不统一；路径单行截断 | 24dp 线性文件图标；路径两行 | `ui/screens/local/LocalWorkspaceFilesDialog.kt` |

### L6 材质细节与质感（根因 3 / 7）

| 现状 | 处理 | 涉及文件 |
| --- | --- | --- |
| L4 浮层一刀切 16dp（`ShenyanMaterial.kt:128`） | 语义化：菜单 8dp / Sheet 12dp / Dialog 16dp | `designsystem/components/ShenyanMaterial.kt` |
| 平台控件取色固定中段（`ShenyanMaterialColor.kt:15` 默认 `regionY = .5f`） | 改用控件真实区域坐标采样；玻璃合成后在最终像素上断言对比度 | `designsystem/components/ShenyanMaterialColor.kt` |
| 气泡尾巴圆角定义了但 0 使用（`Shape.kt:19-20`） | 启用或删除（不留死代码） | `ui/theme/Shape.kt` |
| SVG 描边不统一（176×1.75 之外还有 8×2、4×3.2、2×3）；icons 硬编码 `#302B27` | 归一 1.75 + currentColor；接入 `audit-all-icons.py` | `docs/design/v6/assets/**` |

---

## 二、执行序列（叠加栈）

| 序 | PR | 内容 | base | 与在途 PR 的冲突面 |
| --- | --- | --- | --- | --- |
| S1 | 视觉门禁 + 原型同源 | 三项门禁接线 ＋ 回填 #926 原型改动 | #934 | 无 |
| **S2** | **L1 + L2** | 单一 M3 scheme 构造器；迁 `DsColors` 消费后删遗留调色板；字阶名值对齐；中文正文兜底字体 | S1 | 无（#932/#933/#934 未碰主题层） |
| S3 | L3 + L5 | 边缘基准 14dp；状态色改枚举；48dp 触摸目标；抽屉/工作区行样式与图标 | #934 + S2 | 与 #934 重叠（Surface、Drawer） |
| S4 | L4 | 设计系统组件分批接线，裸 Surface 45 → 0 | S3 | 大面积，逐面推进 |
| S5 | L6 | 材质语义化、真实区域取色、中文兜底落地、图标描边归一 | S4 | 全局令牌，回主线复验 |
| S6 | 收尾 | 棘轮门禁全量启用、CHANGELOG 与台账回写 | S5 | — |

**每步的完成判据**：门禁三项绿 ＋ PR CI 绿 ＋ #934 的原生巡检/录屏证据通过 ＋ 台账同步。

---

## 三、门禁策略（棘轮）

现有 `check-ui-design-system-boundaries.py` 是"窄棘轮"风格，沿用同样做法：

1. **先记录基线**：把 裸 `material3.Surface` 45、字面 dp 603、字面色 101（不含遗留调色板）、`DsColors` 18 等指标写进脚本作为上限；
2. **只许下降**：每次改动让上限减少，超出即失败；
3. **新增禁止项**：裸 `Surface`、字面圆角、`DsColors` 新增引用、状态色靠文案判定；
4. 与 `audit-all-icons.py`、原型同源检查一起跑在 `ui-visual-gates.yml`。

---

## 四、风险与回退

| 风险 | 处理 |
| --- | --- |
| 本机无 Android SDK，改动无法本地编译 | 每个 PR 只做一类改动，靠 CI + 原生巡检验证；不合并未过 CI 的 PR |
| 删遗留调色板可能误伤隐藏引用 | 先迁 4 个消费点，门禁加"新增 `DsColors` 引用即失败"，迁完再删 |
| 与在途 PR 语义冲突（Surface / Drawer） | 相关改动一律排在 #934 之后（S3 起） |
| 一次性大面积换组件导致回归面过大 | L4 按面分批，每批一个 PR，附前后截图 |
| 观感提升无法量化 | 批次 0 已在 #934 落地（777.400 校验基线 + 分段录屏），本方案的所有"变好看"都以截图证据交付 |

---

## 五、对审计结论的一处修正

审计 rev 1/rev 2 写"遗留调色板生产零引用"——**不准确**。实测 `DsColors` 有 4 个消费文件、18 处引用（均在主题层），并通过 `harmonizeV6` / `withV6Accent` 与 V6 调色板互通。
因此处理方案不是"直接删"，而是 **先迁消费、再删遗留**，这一顺序已写入 L1。
