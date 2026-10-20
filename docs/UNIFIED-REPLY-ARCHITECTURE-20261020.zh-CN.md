# 朝夕 777｜统一多模态回复架构与视觉规范

范围：聊天与工作统一回复编排，媒体/文件/成果/交互文档，兼容 #791 新工作步骤。基于 2026-10-20 主线代码；落地按可独立回归的阶段进行。

## 产品目标

用户关注“一次回复做了什么、交付了什么、如何继续操作”，不必在多个独立消息卡、工作输出列表和媒体中心之间往返。聊天继续轻量对话，工作保留紧凑过程时间线，但都复用同一条对话时间线与内容归属模型。

### 已有的事实边界

- LocalHarnessMessage 和 LocalMessageBlock 管理持久文本、用户附件及部分模型附件，媒体实体在 LocalMediaStore，不能把二进制文件重写进会话。
- LocalMediaResult/LocalMediaCreationJob/LocalMediaVideoTask 都有 sourceMessageId；UiDocumentSource 有 messageId；LocalArtifactUiItem 有 sourceCallId。
- NativeV6DocumentMessage 已按来源消息归属；工作流程回执依赖 toolCallId 和可展开详情，不允许伪造完成状态。
- mergeLocalMediaTimeline 原先按时间混排独立创作结果；appendWorkArtifactsToTranscript 原先把全部成果尾随会话，存在与来源脱离的体验问题。

## 设计选型

**保留独立持久化事实 + 合并阅读侧呈现投影（本次）。**
从已有 Owner 的 messageId/callId 构建归属，不调整权限、媒体提交、下载或保存协议。关联媒体与成果仍占独立 LazyColumn item，以防超长回复成为一个巨大的 Compose 子树；但每个条目带 attachedTo，并靠近来源回复或工具步骤。

不选择“新建多媒体资产数据库再复制消息和附件”，因为会制造双份所有权和竞态。不选择“按时间推断来源”，因为会错挂重试、历史、群聊与分支结果。

### 现阶段数据流

`session history → buildLocalTranscript → mergeLocalMediaTimeline → appendWorkArtifactsToTranscript → appendPendingWorkToolActivities → composeLocalReplyAttachments → LocalConversationSurface`

- 找到可见 sourceMessageId：图片/音频/视频/创建任务紧随其来源消息。
- 找到可见 sourceCallId：工作成果紧随已验证 toolCallId 的真实工具步骤。
- 找不到来源：保持旧独立条目及素材档案，历史翻页后可重新投影；不凭时间补关系。
- 关联成功的条目只变更 attachedTo，不变更媒体或工具 key；任务阶段变更仍复用原来位置，视频轮询、保存/分享、正文去重继续由原运行时承担。
- UI Document 已有来源关联，不复制一份展示。

## 视觉设计体系

依据既有《朝夕 UI 设计规范》与 2026-10-18 `ui设计.skill`/`aesthetic-ui` 联合审计基准（两者同源）。

- **层级：** 正文是主要信息；图/视频用内容本身作为视觉焦点，省掉重复“已生成 / 基于此回复”标题；生成中、失败、未知保留可理解的轻量状态。
- **色彩：** 继续使用 LocalSyColors 的 text/textSecondary/textTertiary/materialL3；不新增高饱和卡片块。
- **排版：** 使用 DsType 的阅读型文字样式、SyMetric 的统一间距。已归属媒体使用 100% 父容器宽度与 640dp 上限；未知归属的旧条目延续 90% 宽与 470dp 上限，避免突兀改变老记录。
- **媒体：** 图片等比缩略图与点击预览；音视频复用真实播放状态和本地文件；失败时保留重试/错误解释，不用假的成功占位图。
- **交互：** 已有 NativeV6Document 的表单提交、二次确认、历史只读及 Media Studio 的素材管理均保留。后续统一局部 Save/Share 到媒体溢出菜单，切勿先删除可用功能。
- **尺寸与动效：** Android 320dp 窄屏、深浅模式、130%/200% 字号、减少动效以及设备旋转不得裁切；大型媒体懒解码，独立 Lazy 项不牺牲滚动流畅度。

## 分阶段交付与验收

### 第一期（PR #794）

1. 真实来源关联与投影、稳定 item key、历史回退；
2. 连接生成中和已完成状态，图片/音视频/工作成果缩减重复外壳；
3. JVM：顺序、遗漏来源、任务身份、视频、工具成果；Android：320dp 浅色/深色布局；
4. GitHub CI 编译、单元测试、Android 16/17 设备用例通过后才能进入合并审批。

### 第二期（未实现，后续 PR）

1. 建立更通用的统一 ReplyBlock 接口，允许新模型输出的媒体占据正文指定插入点；
2. 把消息级/内容级操作收口到一致的菜单体系，避免底部保存/分享与详情菜单重复；
3. 将有可信关联的文件成果进一步整理到最终助手答复中；支持批量附件折叠、分页；
4. UiDocument 在对话中按内容复杂度决定内联或展开，保持工作审批的真实 Owner；
5. 读屏标签、触摸热区、跨设备对比截图、长会话滚动锚点及应用后台恢复专项验收。

不能把一期的视觉优化说成全部多模态架构完成；审计以实际代码与 CI 证据为准。
