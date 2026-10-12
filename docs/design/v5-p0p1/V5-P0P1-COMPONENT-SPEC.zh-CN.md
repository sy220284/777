# 神言神语 V5 · P0/P1 组件增补规范

> 这是对 **原 v5 素材库的增量**，不是新的配色/主题，也不是 777 App 已实现功能的声明。原始 38 屏、原 `Sy*` 组件、五级材质与视觉矩阵全部保留。
>
> 唯一新增可操作入口：`v5-p0p1-components.html`。演示使用离线样例数据，不调用真实模型、视频解码、文件上传、支付或任务审批。Android 迁移时必须接真实回调。

## 设计边界与复用

- 沿用 `semantic-design-tokens.json` 与 `V5-SCENE-DESIGN-SYSTEM.zh-CN.md`：暖纸/烟熏/哑光黑；暖杏主操作、薄荷成功与运行、陶土可恢复失败、危险红仅用于不可逆操作；间距 2/6/10/14/22/34/54，卡片 18px、Sheet 24px。
- 材质 L1/L2/L3 用于背景、附属卡、常规内容；关键成果卡 L4；可读正文/播放器控件最多嵌一层 L5，避免超过三层透明祖先。所有文字和图标始终清晰，不应用 `blur()` 到正文。
- 手机优先原位交付。宽度 320–599 一列、600–1023 双列样例，>=1024 宽屏；与既有宿主 600/760/1024/1440 断点一致。组件本身随容器自适应，不能按浏览器 `px` 机械映射 Android `dp`。
- 最小点击区域 HTML 44px、Android 48dp；角色名、长文件名省略同时保留完整语义；保留 Esc 关闭/焦点恢复/状态 live announcement；尊重减少动效/减少透明度。
- 正式素材采用单张原图按显示需求解码和裁切，不因设备再存两套相同图片。没有真正人物立绘或视频时使用明确标记的渐变占位，禁止将原型图当作业务生成结果。

## P0 · 直接影响 Chat/Work 完整体验（10）

| 编号 | 组件 / 设计页 | 必备变体和交互 | 消费点 |
|---|---|---|---|
| 01 | `SyRichMessage` 富媒体消息 | text/image/video/audio/file/mixed；消息发送中、已完成、失败；图片点开查看，文件预览，交付就地展示 | 01 聊天 / 38 媒体 |
| 02 | `SyDeliverableCard` 成果卡 | ready/pending/error/expired；重试、重新获取、预览仅操作当前产物 | 01 聊天 / 02 Work / 16 文件 |
| 03 | `SyArtifactGallery` 成果画廊 | 图片、视频、文档可混排；单项加载/缺图/格式不支持；点击对应预览 | 01/16/38 |
| 04 | `SyExecutionTimeline` 流式步骤轴 | queued/running/done/blocked/failed/cancelled；当前步骤与真实百分比独立 | 02/03/15/37 |
| 05 | `SyParallelToolGroup` 并行轨道 | 多工具独立状态；原文日志可展开；仅重试失败项；不吞异常 | 02/03/15 |
| 06 | `SyDecisionCard` 审批卡 | awaiting/selected/confirmed/cancelled/expired；显式选择和确认；取消不执行 | 02/14 |
| 07 | `SyRecoveryNotice` 恢复卡 | network/auth/permission/timeout/file-expired; cause/action/retained-results | 01/02/29/35 |
| 08 | `SyPartialResult` 部分结果 | all/partial/none；展示可用成果、遗漏与下一步，不把完成数量误判成功 | 02/15/37 |
| 09 | `SyResumeBanner` 续跑横幅 | paused/restartable/conflict/expired；恢复点和已保存内容；继续/退出 | 02/03/19 |
| 10 | `SyGenerationProgress` 媒体任务 | queued/running/ready/error/cancelled；源任务和展示分离 | 01/38 |

## P1 · 呈现和输入体验（10）

| 编号 | 组件 | 必备变体和交互 | 消费点 |
|---|---|---|---|
| 11 | `SyPersonaCard` 人物图集 | has-image/loading/no-image/failed；焦点和长按 | 06/07 |
| 12 | `SyPortraitFallback` 缺图兜底 | 姓名首字＋主题渐变；远程加载失败仍显示人物详情入口 | 06/07/10 |
| 13 | `SyImageViewer` 图片预览 | fit/fill、缩放、注释、单图/多图、失败、关闭与焦点恢复 | 01/06/16/38 |
| 14 | `SyVideoViewer` 视频容器 | poster/loading/playing/paused/muted/error/unsupported；原生播放器接入 | 01/16/38 |
| 15 | `SyAudioPlayer` 音频容器 | 播放/暂停、进度、速度、载入失败；无音频时不能假装在播 | 01/38 |
| 16 | `SyTranscript` 时间轴转录 | 多说话人、选段、时间、编辑纠错、失败时保留原音频 | 01/38 |
| 17 | `SyAttachmentRow` 附件行 | long-name/image/audio/video/pdf/archive/unknown, locked/ready/error | 01/16/38 |
| 18 | `SyFileThumbnail` 文件缩略图 | 缩略图可用/损坏/无权限/未知格式兜底 | 16/38 |
| 19 | `SyMediaQueue` 媒体队列 | mixed statuses, retry individual, batch cancel confirm | 02/38 |
| 20 | `SyAttachmentPicker` 附件选择 | idle/selected/uploading/failed/rejected; 容量、类型、权限错误 | 01/38 |

## P0 交付链路的视觉闭环

用户发起需求 → 对话消息中显示执行阶段 (`SyExecutionTimeline`) → 并行工具状态 (`SyParallelToolGroup`) → 需要用户决定时插入 `SyDecisionCard` → 逐项形成 `SyDeliverableCard` 并放入 `SyRichMessage` 的 `SyArtifactGallery` → 图片/视频/音频/文件分别由 P1 预览组件打开。部分失败时追加 `SyPartialResult` + `SyRecoveryNotice`，不清空成功项；重进会话使用 `SyResumeBanner` 恢复。

**设计示例 ≠ 真实系统已接入。** 预览卡文案、模拟计时、任务阶段和状态切换只说明视觉如何表现，不允许把示例进度、媒体内容或审批当作成功结果。

## 无障碍、错误与场景验收

所有 P0/P1 组件要覆盖：默认、加载、空、成功、失败、取消、部分成功、恢复；长中文、英文路径；浅色、深色、哑光；320/390/600/840/1024/1440 视口；文字放大；触控与键盘；减少透明度与动效。组件的无意义状态可标 N/A，不制造假的成功路径。具体变体集合和正式接入要求见 `V5-P0P1-COMPONENT-CONTRACTS.json`。

### 现阶段验收状态

- 已完成：离线交互展示、三主题、20 组件基本设计与契约、静态结构校验（记录于 `V5-P0P1-VERIFICATION.json`）；浏览器在当前环境被访问策略拦截，尚未取得新截图。
- 未完成且不得误报：Figma **组件实例实际写入**（Starter 调用配额受限）；777 原生 Compose 生产代码接入、模型执行、真实媒体解码/存储和 Android 16/17 设备验证。

设计资源**全部新增**于独立文件，原始 38 屏与原组件不被替换；适配时优先在 777 的新 `Sy*` 体系中复用，不应回退 `Ds*` 旧视觉组件。
