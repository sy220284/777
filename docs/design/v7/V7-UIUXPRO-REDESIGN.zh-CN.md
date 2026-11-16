# V7 · 用 ui-ux-pro-max 重设计的方向与令牌提案

> **性质：设计提案 + 技能检索记录，不是代码事实。** 事实底账见 [CURRENT-UI-INVENTORY.zh-CN.md](CURRENT-UI-INVENTORY.zh-CN.md)（逐条标 `文件:行号`）。
> **本文不改任何生产代码，也不改 [V7-VISUAL-LANGUAGE.zh-CN.md](V7-VISUAL-LANGUAGE.zh-CN.md) 与 [tokens.json](tokens.json)**（这两份由设计稿 Bot 持有）。差异在 §6 逐项列出，由主线 Bot 决定合并方式。
> **技能来源：** `ui-ux-pro-max` v2.15.0（npm `ui-ux-pro-max-cli`），本机技能目录 `~/.dsh/skills/ui-ux-pro-max`，数据 79 样式 / 192 配色 / 74 字体配对 / 119 UX 规则 / 22 技术栈。

---

## 1. 检索记录（可复现）

| # | 查询 | 结果 | 判定 |
|---|---|---|---|
| 1 | `--design-system "AI chat companion mobile app warm paper ink premium reading dark mode"` | Pattern=Trust & Authority；Style=Organic Biophilic；配色 `#059669` 绿；字体 Orbitron + JetBrains Mono | **跑偏**：给的是生物风 + 赛博字体，与本项目（中文、暖纸、阅读向）无关 |
| 2 | `--design-system "chat ai assistant reading app minimal editorial light dark"` | Style=**AI-Native UI**（Chatbot, conversational, ambient, minimal chrome, streaming text；Light/Dark 均支持）；Pattern=Product Demo + Features | **采纳为风格主轴**；Pattern（落地页结构）与原生 App 无关，不采纳 |
| 3 | `--domain style "warm neutral minimal glass editorial"` | Minimalism & Swiss Style（单色、留白、高对比、无阴影渐变） | **采纳为排版/表面原则**；玻璃作为次级风格保留在 L2/L4 |
| 4 | `--domain product "chat assistant ai productivity"` | AI/Chatbot Platform → AI-Native UI + Minimalism & Swiss；次级 Glassmorphism；配色焦点「Neutral + AI Purple #6366F1」 | 结构采纳，配色**不采纳**（见 §5） |
| 5 | `--domain typography "reading long form chat legible neutral"` | Spatial Clear（Inter）/ Minimal Swiss（Inter）/ Bold Typography Mobile / Academia Mobile | 采纳「几何无衬线 + 高可读」结论，**不换字体**（见 §5） |
| 6 | `--stack jetpack-compose "material elevation contrast touch target"` | 0 命中 | 按技能规则改写 |
| 7 | `--stack jetpack-compose "theming materialtheme"` | 3 条：不用硬编码 sp、走 `MaterialTheme.typography`；用 Material3 集中令牌不硬编码；用 `colorScheme` 不写死颜色 | **全采纳**，并直接命中本项目两处待改点 |
| 8 | `--domain ux "bottom sheet modal dismiss focus trap"` | 焦点态需可见指示器；焦点不得被浮层遮挡（Severe High/Medium） | 采纳 |
| 9 | `--domain ux "reduced motion animation duration exit faster than enter"` | 尊重减少动效（High）；时长按距离/复杂度/平台/上下文分级，不设万能值（Medium）；每屏最多动 1–2 个关键元素（High） | 采纳 |
| 10 | `--domain web "touch target size android 48dp"` | Android 48dp / iOS 44pt / Web 24px 不可混用（Critical）；相邻可点元素间距 ≥8dp（Medium） | 采纳 |
| 11 | `--domain ux "screen reader label semantics state"` | 状态不可仅靠颜色编码（High）；紧凑控件需原生角色、可访问名与 pressed/selected 状态（Critical） | 采纳 |

---

## 2. 技能给出的目标画像（与本项目交叉后的结论）

| 维度 | 技能结论 | 与本项目现状的关系 |
|---|---|---|
| 风格主轴 | AI-Native UI：minimal chrome、流式文本、环境式（ambient）层级 | 与现状「连续阅读流 + 语义材质」同向，但现状玻璃层数过多 |
| 排版原则 | Minimalism & Swiss：单色、留白、高对比、几何无衬线、**避免阴影与渐变** | 现状 L2/L4 同时有渐变描边 + 顶部高光 + 阴影，与该原则直接冲突 |
| 玻璃定位 | Glassmorphism 只是**次级**风格，不是主结构 | 现状把玻璃做成了主结构（L1–L4 全部半透明 + 实时模糊） |
| 表面层级 | 背景 / 栏 / 卡片 / 浮层靠明度与留白区分，非靠堆叠透明层 | 现状 L1–L4 靠 alpha 梯度区分 |
| 交互反馈 | 共享动效令牌、按上下文分级；每屏最多 1–2 个关键元素动画 | 现状令牌齐全但状态动效缺位（底账 §9-5） |
| 无障碍 | 48dp 目标 + 8dp 间距、状态不靠颜色单独表达、焦点可见、减少动效生效 | 48dp 已满足；状态用色 + 文字已满足；焦点/浮层遮挡未系统检查 |

---

## 3. 重设计提案 A：材质语法（L1–L5）

**目标：** 取消「层数 = 玻璃层数」的表达，改为「层数 = 信息密度」。

| 提案 | 内容 | 对应事实 |
|---|---|---|
| A1 取消 L2/L4 实时模糊 | 模糊只在**唯一**的一处存在：L4 浮层（可选，L5 不透明时为 0）；L2 改为不透明承载面 | 现状 L2=24dp、L4=32dp 实时模糊（底账 §2） |
| A2 L4 三变体 | 由 `L4_OVERLAY` 派生 `menu` / `sheet` / `dialog` 三个语义外观：阴影 8 / 12 / 16dp，圆角 16 / 20 / 24dp，内边距 16×12 / 20×16 / 24×20 | 现状 L4 一刀切 16dp + 浮层各自硬编码（底账 §6） |
| A3 边缘去重 | L2/L4 只保留一条 1dp 描边 + 一条顶部高光，二者不叠加；取消 L2 的三段渐变 rim | 现状 rim 渐变 + 高光线 + 阴影三重叠加（`ShenyanMaterial.kt:130-132, 152-165`） |
| A4 分层语义 | L1=留白/分隔（无材质）；L2=承载栏（不透明）；L3=内容卡（1dp 描边，0 阴影）；L4=浮层（唯一允许阴影与模糊）；L5=保底实心 | 现状 L1/L3 均为半透明（LIGHT 下 L1 α0.30 / L3 α0.64） |
| A5 减少透明度联动 | 「减少透明度」= L4/L3 整体不透明化，而非逐级替换 | 现状仅在 `ShenyanMaterial.kt:99` 对 L3 以下生效，壁纸路径未统一 |

---

## 4. 重设计提案 B：令牌具体差量

> 下表「提案值」为**新设计目标**，不是代码事实；落地需同步 `tokens.json` 与 Compose。

### 4.1 色（沿用三主题关系，收紧暖度与对比）

| 令牌 | 现状（事实） | 提案值 | 理由 |
|---|---|---|---|
| LIGHT `canvas` | `#EDE6DA` | `#F2EDE4` | 降低黄绿味，正文对比余量更大 |
| LIGHT `textTertiary` | `#70665A`（对画布 4.53:1） | `#6B6154` | 提到 ≈5.0:1，去掉贴线风险 |
| DARK `canvas` | `#24211E` | `#1B1917` | 与烟熏的「暖灰深底」目标一致 |
| MATTE `canvas` | `#0F0F10` | `#0D0D0E` | 哑光保持近黑纯色 |
| 三主题 `accent` | 暖杏 `#E8A87C` 系 | 保留暖杏，但**降饱和 + 提暗**（如 LIGHT `#9C4E1B` / DARK `#E08A4E`） | 技能：强调色是容器色，前景需按最终填充求；深色填充更利于白色前景稳定 |
| `onAccent` | 由单色求解（底账 §8.2） | **改为按「实际填充集合」求解**：填充集合 = {常态填充, 按下填充, 品牌渐变起点} | V6.4 待实现第 4 项（底账 §9-4） |

### 4.2 排版（收敛 sp 层级，中英混排）

| 提案 | 内容 | 对应事实 |
|---|---|---|
| B1 收敛到 5 级 + 2 辅助 | 主文 15/26sp（保持现状 `chatBody`）、次文 13/21sp、标题 20/28sp、大标题 26/34sp、辅助 11.5/19sp；代码 13/22sp | 现状 11.5 / 12 / 13 / 14 / 15 / 15.5 / 17 / 19 / 20 / 21 / 24 / 26 / 28 共 13 档（底账 §4） |
| B2 统一走 `MaterialTheme.typography` | 库层补 `typography` 覆盖，消除组件内 `fontSize` 直写 | 技能 #7；现状 `ShenyanTheme` 未覆盖 typography（底账 §11） |
| B3 中英混排规则 | 中文与 Outfit 基线差用行高吸收，不改字重；数字列用等宽 | 现状 `contentFont` 仅 Outfit + 系统回退（`Type.kt:31-37`） |

### 4.3 几何（8dp 节奏）

| 提案 | 现状 | 提案值 |
|---|---|---|
| 间距 | 2 / 6 / 10 / 14 / 22 / 34 / 54dp | 4 / 8 / 12 / 16 / 24 / 32dp |
| 圆角 | 9 / 13 / 18 / 24dp | 8 / 12 / 16 / 20dp（与 [tokens.json](tokens.json) 一致） |
| 点击区 | 48dp | 保持 48dp；相邻可点元素间距 ≥8dp（技能 #10） |
| 图标 | 无统一令牌（18/20/22dp 分散） | 新增 `iconSm=16 / iconMd=20 / iconLg=24`，点击区仍 48dp |

### 4.4 动效

| 提案 | 内容 | 对应事实 |
|---|---|---|
| C1 分级 | 反馈（按压/选中）≤150ms；容器切换 180–240ms；页面转场 240–280ms；退场时长 = 入场 ×0.6 | 技能 #9；现状入出场同值（`SyConversation.kt:212-215`） |
| C2 每屏 ≤2 个关键动画元素 | 工作状态仅当前步骤动；完成/失败立即收束 | 技能 #9；现状 `SyStatusLine` 纯静态（`SyControls.kt:102-115`） |
| C3 清理死令牌 | `toolDetailDelayMs`、`cascadeStaggerMs` 要么落地要么删除 | 底账 §5 使用点数 0 |
| C4 减少动效 | 保留全部 `SyMotion.duration` 入口；减少动效时状态语义不变 | 现状已满足 |

---

## 5. 技能建议中**不采纳**的部分及理由

| 技能建议 | 不采纳理由 |
|---|---|
| 配色用 Neutral + AI Purple `#6366F1` | 本项目品牌锚点是暖纸/暖杏；改紫会推翻既有三主题关系与八色卡体系。可借用的只是「中性底 + 单一强调」的结构 |
| 字体换 Inter / Playfair / Orbitron / JetBrains Mono | 仓库已内置 Outfit（几何无衬线，与 Inter 同族）+ Noto Sans SC 子集；换字体要重做 CJK 子集打包，收益为负 |
| 用 Phosphor / Heroicons / Radix | 本项目图标为自绘纸墨矢量（`docs/design/v6/PAPER-INK-ICONS.zh-CN.md`），不可用通用图标库替换 |
| `prefers-reduced-motion` / `cursor-pointer` / `:focus` 等 Web 写法 | Android 原生已有 `LocalSyReduceMotion`、TalkBack 与焦点系统 |
| 断点 375 / 768 / 1024 / 1440 与 `hover` 态 | 原生 App 无 hover；断点按 320 / 360 / 393 / 412dp + 600dp 平板档 |
| 落地页 Pattern（Hero / Testimonial / Pricing / CTA） | 与原生 App 无关 |

---

## 6. 与既有 V7 `tokens.json` 的差异（待主线决策）

| 项 | `tokens.json` 现状 | 本文提案 | 是否冲突 |
|---|---|---|---|
| 三主题 canvas | `#F2EDE4 / #1B1917 / #0D0D0E` | 同 | 否，一致 |
| LIGHT `ink3` | `#6F6659` | `#6B6154`（提高对比） | 是，提亮 |
| 强调色 | LIGHT `#9C4E1B` / DARK `#E08A4E` | 同 | 否，一致 |
| 几何 | 8 / 12 / 16 / 20dp + gap 4/8/12/16/24/32 | 同 | 否，一致（本文只是把「现状 2/6/10/14/22/34」列为目标差量） |
| 字阶 | 8 档，行高用倍率（1.25–1.65） | 5 级 + 2 辅助，行高用 sp 绝对值 | 是，需二选一 |
| 表面 L1/L2/L3 | 全不透明、0 模糊、0 阴影 | 同（A1/A4） | 否，一致 |
| L4 | opacity .92 + blur 20 + shadow 6 | 三变体（menu/sheet/dialog），阴影 8/12/16 | 是，需细化 |
| 动效 | 单一 easing + 11 个时长 | 分级 + 退场 0.6× | 部分一致 |

---

## 7. 落地顺序（对齐 [STATUS.zh-CN.md](STATUS.zh-CN.md) 阶段表）

| 阶段 | 内容 | 验收点 |
|---|---|---|
| P0 | 令牌落地：`tokens.json` → `SyColors` / `SyMetric` / `SyMotion`，主题可回退 | 令牌与文档三处一致，可自动校验 |
| P1 | 表面语法重写：A1–A5（取消 L2/L3 模糊、L4 三变体、边缘去重） | 三主题下 L1–L5 截图基线无「白板化」 |
| P2 | 排版系统、按钮三级、行式列表、设置分组（B1–B3、C3） | 320dp + 200% 字体下无截断、无竖排挤压 |
| P3 | HOME 聊天 / 工作重排 | 流式稳定、状态动效 ≤2 元素 |
| P4 | TOOLS / TASKS / WORKSPACE / PERSONA / SETTINGS 逐页改造 | 每页独立节奏，共享底层令牌 |
| P5 | 壁纸取色与最终合成校验、透明度安全下限、滑块真实反馈 | 纯白/纯黑/高饱和壁纸下正文 ≥4.5:1 且滑块可感知 |
| P6 | 自动回归：对比度矩阵、令牌同步校验、视觉冒烟 | 三主题 × 三级文字 × 八色卡矩阵进 CI |

---

## 8. 需要人工确认的开放问题

1. **暖杏强调色是否加深**：加深后白色前景稳定，但会改变「流体杏」的品牌观感；保持现值则需继续按填充动态求前景。
2. **L4 是否保留实时模糊**：技能把玻璃列为次级风格；完全取消会让「壁纸模式」的通透感消失，建议保留 L4 单点模糊。
3. **字阶用倍率还是绝对 sp**：`tokens.json` 用倍率，代码用绝对 sp；二者需统一，否则 CI 同步校验无法机械比对。
4. **图标尺寸令牌是否进 `SyMetric`**：现无统一令牌，影响 §3 的一致性判定。
5. **是否保留「无缝背景」双轨**：若 P1 统一 L4 外观，菜单/Sheet 的同源场景绘制模式需要保留还是废弃需定。
