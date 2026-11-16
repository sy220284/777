# 777 UI 视觉审计与升级方案（rev 2 · 叠加态评估）

> 审计日期：2026-11-16（rev 1：仅基于 main；rev 2：改为**主线 + 开放 PR 叠加**重评）
> 评估基线：main `4e2e208`（含 PR #926）**＋ PR #932 ＋ PR #933 ＋ PR #934** 在本地合并出的叠加态 `eval/stacked`
> 叠加结果：三个 PR 在 main 上**零冲突**合并，合计 69 文件 / +2123 / −245
> 方法：四路并行只读代码审计 ＋ 关键论断抽样复核 ＋ **叠加态重算指标**
> 说明：本轮**未修改产品代码**，也**未编译、未真机复验**（本机无 Android SDK）

---

## 一·零、评估口径：为什么必须叠加

在途 PR 会实质改变"当前界面"的真相，单独看 main 会得出错误结论：

| 开放 PR | 性质 | 对 UI 的影响 |
| --- | --- | --- |
| **#934** `codex/ui-v6-unified-ux-20261116` | **界面整改 + 验收设施**（V6.5） | 已改：技能详情透底、技能目录、工作区操作入口、附件面板层级、群聊空状态、价格计量单位、运行策略文案；并新增 `tools/ui/device_video.py` 分段录屏证据、`run-native-ui-audit.py` 巡检增强、"已发布且校验过的 **777.400** 正式包"作为 UI 巡检基线 |
| #933 `fix/anthropic-tool-json-continuation-20261116` | 模型协议 | 触及 25 个 `ui/` 文件（用量、设置、任务展示） |
| #932 `audit/post930-direct-history-retention-20261116` | 工具链精简 | 触及 12 个 `ui/` 文件 |

因此本审计的全部结论都在 `eval/stacked` 上**重新测量**，而不是沿用 main 的数字。

---

## 一、为什么"看起来丑"：七条根因（叠加态复核后仍成立）

| # | 根因 | 证据（**叠加态实测**） |
| --- | --- | --- |
| 1 | **设计系统建好了却没人用** | `SyGroupCard` / `SyListItem` / `SyNote` / `SyFileRow` / `SyInput` / `SyModalSheet` / `SyConfirmDialog` / `SySwitch` / `SyLoadingStates` / `SyApproveCard` 调用数**仍全为 0**；裸 `material3.Surface` **45 处** |
| 2 | **令牌没被强制** | main 里 **603 行**字面 dp、**226 行**字面 `Color(0x…)`（其中 125 行是遗留调色板 `ui/theme/Color.kt:12-175`，生产零引用） |
| 3 | **两套 Material3 配色并存** | `ShenyanFoundation.kt:235-247` 未设 `outline`/`outlineTint`/`surfaceTint`/`primaryContainer` → M3 基线紫可能漏出；`Theme.kt:216-250` 另一条 scheme 已设 `outline` → 换色卡改描边 |
| 4 | **遗留调色板与圆角/字阶双轨** | 遗留调色板 125 色值生产零引用；圆角 SyMetric 9/13/18/24 vs `DsShapes` 12/20/999；字阶名值不符（`title22=20sp`、`base16=15.5sp`、`caption11=11.5sp`） |
| 5 | **双轨系统并存** | `com.shenyan.designsystem` ＋ `ui/shenyan` 迁移外观层（91 文件引用）＋ 旧 `Ds` 色板，同一控件多处定义 |
| 6 | **语言与层级不统一** | 三套顶栏、四套分组标题、两套列表行（56/64dp vs 48dp）、三套左右基准（22/14/26dp） |
| 7 | **状态靠中文文案判定 ＋ 浮层一刀切 ＋ 中文回落系统字体** | 叠加态实测仍为 `statusText.startsWith("执行异常")` 式判定（`LocalConversationHeaders.kt`）；抽屉仍混用 `TextButton`(581/608/893) 与 `SyCategoryRow`(483/500/513)；L4 浮层仍固定 **16.dp**（`ShenyanMaterial.kt:128`）；`syMaterialColor` 仍默认 `regionY = .5f`（:15）；`contentFont` 仍只有 Outfit（`Type.kt:31-37`） |

**结论**：#934 解决的是**具体症状**（透底、藏太深、层级缺失），没有触及**结构性根因**——同一视觉概念仍有 2–4 套实现。两者不冲突，缺一不可。

---

## 二、叠加态下的覆盖关系（实测）

### 2.1 已被在途 PR 覆盖 → 本审计不再重复建议

| 项 | 覆盖方 | 落点 |
| --- | --- | --- |
| 全屏技能详情透底、技能目录用途与状态、工作区"文件操作"入口、附件面板层级、群聊空状态、价格单位、运行策略文案 | #934 | V6.5 台账 `docs/design/v6/V6.5-UI-UX-VIDEO-REMEDIATION-20261116.md` |
| 分段录屏与视频证据、原生页面巡检增强、777.400 校验基线 | #934 | `tools/ui/device_video.py`、`tools/ui/run-native-ui-audit.py` |
| 系统返回顺序、侧栏头像弹层残留、任务返回与用量布局 | #934 | 多个 `ui/screens/**` |

### 2.2 在途 PR 未覆盖 → 本审计结论全部保留

七条根因在叠加态逐条复测后**数值基本不变**（见第一节证据列）：零调用组件仍 0、裸 Surface 仍 45、字面 dp 由 599 升到 603、遗留调色板仍零引用、L4 仍 16dp、取色仍 .5f、中文字体仍回落。

**且离线原型同源门禁在叠加态依然是红的**：

```
python3 docs/design/v6/build-standalone.py --check
→ V6 compiled offline page drifted from editable source; regenerate
```

根因：PR #926 的原型改动只落在编译产物 `mobile/app.html`，未回填可编辑源 `mobile/v6.css` / `mobile/v6.js`；在途 PR 只新增 V6.5 台账，未触碰这三个文件（实测：无冲突面）。

### 2.3 重叠与依赖（冲突面已核对）

| 我审计引用的文件 | 在途 PR 是否改动 | 结论 |
| --- | --- | --- |
| `LocalConversationComposer.kt` / `LocalWorkspaceFilesDialog.kt` / `LocalConversationHeaders.kt` / `LocalHarnessMessageComponents.kt` / `ui/theme/{Type,Color,Shape}.kt` | **否** | 可直接叠加，无冲突 |
| `LocalConversationSurface.kt` / `LocalHarnessDrawer.kt` | **是**（#934） | 依赖 #934 合并后再落地，否则语义冲突 |
| `docs/design/v6/{current-ui.json,mobile/*,STATUS.zh-CN.md}` | **否**（三个 PR 只新增 V6.5 台账） | 可直接叠加 |

---

## 三、升级方案（重排为**叠加栈**，按依赖顺序）

| 序 | PR | 内容 | base（叠加） | 冲突面 |
| --- | --- | --- | --- | --- |
| **S1** | 视觉门禁 ＋ 原型同源 | 新增 `.github/workflows/ui-visual-gates.yml`（原型同源 / 设计系统边界 / 图标契约三项）；把 #926 的原型改动回填 `v6.css`/`v6.js`（滚动规则追加在样式表末尾以保持层叠顺序），`current-ui.json` 的 `baselineCommit` 更新为含 #926 的主线提交，离线页重新生成；同步 STATUS 台账 | **#934** | 无（三个 PR 未碰这些文件） |
| **S2** | 主题层收敛 | `SyPhase` 标签统一到 stringResource；统一 M3 scheme（`ShenyanFoundation.kt:235-247` 补 outline/surfaceTint/primaryContainer）；删生产零引用的遗留调色板 `ui/theme/Color.kt:12-175`；`DsShapes` 改引 `SyMetric.radius*` | **S1** | 无 |
| **S3** | 聊天/工作区细节 | 输入区 48dp 触摸目标（`LocalConversationComposer.kt:400-483`）；边缘基准统一 14dp；状态色改枚举判定；工作区 24dp 线性文件图标 ＋ 路径两行；抽屉行样式归一 | **#934 ＋ S2** | 与 #934 重叠（Surface / Drawer） |
| **S4** | 设计系统组件归位 | 把 10 个零调用组件按面归位（先 `SyListItem`/`SyGroupCard` → 设置与图集；再 `SyModalSheet`/`SyConfirmDialog`/`SySwitch`），消除裸 `material3.Surface` | **S3** | 大面积，逐面推进 |
| **S5** | 材质与质感 | L4 语义化（菜单 8dp / Sheet 12dp / Dialog 16dp）；取色改真实区域坐标；玻璃合成后对比度断言；中文正文兜底字体；字阶名值对齐；气泡尾巴；流式滚动"离底才回底" | **S4** | 全局令牌，回主线复验 |
| **S6** | 质感收尾 | 图标描边归一 1.75 ＋ currentColor；场景动效；台账与 CHANGELOG 回写 | **S5** | — |

S1 的实际内容与验证结果见同分支提交 `68053f0eb`。

---

## 四、验证方式与约束

- **本机没有 Android SDK**（`ANDROID_HOME` 未设置），本地无法编译。可用路径：开 PR 触发 CI（`runtime-user-journey.yml` 对 `pull_request` 生效；#934 引入的原生巡检与录屏证据可承接设备侧验收）；`release.yml` 仅在 push 到 main 时出包。
- 仓库硬性要求（AGENTS.md / docs/design/v6）：
  - 只改全局来源（`ShenyanFoundation` / `ShenyanMaterial` / `ShenyanMaterialColor`），禁止页面自造色板与曲线；
  - 改入口或统一视觉 Token **必须同步**：STATUS 台账 + 现行规范 + `current-ui.json` + 离线原型；
  - 验收矩阵：320/360/393/412dp × 暖纸/烟熏/哑光 × 默认/放大字体；
  - 仅"美化局部"不足以宣称优化完成；
  - 多 PR 按依赖推进，横切改动必须回到最新主线复验；
  - PR 标题与描述用中文。

---

## 五、对上一版（rev 1）的两处修正

1. **"PR #926 未编译未设备复验"** —— 表述不完整。#926 本身确实只做了源码审阅，但 **#934 已把 UI 巡检基线修正为"已发布且校验过的 777.400 正式包"**，并引入分段录屏与视频证据；因此"缺基线"应表述为"基线设施正在 #934 落地，本审计的门禁接线应与它叠加，而不是重复造"。
2. **"两个门禁脚本实测已失败"** —— 实测不准确：`audit-all-icons.py` **通过**（128 项）；失败的是 `check-ui-design-system-boundaries.py`，且根因是离线原型与设计源分叉，不是脚本本身有问题。
