# V7 · 现有视觉系统真实值底账（只读审计）

> **性质：代码事实快照，不是设计目标。** 全部数值抄自生产 Compose 源码，标注 `文件:行号`；读不到的一律写「未找到」，不做推测。
> **审计范围：** `com.shenyan.designsystem`（唯一视觉库）、`ui/theme`（主题 / 壁纸 / 强调色）、`ui/shenyan`（迁移代理层）。
> **审计日期：** 2026-11-16　**对应实现基线：** 与 [V6 当前实现](../v6/V6-CURRENT-IMPLEMENTATION.zh-CN.md) 同源（main `73d86a8d` + PR #926）。
> **本文不改动任何生产代码**，只作为 V7 重设计的取值依据。与 [V7 视觉语言](V7-VISUAL-LANGUAGE.zh-CN.md) 的差异请以代码为准。
> **与当前设计方向的关系：** 本文全部数值仍是「当前生产实现」的真实值。V7 / V8 两轮视觉方向已按用户要求整体清理；现行唯一有效的设计方向是 [V9](../v9/V9-DESIGN.zh-CN.md)，其令牌在 `docs/design/v9/tokens.json`（64 项校验通过），Android 实现尚未开始——本文与 V9 目标的差距即 V9 路线图的施工量。

---

## 1. 三主题真实色值（`SyColors`）

来源：`app/src/main/java/com/shenyan/designsystem/foundation/ShenyanFoundation.kt`

| 令牌 | 暖纸 LIGHT（:87-97） | 烟熏 DARK（:98-107） | 哑光 MATTE（:108-117） | 用途 |
|---|---|---|---|---|
| `canvas` | `#FFEDE6DA` | `#FF24211E` | `#FF0F0F10` | 根画布底色 |
| `topBar` | `#FFF6F1E9` | `#FF292521` | `#FF18181A` | 顶栏后备色（不是独立绘制层） |
| `materialL1` | `#4DEDE6DA` | `#1A302B27` | `#FF181819` | L1 分区 |
| `materialL2` | `#75F1E8DC` | `#29302B27` | `#FF1E1E20` | L2 栏 / 输入承载 |
| `materialL3` | `#A3F1E8DC` | `#4035302B` | `#FF232324` | L3 卡片 |
| `materialL4` | `#E0F1E8DC` | `#E0312B26` | `#FF29292B` | L4 浮层 |
| `materialL5` | `#FFF1E8DC` | `#FF35312D` | `#FF29292B` | L5 实心保底 |
| `materialSheen` | `#66F6F0E6` | `#29FFF1DE` | `#0EFFFFFF` | 边缘高光 / 顶部光线 |
| `surface` | `#A3F1E8DC` | `#4035302B` | `#FF232324` | 与 L3 同值 |
| `surfaceRaised` | `#75F1E8DC` | `#29302B27` | `#FF1E1E20` | 与 L2 同值 |
| `surfaceSolid` | `#FFF1E8DC` | `#FF35312D` | `#FF29292B` | 与 L5 同值 |
| `border` | `#66D3C5B5` | `#21FFFFFF` | `#1AFFFFFF` | 描边 |
| `text` | `#FF3A342D` | `#FFF5F0E8` | `#FFF5F0E8` | 主文字 |
| `textSecondary` | `#FF6A6156` | `#FFD9D0C5` | `#FFD9D0C5` | 次文字 |
| `textTertiary` | `#FF70665A` | `#FFB8AB9B` | `#FFB8AB9B` | 三级文字 / 占位 |
| `selected` | `#FFF2C7A8` | `#FF60422F` | `#FF573F30` | 选中态底色 |
| `overlay` | `#335A4837` | `#5C000000` | `#99000000` | 遮罩 / scrim |

### 1.1 全局共享色（不属于任何单一主题，改主题不变）

| 令牌 | 值 | 来源 | 用途 |
|---|---|---|---|
| `primary` | `#FFE8A87C` | :39 | 主交互色（被用户色卡覆盖，见 §8） |
| `primaryPressed` | `#FFD9926B` | :40 | 按下态 |
| `onPrimary` | `#FF3A342D` | :41 | 主按钮前景（可被色卡覆盖） |
| `mint` | `#FF7FBFA8` | :42 | 成功色 |
| `lilac` | `#FFB9A0D4` | :43 | 人物 / 紫棠色 |
| `clay` | `#FFC98B6B` | :31 | 陶土装饰色 |
| `warning` | `#FFE0A458` | :44 | 警示 |
| `danger` | `#FFD9705F` | :45 | 危险 |

### 1.2 派生语义色（运行时求解，非静态令牌）

| 派生 | 规则 | 来源 |
|---|---|---|
| `actionFill` / `actionInk` | 直接等于 `primary` / `onPrimary` | :51-52 |
| `linkInk` | `primary` 若为 `#FFE8A87C` 则换成 `#FF44618C`，再按 `syReadableInk` 对 `materialL5` 求解 | :53-54 |
| `personaInk` | `lilac` 对 `materialL5` 求解 | :55 |
| `successInk` / `warningInk` / `dangerInk` | 按 `text` 亮度二选一（暗底用固定深色值，亮底用 `mint`/`warning`/固定浅色） | :56-58 |

### 1.3 实测对比度（按 WCAG 相对亮度公式复算自上表 ARGB）

| 组合 | LIGHT | DARK | MATTE |
|---|---|---|---|
| `text` / `canvas` | 9.91 | 14.12 | 16.89 |
| `textSecondary` / `canvas` | 4.90 | 10.51 | 12.57 |
| `textTertiary` / `canvas` | 4.53 | 7.12 | 8.52 |
| `text` / `materialL5` | 10.13 | 11.37 | 12.80 |
| `textSecondary` / `materialL5` | 5.01 | 8.46 | 9.53 |
| `textTertiary` / `materialL5` | 4.63 | 5.73 | 6.46 |
| `onPrimary` / `primary` | 6.04 | 6.04 | 6.04 |

> LIGHT 的 `textTertiary` 对画布为 4.53，仅略高于 4.5 线；`textSecondary` 4.90 亦接近下限。

---

## 2. L1–L5 材质真实参数

来源：`app/src/main/java/com/shenyan/designsystem/components/ShenyanMaterial.kt`

| 层级 | 枚举（:52） | 模糊 dp（:117-123） | 默认阴影 dp（:124-129） | 令牌 alpha（Light） | 圆角（默认 shape，:63/:84） | 描边（:130-132） |
|---|---|---|---|---|---|---|
| L1 分区 | `L1_SECTION` | 0 | 0 | `#4D`（0.30） | `radiusCard` 18dp | `border` 1dp |
| L2 栏 | `L2_BAR` | 24 | 2 | `#75`（0.46） | `radiusCard` 18dp | 渐变 rim + 顶部高光线 |
| L3 卡片 | `L3_CARD` | 0 | 1 | `#A3`（0.64） | `radiusCard` 18dp | `border` 1dp |
| L4 浮层 | `L4_OVERLAY` | 32 | 16 | `#E0`（0.88） | `radiusSheet` 24dp | 渐变 rim + 顶部高光线 |
| L5 实心 | `L5_SOLID` | 0 | 0 | `#FF`（1.00） | `radiusCard` 18dp | `border` 1dp |

关键行为事实：

| 事实 | 位置 |
|---|---|
| L4 专用圆角 24dp，其余层 18dp；`SyMaterialFrame` / `SyMaterialSurface` 默认 shape 同此 | :63, :84 |
| 连续嵌套 ≥3 层或系统「减少透明度」时，非 L5 一律降级为 L5 | :97-100 |
| 模糊只在 `nested == 0` 且填充非全不透明时绘制；`Modifier.blur` 只模糊自身层 | :117-123, :135-146 |
| 描边：L2 / L4 用 `materialSheen → border×0.45 → sheen×0.12` 线性渐变 1dp；其他层用 `border` 1dp | :130-132 |
| 顶部高光：L2 / L4 额外画 1dp 水平线（`Canvas(matchParentSize())`，不参与测量） | :152-165 |
| 按压反馈：整体 `scale = 0.985`，时长 `pressMs` | :108-111, :174 |
| 无场景且无 backdrop 时，填充合成到 `canvas` 之上，避免透出无关内容 | :114-115 |
| L4 阴影固定 16dp，菜单 / Sheet / Dialog 各自另有取值（见 §6） | :129 |

### 2.1 材质填充求解（`ShenyanMaterialColor.kt`）

| 步骤 | 事实 | 位置 |
|---|---|---|
| 令牌映射 | L1→`materialL1` … L5→`materialL5` | :32-38 |
| MATTE 短路 | `materialL3.alpha == 1f` 或 `scene == null` 时直接返回令牌，不取色 | :40 |
| 区域判定 | `regionY < .30` → 上；`< .74` → 中；否则下 | :42-46 |
| 取样源 | 优先 `scene.regionColors[index]`（叠加 veil）；否则 `glowColors` 与 canvas 插值；否则 canvas | :47-59 |
| 保色相 | 填充 = `materialL5` 与样本按 .30（有壁纸）/ .18（无壁纸）插值 | :75-77 |
| 对比保护 | 等通道增减亮度（±.01）最多 100 步，直到 8 个语义前景色的最小对比 ≥ 4.5 | :78-90 |
| 透明度 | 目标 alpha = 令牌 alpha + `SyMaterialPolicy.adjustment(透明度)`，夹在 .08–1 | :93-98 |
| 自适应提亮 | 二分求「安全 alpha」，再按 `1 - .44×透明度` 权重混合回目标 alpha | :104-119 |
| 透明度策略 | `adjustment(t) = (0.5 - t) × 0.46` | `ShenyanFoundation.kt:173-177` |

---

## 3. `SyMetric` 几何尺度

来源：`ShenyanFoundation.kt:155-171`

| 令牌 | 值 | 用途 |
|---|---|---|
| `touch` | 48dp | 最小点击区 |
| `navigationPushOffset` | 20dp | 层级前进位移 |
| `navigationPeerOffset` | 6dp | 同级切换位移 |
| `gapXs / gapSm / gapMd / gapLg` | 2 / 6 / 10 / 14dp | 组件内间距 |
| `gapSection / gapPage / gapHero` | 22 / 34 / 54dp | 分段 / 页面 / 首屏 |
| `radiusSm / radiusControl / radiusCard / radiusSheet` | 9 / 13 / 18 / 24dp | 圆角 |

其它几何事实：

| 项 | 值 | 来源 |
|---|---|---|
| 迁移层 `SyDimensions` | `minimumTouch` 48dp、`cornerControl` 13dp、`cornerCard` 18dp、`cornerOverlay` 24dp、`navigationExpanded` 258dp、`navigationCollapsed` 64dp、`inspector` 318dp、`toolbarPhone` 56dp、`toolbarExpanded` 64dp | `ui/shenyan/ShenyanTheme.kt:66-76` |
| 输入区动作 | 视觉盘 32dp、主按钮 34dp、图标 18dp，点击区 48dp | `ui/shenyan/SyConversation.kt:63-68` |
| 按钮 | 最小宽高 48dp；Large ≥52dp；图标 18dp；内边距 16/11dp（紧凑 12/8dp） | `designsystem/components/ShenyanBasics.kt:98-115`；`ui/shenyan/SyControls.kt:43-46` |
| 选项条 Chip 间距 | 6dp | `ShenyanBasics.kt:146` |
| 进度条 | 高 7dp | `ui/shenyan/SyBusinessControls.kt:156` |
| 上下文菜单格 | 宽 96dp、高 ≥72dp、图标 22dp、格间距 4dp | `ui/shenyan/SyOverlays.kt:327-341` |

> 图标尺寸：代码中仅见 18dp（按钮）、20dp 上下（`SyComposerMetrics`）、22dp（上下文菜单）、24dp 以下范围；「20–24dp 图标 + 48dp 点击区」是 [V6.4 规范 §6](...) 的目标描述，代码中未集中定义图标尺寸令牌 —— **未找到**统一图标尺寸令牌。

---

## 4. 字号 / 行高 / 字重层级

来源：`app/src/main/java/com/labteto/dshmobile/ui/theme/Type.kt`

| 角色 | 字号 / 行高 | 字重 | 字体 | 行号 |
|---|---|---|---|---|
| `mdH1` | 24 / 32sp | Bold | content | :46 |
| `mdH2` | 21 / 30sp | Bold | content | :47 |
| `mdH3` | 19 / 28sp | SemiBold | content | :48 |
| `mdH4` | 17 / 26sp | SemiBold | content | :49 |
| `mdBody` | 14 / 23sp | Normal | content | :50 |
| `workBody` | 15 / 25sp | Normal | content | :51 |
| `mdCode` | 13 / 22sp | Normal | code | :53 |
| `largeTitle28` | 28 / 36sp | SemiBold | ui | :57 |
| `display24` | 24 / 33sp | SemiBold | ui | :60 |
| `title22` | 20 / 26sp | SemiBold | ui | :58 |
| `large20` | 20 / 28sp | SemiBold | ui | :62 |
| `headline17` | 17 / 24sp | SemiBold | ui | :59 |
| `hero26` | 26 / 34sp | SemiBold | ui | :61 |
| `base16` / `base16Strong` | 15.5 / 27sp | Normal / SemiBold | ui | :64-65 |
| `std14` / `std14Strong` | 14 / 23sp | Normal / SemiBold | ui | :66-67 |
| `small13` / `small13Strong` | 13 / 21sp | Normal / Medium | ui | :68-69 |
| `xsmall12` | 12 / 18sp | Normal | ui | :70 |
| `caption11` / `caption11Strong` | 11.5 / 19sp | Normal / Medium | ui | :71-72 |
| `navigationItem` | 14 / 23sp | SemiBold | ui | :75 |
| `navigationSupporting` | 14 / 21sp | Normal | ui | :76 |
| `navigationSection` | 14 / 21sp | Medium | ui | :77 |
| `drawerSession` | 14 / 23sp | Normal | content | :80 |
| `drawerSection` | 12 / 18sp | Medium | ui | :81 |
| `settingsItem` | 14 / 23sp | Medium | ui | :82 |
| `chatBody` / `bubbleText` | 15 / 26sp | Normal | content | :87-88 |
| `rowText` | 14 / 23sp | Normal | ui | :89 |
| `tabText` | 14 / 20sp | Medium | ui | :90 |
| `dockTitle` | 14 / 22sp | Medium | ui | :91 |
| `sectionTitle` | 13 / 21sp | Medium | ui | :92 |

字体族：`uiFont` = Outfit 400–800 + Noto Sans SC 400–800；`contentFont` = 仅 Outfit（中文回退系统）；`titleFont` = `FontFamily.Serif`；`codeFont` = fluid mono 常规 + 斜体（:19-43）。

Material 3 映射（:109-125）：`displayLarge←largeTitle28`、`displayMedium/headlineLarge←display24`、`displaySmall/headlineMedium←title22`、`headlineSmall/titleLarge←large20`、`titleMedium←headline17`、`titleSmall/labelLarge←std14Strong`、`bodyLarge←base16`、`bodyMedium←std14`、`bodySmall←small13`、`labelMedium←small13Strong`、`labelSmall←caption11`。

阅读粗细偏好：`adjustedReadingWeight` 每级 +100，最�� 800（:95-102），由 `dsTypography(textWeightAdjustment)` 注入（`ui/theme/Theme.kt:393`）。

> 注意：`ShenyanTheme` 只覆盖 `colorScheme` 与 `shapes`，**没有覆盖 `typography`**（`ShenyanFoundation.kt:250-257`）——Compose M3 默认 typography 仍在库层生效。

---

## 5. 动效令牌

来源：`ShenyanFoundation.kt:179-215`

| 令牌 | ms | 使用点数 | 令牌 | ms | 使用点数 |
|---|---|---|---|---|---|
| `pressMs` | 70 | 6 | `composerRevealMs` | 155 | 1 |
| `selectMs` | 150 | 8 | `composerFadeMs` | 95 | 1 |
| `dialogMs` | 220 | 3 | `chevronMs` | 130 | 1 |
| `sheetMs` | 280 | 3 | `interactionColorMs` | 120 | 4 |
| `pushMs` | 260 | 4 | `fadeMs` | 130 | 2 |
| `morphMs` | 260 | 1 | `pagePopMs` | 220 | 1 |
| `toolExpandMs` | 170 | 5 | `pagePeerMs` | 140 | 5 |
| `toolDetailDelayMs` | 55 | **0** | `pageEnterFadeMs` | 130 | 3 |
| `toastMs` | 170 | 4 | `pageExitFadeMs` | 95 | 2 |
| `sidebarMs` | 180 | 2 | `cascadeStaggerMs` | 55 | **0** |
| `sidebarContentDelayMs` | 0 | — | `livePulseMs` | 900 | 2 |
| `progressMs` | 280 | 1 | `holdStartMs` | 420 | 1 |
| `pressedScale` | 0.985 | — | `holdRepeatMs` | 90 | 1 |
| `hapticIntervalMs` | 45 | 1 | `reducedMotionMs` | 0 | — |

- 缓动：`CubicBezierEasing(.22, 1, .36, 1)`（:182），全 App 单一曲线。
- 节奏倍率：`duration(ms, reducedMotion, tempo)`，tempo 夹在 0.7–1.3；减少动效时一律 0ms（:184-187, :220）。
- 迁移层别名：`SyDurations` 直接引用上述常量（`ui/shenyan/ShenyanTheme.kt:78-86`）。
- 使用点数 = 生产代码中 `SyMotion.<令牌>` 出现次数（`app/src/main/java` 全量 grep）。

---

## 6. 浮层形态与差异（现状）

| 浮层 | 载体 | 背景 | 圆角 | 阴影 | 时长 | 位置 |
|---|---|---|---|---|---|---|
| 通用 Dialog | `androidx.compose.ui.window.Dialog` | `SyMaterialSurface` L4 + `border` 1dp | `radiusSheet` 24dp | 16dp（显式 `shadowElevation`） | `dialogMs` 220，缩放 .92→1 | `ui/shenyan/SyOverlays.kt:87-135` |
| 菜单（下拉） | M3 `DropdownMenu` | 默认「无缝」= `SySceneBackgroundSurface` 直接重绘场景；否则 L4 取材色 | `radiusControl` 13dp | 0dp（无缝）/ 8dp | M3 默认 | `SyOverlays.kt:204-255` |
| 菜单项 | `DropdownMenuItem` | — | — | — | — | 行高 ≥48dp，`SyOverlays.kt:258-285` |
| 上下文动作菜单 | `Popup` + `FlowRow` | `SySceneBackgroundSurface` | `radiusControl` 13dp | — | — | 96×72dp 格，22dp 图标，`SyOverlays.kt:289-353` |
| 底部 Sheet（手机） | M3 `ModalBottomSheet` | 默认「无缝」= `SySceneBackgroundSurface`；`containerColor = Transparent`，scrim 默认透明 | `radiusSheet` 24dp | 由 M3 决定 | `sheetMs` 280 | `ui/shenyan/SyResponsiveSheet.kt:166-184` |
| 侧栏 Sheet（≥600dp） | `Dialog` + 右侧面板 | 同上 | `radiusSheet` 24dp | — | `sheetMs` 280，横向滑入 | `SyResponsiveSheet.kt:131-164` |
| Toast | `AnimatedVisibility` + `SyToast` | 组件自有 | — | — | `toastMs` 170，淡入 + 上移 1/3 | `SyOverlays.kt:153-184`；TTL 3000ms |
| 全屏媒体 | `Dialog`（`decorFitsSystemWindows=false`） | `colors.canvas` 实色 | — | — | 无动画 | `SyOverlays.kt:360-389` |
| 页面层级转场 | `AnimatedContent` | — | — | — | push 260 / peer 140 / 进 130 / 出 95，位移 10dp | `ui/shenyan/SyConversation.kt:203-239` |

现状差异要点：

1. **L4 没有语义角色**：同一 `L4_OVERLAY` 在 Dialog（16dp 阴影、24dp 圆角）、菜单（0/8dp 阴影、13dp 圆角）、Sheet（0 阴影、透明容器）三种形态下表现不同，且各自硬编码，未由统一策略派生 —— 与 V6.4 §4「L4 一刀切 16dp 偏重」目标相反。
2. **「无缝背景」是双轨**：菜单 / Sheet 默认走 `SySceneBackgroundSurface`（重绘壁纸与场景，无独立底板），Dialog 默认走 L4 材质；同一层级在两种模式间切换时观感不同。
3. **层级转场与浮层未共用同一套进出曲线**：页面用 `slideIn/outHorizontally` + fade，浮层用 fade + 位移。

---

## 7. 壁纸分区取色算法

### 7.1 入口链

```
AppBackgroundHost(path, adaptiveEnabled, darkTheme, surfaceBase, surfaceTransparency, fluidGlass, ambientGlow)
  → rememberBackground(path)        解码 Bitmap（IO 线程）
  → analyzeBackground(bitmap, viewport)  可见区裁切后的网格采样（Default 线程）
  → SySceneBackdrop(regionColors, regionExtremes, veil, glowColors, ...)
  → LocalSySceneBackdrop
  → SyMaterialSurface / syResolveMaterialFill 按控件屏幕坐标取区域
  → SySceneBackgroundSurface 负责把壁纸 / 光场按坐标重绘到浮层
```

来源：`ui/theme/AppBackground.kt:236-319`（Host）、`:353-381`（解码）、`:396-482`（采样）；`designsystem/components/ShenyanMaterial.kt:237-272`（场景重绘）。

### 7.2 采样坐标规则

| 规则 | 值 | 位置 |
|---|---|---|
| 解码采样上限 | 长边 3072px，步进 2 的幂 | `AppBackground.kt:209-216` |
| 裁切 | 按视窗比例居中裁切，复现 `ContentScale.Crop` | :401-406 |
| 采样网格 | 最多 24 列 × 36 行，格心取样 `((row+0.5)·h/rows)` | :407-417 |
| 明度公式 | `0.2126R + 0.7152G + 0.0722B` | :421 |
| 饱和度 | `(max-min)/max` | :424 |
| 细节度 | 与左邻 / 上邻的明度差 ×2.2 | :428-437 |
| 区域划分 | 上 `0–0.30`、中 `0.30–0.74`、下 `0.74–1.0` | :478-480 |
| 区域均值 / 最暗 / 最亮 | 每区统计 `averageColor`、`darkestColor`、`brightestColor` | :450-470 |
| 全局遮罩 | `0.06 + 复杂度×0.12 + 明度失配×0.08 + 饱和超限×0.04`，夹 0.06–0.28 | :339-350 |
| 控件→区域映射 | 由控件中心屏幕 Y 相对视窗高度换算，三段阈值 .30 / .74 | `ShenyanMaterial.kt:101-103` |
| 菜单→区域映射 | 同三段，但采样点固定 .15 / .50 / .88 | `SyOverlays.kt:217-229` |

### 7.3 字色 / 填充求解

| 环节 | 事实 | 位置 |
|---|---|---|
| 语义前景候选 | `text`、`textSecondary`、`textTertiary`、`successInk`、`warningInk`、`dangerInk`、`linkInk`、`personaInk` 共 8 个 | `ShenyanMaterialColor.kt:65-69` |
| 填充保色相 | `materialL5` 与样本插值（.30 / .18） | :75-77 |
| 对比修正 | 等通道 ±.01 亮度步进，最多 100 步，要求 8 色最小对比 ≥ 4.5 | :78-90 |
| 采样背景集合 | 区域均值 + 该区最暗/最亮两个极值 | :60-63 |
| 安全 alpha | 二分求满足 4.5 的最小 alpha，再按 `1-.44×透明度` 回混 | :104-119 |
| 无场景 | 直接用材质令牌，不取色 | :40 |
| `syReadableInk` | 保留色相，二分在黑/白之间插值到 ≥4.5 | `ShenyanFoundation.kt:70-85` |

> **重要限制：** 对比度是在「材质填充色合成到采样底色」这一**估算模型**上求得的（`ShenyanMaterialColor.kt:104`），不是对设备上真实最终像素的测量；壁纸场景下也没有最终合成结果的自动校验。

---

## 8. 八色强调色卡与 `onPrimary` 求解现状

来源：`ui/theme/AccentTheme.kt:26-35`；投影链 `Theme.kt:263-295` → `ShenyanColorHarmony.kt:13-55`。

| # | key | 中文 | 亮主色 | 暗主色 | 声明 onLight | 声明 onDark |
|---|---|---|---|---|---|---|
| 1 | `fluid_apricot` | 流体杏（默认） | `#D9926B` | `#E8A87C` | `#3A342D` | `#3A342D` |
| 2 | `default_blue` | 默认蓝 | `#1783FF` | `#5A9BFF` | `#FFFFFF` | `#07111F` |
| 3 | `celadon_explicit` | 青瓷 | `#3E8E8C` | `#63B3AC` | `#FFFFFF` | `#0C1A19` |
| 4 | `dailan` | 黛蓝 | `#44618C` | `#8FB0D9` | `#FFFFFF` | `#0D1520` |
| 5 | `zhusha` | 朱砂 | `#A64B44` | `#DE8B84` | `#FFFFFF` | `#1C0D0C` |
| 6 | `xianghuang` | 缃黄 | `#A67C32` | `#D9BC7A` | `#FFFFFF` | `#1A150A` |
| 7 | `zitang` | 紫棠 | `#7458A8` | `#B3A0DC` | `#FFFFFF` | `#150F20` |
| 8 | `yanzhi` | 胭脂 | `#A34A5E` | `#DB8B99` | `#FFFFFF` | `#1F0D11` |

### 8.1 求解机制

1. `readableAccentForeground(preferred, fills)`：若 `preferred` 对**给定 fills 集合**的最小对比 ≥4.5 直接采用；否则在黑/白中选对比更高者，再二分插值（`AccentTheme.kt:50-68`）。
2. `withAccent` 把色卡注入旧 `DsColors`：`accent`、`onAccent`、`brandPrimary`（accent 向安全极偏移 10%）、hover（偏移 8%）、各交互态透明层（`AccentTheme.kt:74-107`）。
3. `harmonizeV6` / `withV6Accent` 把结果回灌到 V6 规范色板：`primary = source.accent`、`primaryPressed = accentHover 合成 bgBase`、`onPrimary = source.onAccent`、`selected = accentTertiary 合成 bgBase`（`ShenyanColorHarmony.kt:50-55`）。
4. `SyButton` 的 PRIMARY 填充 = `c.primary`，前景 = `c.onPrimary`（`designsystem/components/ShenyanBasics.kt:80-93`）。

### 8.2 现状判定（按代码实测）

| 色卡 | 亮底：声明前景对比 | 亮底实际解 | 暗底：声明前景对比 | 暗底实际解 |
|---|---|---|---|---|
| 流体杏 | 4.84 ✓ | `#3A342D` | 6.04 ✓ | `#3A342D` |
| 默认蓝 | **3.67 ✗** | `#000000`（5.73） | 6.82 ✓ | `#07111F` |
| 青瓷 | **3.85 ✗** | `#000000`（5.45） | 7.28 ✓ | `#0C1A19` |
| 黛蓝 | 6.30 ✓ | `#FFFFFF` | 8.19 ✓ | `#0D1520` |
| 朱砂 | 5.66 ✓ | `#FFFFFF` | 7.31 ✓ | `#1C0D0C` |
| 缃黄 | **3.78 ✗** | `#000000`（5.55） | 9.89 ✓ | `#1A150A` |
| 紫棠 | 5.67 ✓ | `#FFFFFF` | 8.01 ✓ | `#150F20` |
| 胭脂 | 5.68 ✓ | `#FFFFFF` | 7.26 ✓ | `#1F0D11` |

**结论：**

- `onPrimary` **不是**固定值：色卡经 `withV6Accent` 覆盖了 `SyColors.onPrimary`，三张亮色卡（默认蓝 / 青瓷 / 缃黄）的白字声明会被求解器改成黑色。
- 但求解**只针对单一平面主色**，未覆盖 `primaryPressed`（hover 偏移后合成 `bgBase` 的结果）、渐变起点 `brandPrimary`（accent 向安全极偏移 10%）以及半透明叠加后的最终像素 —— 即 **不是**「按最终填充色求解」。
- 求解结果在 `withAccent` 中被 `lerp` 偏移使用（hover 8%、品牌渐变起点 10%），偏移后的色值没有重新求解前景对比。
- 三主题 × 八色卡 × 按下/禁用态的对比矩阵**无自动测试**（见 §10）。

---

## 9. V6.4 五项「待实现」在代码中的确切位置与现状

对应 [V6.4 规范 §2 表格](../v6/V6.4-VISUAL-MATERIAL-GUIDELINES.zh-CN.md) 的「本轮目标（待实现）」列。

| # | V6.4 待实现项 | 代码位置 | 现状行为 |
|---|---|---|---|
| 1 | **主题**：保留三主题色相关系，统一高对比和自定义色卡校验 | `ShenyanFoundation.kt:87-117`（三主题硬编码）、`:62-85`（`syInkContrastRatio` / `syReadableInk`）、`AccentTheme.kt:50-68` | 三主题关系保留；高对比只在**单色填充**层面求解（见 §8.2），三主题 × 八色卡 × 按下/禁用态的完整对比矩阵无自动测试 |
| 2 | **五层材质**：减少重复描边；给 L4 定义菜单 / Sheet / Dialog 语义角色 | `ShenyanMaterial.kt:130-132`（描边）、`:152-165`（顶部高光）、`:124-129`（阴影）、`:52`（层级枚举）；`SyOverlays.kt:118-122`（Dialog）、`:230-240`（菜单）、`SyResponsiveSheet.kt:170-173`（Sheet） | 描边 + 顶部高光 + 阴影三重强调在 L2/L4 同时存在；L4 仍是一刀切 32dp 模糊 / 16dp 阴影，菜单 0/8dp、Sheet 0、Dialog 16dp 各写各的，**没有**共享的 L4 语义外观策略 |
| 3 | **壁纸**：最终像素对比校验、位置一致、透明度反馈明确、极端壁纸安全 | `ShenyanMaterialColor.kt:104-119`（对比估算）、`AppBackground.kt:396-482`（采样）、`:189-199`（透明度夹取）、`ShenyanMaterial.kt:101-103` 与 `SyOverlays.kt:217-229`（两套区域映射） | 对比度是「合成模型估算」，非最终像素校验；透明度被 `resolveWallpaperAlpha` 夹到 `[min-0.18, max+0.08]` 且无用户可见解释；纯白 / 纯黑 / 高饱和壁纸无测试 |
| 4 | **强调色**：前景色按最终按钮填充色自动求可读对比度，三主题全量测试 | `AccentTheme.kt:50-68`、`:74-107`；`ShenyanColorHarmony.kt:50-55`；`ShenyanBasics.kt:80-93` | 前景确实按用户色卡求解（非固定），但只针对单一平面主色，未覆盖按下 / 渐变 / 半透明后的最终像素；三主题全量测试缺失 |
| 5 | **动效**：补足真实工作状态及组件过渡的微动效 | `ShenyanFoundation.kt:179-215`（令牌）、`:185-187`（`duration` + tempo）、`ui/shenyan/SyControls.kt:102-115`（`SyStatusLine`） | 时序令牌齐全且已统一入口，但工作状态只以**颜色 + 状态词**表达（`SyStatusLine` 无过渡动画）；`toolDetailDelayMs`、`cascadeStaggerMs` 两个令牌**使用点数为 0**（定义未落地） |

---

## 10. 现有视觉相关自动测试清单

| 文件 | 覆盖点 |
|---|---|
| `app/src/test/java/com/labteto/dshmobile/ui/components/ConversationMenuIconsTest.kt` | 菜单图标为新纸墨矢量且视口一致（:8） |
| `app/src/test/java/com/labteto/dshmobile/ui/components/PaperInkEntryIconsTest.kt` | 全部入口图标路径可渲染（:12） |
| `app/src/test/java/com/labteto/dshmobile/ui/screens/local/LocalDrawerVisualStyleTest.kt` | 抽屉模式跟随壁纸且黑底标签保持对比（:10） |
| `app/src/androidTest/java/com/labteto/dshmobile/ui/screens/local/LocalReplyCompositionVisualTest.kt` | 窄屏回复嵌图满宽阅读、无重复结果头（:34）、四图相册可见（:54）、暗色三图末行均衡（:74）、行内文件单菜单（:92）、行内音频单一入口（:107）、独立媒体窄版式暗色（:116） |
| `app/src/androidTest/java/com/labteto/dshmobile/ui/screens/local/LocalReplyDocumentInlineVisualTest.kt` | 回复文档预览可读且能打开完整详情（:47） |
| `app/src/androidTest/java/com/labteto/dshmobile/ui/screens/local/WorkReplyTimelineVisualTest.kt` | 工作时间线六态可见（:24）、窄屏 Markdown 状态（:51）、命令工具自然动作与回执（:63）、聊天样式区分叙述与语义动作（:83）、动作分组单行可读（:118）、暗色大字号下失败/运行状态可读（:138）、成果恢复失败说明（:145） |
| `app/src/androidTest/java/com/labteto/dshmobile/ui/screens/local/WorkBackgroundReplyAndroidTest.kt` | 后台输出回流时的定位与去重（:31、:59、:104） |
| `app/src/androidTest/java/com/labteto/dshmobile/ui/screens/local/ConversationHeaderTransparentIconsAndroidTest.kt` | 顶栏导航/新会话无底板无阴影且可点击（:30） |
| `app/src/androidTest/java/com/labteto/dshmobile/ui/screens/settings/SettingsRowIconVisualTest.kt` | 设置行图标对齐槽位且整行可点（:20） |

**未找到**的视觉测试（V7 需要补齐）：

| 缺失项 | 说明 |
|---|---|
| 三主题 × 三级文字对比矩阵 | 无自动测试 |
| 八色卡 × 三主题 × 按下/禁用态对比 | 无自动测试 |
| L1–L5 透明度随滑块变化 | 无自动测试 |
| 极端壁纸（纯白 / 纯黑 / 高饱和 / 细碎纹理）最终合成 | 无自动测试 |
| 减少透明度 / 减少动效策略回归 | 无自动测试 |
| `SyMetric` / `SyMotion` / 色板与文档同步校验 | 无自动测试（[V6.4 §9](...) 提出的 CI 同步校验尚未落地） |

---

## 11. 审计发现的风险（供 V7 取值时优先处理）

| 级别 | 风险 | 依据 |
|---|---|---|
| 高 | L4 浮层三种形态（Dialog / 菜单 / Sheet）视觉与阴影各写各的，且存在「无缝背景」双轨，浮层观感不可预期 | §6 |
| 高 | 强调色前景只对单一平面色求解，按下 / 渐变后的实际填充未校验 | `AccentTheme.kt:50-68`；`ShenyanBasics.kt:80-93` |
| 高 | 壁纸对比度是估算模型，极端壁纸下真实像素未验证 | `ShenyanMaterialColor.kt:104-119` |
| 中 | LIGHT 主题 `textTertiary` 对画布 4.53:1，仅贴线通过 | §1.3 |
| 中 | 「减少透明度」只对 L3 以下材质生效，`LocalSyReduceTransparency` 未在 `AppBackgroundHost` 壁纸路径上统一处理 | `ShenyanMaterial.kt:99`；`AppBackground.kt:236-319` |
| 中 | 两个动效令牌定义后从未使用（`toolDetailDelayMs`、`cascadeStaggerMs`），易被误读为已实现 | §5 |
| 中 | `SyMaterialTier`、`SyColors` 等在 `ui/shenyan` 有同名代理枚举（`SyMaterial.kt:14`），存在第二事实源风险 | `ui/shenyan/SyMaterial.kt:14-46` |
| 低 | 统一图标尺寸令牌缺失，图标尺寸分散在组件内 | §3 |
| 低 | `ShenyanTheme` 未覆盖 `typography`，库层仍使用 M3 默认字号 | `ShenyanFoundation.kt:250-257` |
