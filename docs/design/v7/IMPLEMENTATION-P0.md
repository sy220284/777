# V7 · P0 令牌落地映射（实施计划）

> **性质：实施计划，尚未落地。** 对应 [V7-VISUAL-LANGUAGE.zh-CN.md](V7-VISUAL-LANGUAGE.zh-CN.md) §12 的 P0 阶段。落地前需先在真机/模拟器上验证三主题与强调色表现。

## 1. 令牌宿主与唯一事实源

仓库里同时存在两套令牌体系，V7 必须落到**权威的那一套**，不能新增第三套：

| 体系 | 位置 | 使用面 | V7 定位 |
|---|---|---|---|
| Shenyan 设计系统 | `com.shenyan.designsystem.foundation`（`SyColors` / `SyMetric` / `SyMotion` / `SyMaterialPolicy`）+ `com.shenyan.designsystem.components` | 122 个文件 | **V7 令牌的唯一权威宿主** |
| 旧 Ds 主题 | `com.labteto.dshmobile.ui.theme`（`DsColors` / `DsType` / `DsMetric`） | 96 个文件（含与上一套同时引用的文件） | 只做**收敛适配**，不新增第三套色板 |

依据：[V6 设计包 README](../v6/README.zh-CN.md)「以代码为准的视觉归属」已明确 `ShenyanFoundation.kt` → `SyColors / SyMetric / SyMotion / SyMaterialPolicy` 是视觉事实源。

**收敛规则：**

1. V7 的新色值、几何、动效只写在 Shenyan 体系里；`docs/design/v7/tokens.json` 是它们的机读镜像。
2. 仍引用 `Ds*` 的页面，通过**一次映射**取到 Shenyan 语义色（适配层），不各自复制色值、不产生第二事实源。
3. 每迁移一个页面到 Shenyan 组件，旧 `Ds*` 路径在对应能力上退出；不保留双轨开关。
4. 与旧 `Ds*` 同时存在的功能（用户已保存的外观偏好）保持**兼容读取**，不因视觉重做被重置。

## 2. 三层结构在 Kotlin 侧的落法

```
docs/design/v7/tokens.json
   primitives（原始值）
        ↓
   semantic（角色别名：canvas / ink / accent / …）   →  SyColors 的字段
        ↓
   components（button.primary.bg / listRow.divider …） →  Compose 组件层默认参数
```

- `SyColors` 承载 **semantic 层**：三主题各一份对象，与 `tokens.json` 的 `semantic.color.<mode>` 一一对应。
- **primitive 层**不进 Kotlin 运行时：它是配色的原始台账，运行时直接消费解析后的 semantic 值，避免每次读色再解析字符串。
- **component 层**落在 Compose 组件的默认参数上（如 `SyButton` 的 `variant: ButtonVariant = Primary`），不散落在页面里。

## 3. P0 改动清单

### 3.1 `SyColors` 新增/调整字段

| 字段 | 动作 | 说明 |
|---|---|---|
| `canvas` / `surface` / `surfaceSolid` | 调整色值 | 见 `tokens.json` → `themes` |
| `bar` | **新增** | L2 栏不透明底，替代当前用 `materialL2` 表达栏位的做法 |
| `text` / `textSecondary` / `textTertiary` | 调整色值 | 对齐 `ink` / `ink2` / `ink3` |
| `border` | 调整色值 | 对齐 `rule` |
| `borderStrong` | **新增** | 对齐 `ruleStrong`（次要按钮描边、输入框边） |
| `primary` / `primaryPressed` / `onPrimary` | 调整色值 | 对齐 `accent` / `accentPressed` / `onAccent` |
| `accentSoft` | **新增** | 选中行淡染（用户气泡、当前步骤轨点） |
| `materialL1`…`materialL5` | **重定义** | P1 阶段改为不透明；P0 先补新角色，不改旧语义以保证可回退 |

### 3.2 `SyMetric` 调整

| 令牌 | V6 | V7 |
|---|---|---|
| `radiusSm` / `radiusControl` / `radiusCard` / `radiusSheet` | 9 / 13 / 18 / 24dp | 8 / 12 / 16 / 20dp |
| `radiusOverlay` | — | **新增** 16dp |
| `gapXs` / `gapSm` / `gapMd` / `gapLg` | 2 / 6 / 10 / 14dp | 4 / 8 / 12 / 16dp |
| `gapSection` / `gapPage` | 22 / 34dp | 24 / 32dp |
| `touch` | 48dp | 48dp（不变） |

### 3.3 `SyMotion` 调整

`pressMs` 70→60、`selectMs` 150→140、`dialogMs` 220→200、`sheetMs` 280→240、`pushMs` 260→240、`pagePopMs` 220→200、`sidebarMs` 180→160、`fadeMs` 130→110、`progressMs` 280→260、`pressedScale` .985→.99。缓动曲线不变。

### 3.4 新增 `SyType`

新文件 `com/shenyan/designsystem/foundation/SyType.kt`，按规范 §5 定义 8 个排版角色（`display` / `title` / `heading` / `bodyStrong` / `body` / `bodyQuiet` / `caption` / `mono`），并在 `ShenyanTheme` 里装配为 MaterialTheme 的 typography，使 `SyButton`、列表行、聊天正文等组件能按角色取样式，而不是各写各的字号。

- 字体族沿用现有资源：UI 用 `DsType.uiFont`，正文用 `DsType.contentFont`，代码用 `DsType.codeFont`——**不新增字体资源**。
- 用户已保存的"阅读字重"偏好通过既有 `adjustedReadingWeight` 继续生效。

### 3.5 强调色卡（`AccentTheme.kt`）

- **保留全部 8 个既有 key**（`fluid_apricot` / `default_blue` / `celadon_explicit` / `dailan` / `zhusha` / `xianghuang` / `zitang` / `yanzhi`）：它们与用户外观偏好持久化绑定，改 key 会重置用户设置。
- 显示名更新为 V7 命名（朱砂 / 琥珀 / 苔绿 / 松石 / 靛蓝 / 紫棠 / 玫紫 / 石墨），色值对齐 `tokens.json` → `primitives.accentCards`。
- 前景色求解沿用现有 `readableAccentForeground` 的思路，但改为在**正常态与按下态两个填充色**上取最小对比度后求解，并允许输出 `#FFFFFF` / `#1A1613` 两极（现行实现偏向黑/白二分但偏好色优先，需与 V7 §3.2 统一）。
- `onLight` / `onDark` 的历史硬编码值不再作为事实源，改为求解结果。

## 4. 文件清单（P0）

| 文件 | 动作 |
|---|---|
| `app/src/main/java/com/shenyan/designsystem/foundation/ShenyanFoundation.kt` | 扩展 `SyColors`、调整 `SyMetric` / `SyMotion` |
| `app/src/main/java/com/shenyan/designsystem/foundation/SyType.kt` | **新增** 排版角色 |
| `app/src/main/java/com/labteto/dshmobile/ui/shenyan/ShenyanTheme.kt` | 装配三主题与 typography |
| `app/src/main/java/com/labteto/dshmobile/ui/theme/AccentTheme.kt` | 八色卡色值、显示名、前景求解 |
| `app/src/main/java/com/labteto/dshmobile/ui/theme/Theme.kt` | 旧 `DsColors` 与 Shenyan 语义色的**单向映射**（适配层，不新增色值） |
| `app/src/test/resources/design/v7/tokens.json` | **新增** 令牌镜像（供 JVM 测试读取） |
| `app/src/test/java/com/shenyan/designsystem/SyTokensParityTest.kt` | **新增** 令牌与 Kotlin 常量一致性测试 |
| `app/src/test/java/com/shenyan/designsystem/SyContrastTest.kt` | **新增** 三主题 × 表面 × 文字对比度矩阵测试 |

`docs/design/v7/tokens.json` 与 `app/src/test/resources/design/v7/tokens.json` 由同步脚本保持一致（`docs/design/v7/sync-tokens.py`，CI 校验），避免两份令牌漂移。

## 5. 测试计划

| 测试 | 断言 |
|---|---|
| `SyTokensParityTest` | `SyColors` 三主题字段值 == `tokens.json` 解析值；`SyMetric` / `SyMotion` 同理；镜像文件与文档文件一致 |
| `SyContrastTest` | 正文类组合 ≥4.5:1；强调色前景在正常/按下两态 ≥4.5:1；八色卡前景求解结果达标 |
| 既有视觉测试 | `ShenyanReadableInkTest` 等既有断言在新色值下仍成立；如有偏差，**改断言到新契约**，不删测试 |

本机无 Java / Android SDK，无法编译验证；实现阶段的验证口径与 PR #926 一致：源码审阅 + 测试代码 + 设备侧巡检由仓库方完成。

## 6. 回滚策略

- P0 只**新增**角色与**替换**色值，不删除旧字段；旧色值常量在 P0 阶段保留一个提交周期，便于二分回退。
- 三主题切换必须即时生效且无中间态崩溃；若真机验证发现某主题不可用，先回退该主题色值，不阻塞其余主题。
- 视觉重做不得与功能变更同 PR：P0–P6 每个 PR 只含视觉相关改动，功能契约变更另开 PR。

## 7. 验收

1. `SyTokensParityTest` 与 `SyContrastTest` 全绿。
2. `python3 docs/design/v7/check-tokens.py` 通过（82 项）。
3. 三主题在真机上分别截图核对：画布、卡片、浮层、选中态、按下态、禁用态。
4. 既有视觉测试无回归。
5. `docs/design/v7/STATUS.zh-CN.md` 同步更新阶段状态。

## 8. CI 接入

仓库现有 CI 分两类：Gradle 任务（编译 + 单元测试 + 设备测试）与 Python 审计脚本（`tools/audit/*.py`，见 `architecture3-callgraph-audit.yml`）。V7 校验按后者接入，避免依赖 Android 工具链：

- 在 `tools/audit/` 增加 `check-design-tokens.py`，复用 `docs/design/v7/check-tokens.py` 的校验逻辑（单一实现：脚本被 CI 以参数调用，不复制规则）。
- 新增 workflow `design-tokens-check.yml`：`checkout` → `python3 tools/audit/check-design-tokens.py`；失败即阻断合并。
- 同步校验 `docs/design/v7/tokens.json` 与 `app/src/test/resources/design/v7/tokens.json` 一致，防止两份令牌漂移。
- 图标与 SVG 数量门禁沿用 v6 既有 `audit-all-icons.py`，V7 不另建图标门禁。
