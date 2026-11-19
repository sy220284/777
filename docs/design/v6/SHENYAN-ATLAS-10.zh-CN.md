# 神言原生组件库 · 十类交互图集落地

> 本文对应 2026-11-19 的十张 UI 图集设计评审。
> 权威代码仍为 `com.shenyan.designsystem`（视觉与组件）和 `ui/shenyan`（应用弹层与聊天适配）。
> 本变更在原生 Compose 中实现，业务状态和副作用由调用 Feature 继续持有。

## 组件能力与真实入口

| 图集 | 已有组件 / 本次增加的原生组件 | 真实责任 |
|---|---|---|
| 01 基础控件 | `SyButton`、`SyTextField`、`SyTabs`、`SySwitchControl`、`SySliderControl`、`SyIconButton`、`SyGroupCard` | ShenyanBasics / FormControls / Feedback / Compound |
| 02 弹窗与模态框 | `SyDialog`、`SyConfirmDialog`、`SyFullScreenDialog` | ui/shenyan/SyOverlays 与 ShenyanBasics |
| 03 底部弹窗 | `SyResponsiveSheet`、`SyModalSheet`、`SyActionSheet`、`SyMultiSelectSheet` | 现行响应式弹层与新操作面板 |
| 04 长按/滑动 | `SyConversationActionPopup`、`SyContextActionMenu`、`SySwipeActionRow` | 现行触点菜单 + 左滑显示操作 |
| 05 通知与反馈 | `SyToastHost`、`SyUndoSnackbarHost`、`SyInlineBanner` | 轻提示、可撤销通知、持续状态 |
| 06 加载与进度 | `SyLoadingState`、`SyProgressBar`、`SyEmptyState`、`SySkeletonBlock`、`SySkeletonCard` | 真实任务状态驱动，无伪进度 |
| 07 引导与过渡 | `SyOnboardingCarousel`、`SyPageDots`、`SyCoachMark` | 新手引导、可访问的页码与提示 |
| 08 表单/筛选 | `SyTextField`、`SySegmentedControl`、`SyDatePickerField`、`SyDateRangePickerDialog`、`SyNumericStepper` | 表单状态由调用方持有，日期时间采用系统毫秒值 |
| 09 媒体播放 | `SyFullscreenMediaOverlay`、`SyMediaTransport` | 播放、进度和跳转由实际媒体播放器持有 |
| 10 AI 聊天 | `SyConversationComposer`、`SyComposerField`、`SyConversationActionPopup`、`SyChatComposer` | 消息编辑、附件、语音与发送皆为真实回调 |

新增复用能力集中在 `ShenyanInteractionAtlas.kt`，只补现有库缺失的交互，
不重建已有的 Sheet、菜单、主题、Toast 或播放器业务层。

## 落地设计规则

1. **风格多样，语言一致。** 配色由主题/壁纸/调用方选取，统一计算文字对比与选中状态，不固定成一种暖金风；保持蓝紫霓虹之外的丰富色彩搭配。
2. **易用性。** 操作目标至少 48dp、输入状态与加载状态可辨认；响应式弹层支持需要时的部分展开；半屏面板的滚动与底部操作区分开。
3. **动效。** 使用唯一的 `SyMotion` 时长和缓动。按压先响应，之后展开/切换；跟手滑动由手势位移驱动，超过阈值或速度满足条件时归位。尊重 `LocalSyReduceMotion`。
4. **安全。** 滑动只展开操作，不触发实际删除/发送等副作用；危险操作继续使用明确确认回调；撤销在调用方确实可以恢复时才展示。
5. **内容。** 空状态、等待、失败、完成和禁用属于不同真实业务状态。骨架屏用于实际等待数据，不模仿已经完成的数据。
6. **跨端与可访问性。** 保持中文、系统字号缩放、TalkBack 状态描述、键盘/导航栏安全区和横屏大屏适配。输入发送与媒体按钮的状态继续由调用方拥有。

## 关键验收

- 三个主题在不同系统字体缩放下可读；选定强调色的文字对比至少 4.5:1。
- 单个组件的默认、焦点、按下、禁用、加载、成功和错误状态具备真实视觉与文字反馈。
- 表单日期、多选、数字步进值由外部状态控制，关闭弹层时不会悄悄提交。
- 底部弹窗的返回、下滑、屏幕边缘、IME 与内容滚动路径正常。
- 长按菜单保留触点定位，手势揭示不自动执行危险动作。
- 快速连续点击、动画中断、系统减少动态效果时，状态与事件没有重复或丢失。
- 用户无需引入云端服务；UI 全部在本机 Compose 渲染。

## 验证状态

该文档是本次组件重构的接口契约与验收清单。
编译、设备实测及已有功能回归以当前改动提交对应的实际执行记录为准。
