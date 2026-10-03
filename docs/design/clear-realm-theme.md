# 澄境（Clear Realm）主题

本文是 777 手机端默认视觉主题的权威设计规范。它与 `docs/UI-UX.zh-CN.md`、`docs/design/mobile-ui.md` 和当前 Compose 实现共同构成 UI 事实源。

## 1. 设计目标

澄境的核心是：

> 让界面退到内容之后，让人物有温度，让工作有秩序，让状态自然发生。

视觉优先级固定为：

```text
内容
> 当前状态
> 下一步操作
> 辅助信息
> 装饰
```

主题借鉴 SwiftUI 的信息层级、轻量 Surface、自然动效和内容优先原则，但继续遵守 Android 原生返回、键盘、权限、通知与 48dp 最小触控范围。禁止模拟 iPhone 状态栏、Home Indicator 或系统皮肤。

## 2. 双气质域

### Chat

关键词：柔和、亲近、呼吸感、人物感。

- 人物头像、姓名、关系 / 当前故事优先。
- 助手 / 角色正文尽量直接排版，减少大块 Bubble。
- 用户消息保留轻量 Bubble。
- Token、模型、工具和运行诊断退到次级入口。
- 人物运行期间身份层不淡出、不闪烁。

### Work

关键词：清晰、精确、可靠、秩序。

- 当前目标、当前阶段、阻塞和产物优先。
- 执行过程采用 Timeline + Disclosure。
- 只有真实 Running 项允许持续动画。
- 已完成、失败和历史项全部静止。
- 原始工具参数、Shell 与日志进入二级详情。

## 3. Surface

全应用只使用四级空间层：

1. **Base**：页面背景，无阴影。
2. **Grouped**：逻辑分组，主要靠背景差、留白与 Separator。
3. **Elevated**：Composer、当前选择、局部浮动控件，允许极弱阴影。
4. **Overlay**：Dialog、Sheet、Menu、Toast，承担最高层空间分离。

卡片不能承担普通排版职责。普通 Group 默认无阴影；只有明确需要“浮起”的内容才使用 Elevated。

## 4. 颜色

### 澄境 · 明

- Base：`#F7F7F5`
- Surface 1：`#FFFFFF`
- Surface 2：`#F1F1EF`
- Surface 3：`#E9E9E6`
- 主文字：`#181816`
- Separator：黑色约 6%–10%

### 澄境 · 夜

- Base：`#10100F`
- Surface 1：`#191918`
- Surface 2：`#222220`
- Surface 3：`#2B2B28`
- 主文字：`#F4F3EF`
- 次级文字：`#CAC8C1`
- 辅助文字：`#92908A`

### 澄境 · 墨

沿用低眩光、暖白文字和中性黑灰方向；它与夜色共享暗色系统语义，但进一步降低眩光。

### Accent

传统色 Accent 继续保留。建议视觉占比：

```text
80% 中性色
15% Surface 层级
5% Accent
```

Accent 只表达当前选择、主操作、焦点、当前人物、关键运行状态与少数强调。

## 5. 字体

普通 UI 使用无衬线。衬线字体只允许出现在人物日记、故事标题和文学内容。

语义层级：

- Large Title：28sp / SemiBold
- Title：22sp / SemiBold
- Headline：17sp / SemiBold
- Body：17sp / Regular
- UI Body：15sp / Regular
- Secondary：14sp
- Caption：13sp
- Micro：12sp

Chat 用户和角色正文必须保持相同字号与行高，身份通过头像、容器、对齐和色彩表达。

## 6. 圆角与阴影

圆角：

- Small：8dp
- Row：12dp
- Group：16dp
- Composer：20dp
- Dialog：20dp
- Sheet 顶角：24dp
- Capsule：Full

阴影：

- 页面 / Group：0
- Composer / 轻浮动：约 1dp
- Menu：2–3dp
- Dialog / Sheet：约 4dp

原则：越贴近页面越方，越临时浮起越圆。

## 7. 导航

一级功能页使用 Large Title；二级页使用 Inline Title。Chat / Work 主界面始终保持紧凑顶部栏。

一级页包括人物图集、角色日记、工作空间、运行中心、任务、工具、设置、用量、远程等。

页面进入使用短位移 + Fade。移动距离只占屏宽约 8%–12%，返回反向。禁止整页从屏幕外长距离飞入。

## 8. 按钮与输入

按钮分为：

- Primary：唯一主要操作，Accent 实色。
- Tinted / Info：Accent 低浓度背景。
- Plain / Ghost：无背景。
- Outline：少量需要边界的次操作。
- Danger：不可逆操作。

所有按钮覆盖 Default、Pressed、Disabled、Loading。Pressed 采用约 `1 → 0.975 → 1` 的短弹性反馈。Loading 保持控件尺寸稳定。

Icon Button 视觉 18–20dp，命中区 48dp；Selected 使用 Accent 低浓度圆形 Surface。

Composer 是 Chat 的主控制器。Idle 低存在感，输入后发送按钮成为 Accent；运行中只做发送 / 停止形态切换，不额外制造大状态卡。

## 9. Dialog / Sheet / Menu / Toast

### Dialog

用于确认、简短编辑、高风险决策。入场采用 Fade + `0.97 → 1` 轻缩放。

### Sheet

用于选择模型、人物、附件、会话操作等快速选择。Sheet 顶角 24dp，使用 Grabber，随 IME 正常移动。

### Menu

只承担上下文动作，危险操作放底部；使用 Feather 图标语言。

### Toast

只表达短暂、可重复的一次性结果。普通错误如果需要用户处理，必须同时有页面级恢复路径，不能只发 Toast。

## 10. 状态与动效

动效原则：**有物理感，没有表演感。**

推荐节奏：

- Press：80–120ms
- 小状态：120–160ms
- Segment：160–200ms
- Composer：180–220ms
- Disclosure：180–240ms
- Page：200–240ms
- Sheet / Dialog：200–280ms
- Running Pulse：约 900ms

Running 只允许当前真实执行项做 0.55–1.0 的轻呼吸。禁止标题 Shimmer、历史步骤持续闪动、完成态循环动画。

Success / Failure 只做一次收束，随后完全静止。

## 11. 一级页面

### 设置

使用 Inset Grouped 思路：分组标题 + Group + Row。右侧显示当前值与 Chevron；可直接切换的设置使用 Switch，不多跳一层。

### 人物图集

默认列表以头像、名字、当前故事和最近状态为主体。大人物照片只放详情页。管理能力进入管理态。

### 人物详情

照片是首屏视觉中心；继续故事 / 新故事是主要动作。人物资料、导出、删除和图片管理退到“人物资料与管理”。

### 角色日记

允许克制衬线标题和更大留白，内容像记录，避免参数面板感。

### Work / 运行中心

以目标、当前阶段、执行时间线、后台任务、子代理和结果组织。过程使用 Disclosure，不默认暴露原始日志。

### 工具

先展示“是什么 + 能不能用”，配置与诊断进入详情。

## 12. 侧边栏

骨架保持：

```text
头像 + 神言神语        新建 / 搜索
Chat | Work
当前上下文
快捷入口
置顶
最近
远程 / 设置
```

当前上下文是侧栏唯一明确 Elevated Group；其他快捷入口使用轻量图标 + 文本。当前会话用浅 Accent Surface 表达，不靠大卡片。

## 13. 自定义背景

自定义背景下所有 Screen、Drawer、Card、Composer、Dialog、Sheet、Menu、Toast 必须复用同一自适应 Surface 策略。复杂背景应自动提高不透明度和文字对比，禁止某些浮层绕过全局策略。

## 14. 性能和无障碍

- 禁止全局实时 Blur。
- Streaming 不触发全页面动画和重组。
- 长列表不使用大面积透明叠层和无限动画。
- 每个独立点击目标至少 48dp。
- 状态不能只靠颜色。
- Icon Button 必须有语义描述。
- 大字号下主要动作不能被挤出页面。

## 15. 组件事实源

澄境的公共实现必须集中在现有 Design System：

```text
Color / Theme / Type / Shape / Spacing / Animation
→ AppBars
→ Button / IconButton
→ Group / Row / Card
→ Composer
→ Pill / Segment / Disclosure / Timeline
→ Dialog / Sheet / Menu / Toast
→ Empty / Error / Loading
→ Chat / Work / 各一级页面
```

禁止长期维护“Material 版”和“澄境版”两套平行组件。业务页需要新视觉能力时，优先扩展公共组件，再由页面消费。

## 16. 验收

每个页面第一眼必须回答：

1. 我在哪里？
2. 现在最重要的内容是什么？
3. 下一步能做什么？

回归至少覆盖：320dp 窄屏、普通 / 130% 字号、系统大字体、IME、明 / 夜 / 墨、自定义背景、Empty / Error / Loading / Running、长标题、长历史、Dialog / Sheet / Menu、返回手势、Chat / Work 切换和流式输出。
