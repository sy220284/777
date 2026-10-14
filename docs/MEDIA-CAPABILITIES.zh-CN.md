# 多模态能力矩阵与统一创作业务契约

更新：2026-10-11。依据官方模型及 API 文档；**输入、输出和当前 777 实际接通的服务必须分开描述**。实际 UI 可用项唯一来自 `LocalMediaCatalog.offers()`，不是仅凭预置模型名字或宣传中的模态标签推断。

## 概念边界

- **理解能力**：当前聊天模型能否接收图片／音频／视频输入，在 `LocalModelPresets.runtimeCapabilitiesFor()` / `clientCapabilitiesFor()` 与模型请求网关中判断；以当前实际编码的请求协议为准。现行通用聊天传输只确认文字／图片可消费；它不意味着生成图片、音频、视频。
- **创作能力**：独立产品 API 的文生图、视频任务、TTS、ASR。它有自己的创作模型 ID、URL、请求与结果格式、计费规则；跟当前聊天模型分开。选中已配置的官方模型账户，仅借用该账户关联的 API 凭据；不把聊天模型 ID 错送到生成端点。
- **文档已支持**：官方提供能力，但 777 尚无经过协议验证的适配器。此状态**不能**在手机 UI 宣称“可创作”，也不能把预置模型标成生成能力。
- **已接通**：官方专属适配器、结果处理和用户入口都已编码。并不等同真实付费帐号在线验证通过。
- **套餐权限**：聊天/编码套餐与官方独立生成 API 产品不等价；ChatGPT 套餐 OAuth 凭据不用于 OpenAI 图片/TTS/ASR；MiniMax 编码套餐是否能用生成 API 由服务商判定，不假设免费。

## 官方能力核查及客户端状态

| 服务商 | 当前聊天模型/输入模态（777 实际支持） | 官方专业创作模型／能力 | 777 原生创作接入 |
| --- | --- | --- | --- |
| MiniMax | M3 图像理解；M2.7 文本（以现有模型输入协议为准） | `image-01` 文生图；`MiniMax-Hailuo-2.3` 文生视频；`speech-2.8-hd` TTS；`asr-1.0` ASR | **图片、视频、配音、转写已接通**，保留官方模型专用适配 |
| OpenAI | 官方支持的 GPT 聊天模型支持图像输入；原 `Responses image_generation` 工具独立保留 | `gpt-image-2.5-flare`、`gpt-image-2.5-sunburst` 图片；`gpt-4o-mini-tts` 配音；`gpt-transcribe`、`gpt-4o-transcribe` 语音转文字 | **图片、配音、转写已接通**（官方 API Key；不包含 ChatGPT Plan OAuth） |
| 阿里云 Qwen | `qwen3.8-*` 等输入图片的聊天能力按具体预置与兼容协议判断 | `qwen-image-3.0`、`qwen-image-3.0-pro` 文生图；万相 `wan2.7-t2v` 等视频模型 | **图片和 Wan 2.6 文生视频已接通**（图片走 OpenAI Images 兼容路径；视频走 DashScope 异步任务）；Wan 2.7 等其他版本尚未接通 |
| Google Gemini | 可支持图片／视频理解；777 当前聊天请求仍按其已编码协议判断 | `gemini-3.1-flash-image`、`gemini-2.5-flash-image` 文生图；`veo-3.1-generate-preview` 视频；`lyria-3.5` 音乐 | **图片已接通**（原生 `generateContent`）；Veo/Lyria 文档已支持但尚未接通 |
| DeepSeek | 官方 `deepseek-flash` 支持图片输入理解；其他模型不可据此泛化 | 本轮未查证可直接调用的官方图片／视频／语音生成产品 API | **未接通创作**；保留图像理解，不伪装图片生成为已支持 |
| Anthropic Claude | 官方 Vision 允许图片理解 | Claude Messages 的图像输入不是图像合成端点；不能由 Vision 自动派生创作能力 | **未接通创作**，保留已有 Vision |
| Kimi Code（会员订阅） | `k3`、`k3-256k`、`kimi-for-coding`、`kimi-for-coding-highspeed`：客户端已接通文字/图片理解；`k3-256k` 不支持视频输入，其余型号的视频输入属官方模型能力、777 尚未完成文件直传 | Kimi Code 是独立的智能编程会员接口，具备推理档位和工具调用，但不等同独立图片/视频生成 API | **未接通专业创作**；订阅 Key 与开放平台 Key 不互通，详见 `KIMI-CODE-ADAPTATION.zh-CN.md` |
| 智谱 GLM | 按具体 GLM 模型区分文本、图片理解 | 官方存在 CogView / CogVideo 家族专业生成产品，但本轮未核实与用户的聊天模型账户相同的模型、版本与专用端点细节 | **未接通创作**；有官方可用接口时新增适配器与模型条目 |
| 自定义 OpenAI 兼容代理 | 仅能通过具体协议探测确认已实现的聊天／图像输入 | **不能**因为自称 OpenAI 兼容就默认兼容 `/images/generations`、语音、视频或账户费用 | **默认不接通付费创作**，必须明确配置可验证的专属适配 |

## 统一业务流及状态事实

`聊天/工作「+」与消息操作入口 → LocalMediaStudioSheetContent → LocalMediaCatalog.offers() → 用户选择[操作 + 创作模型 + 账户] → LocalMediaCreationRuntime → 生成/任务事实持久化（绑定 sessionId）→ 当前聊天时间线卡片自动更新 → 内联预览/播放/分享；工作区与媒体档案仅保留历史索引`

1. 业务操作只有 `IMAGE` / `VIDEO` / `SPEECH` / `TRANSCRIBE` 四种；每种能力只显示已接入可执行模型，不复制服务商专用面板；列表按照用户已配置凭据来源生成。对应底层选择精确到 profile ID + 服务商 + 操作 + 创作模型 ID。
2. 执行时冻结选项并复用 `LocalModelCredentialResolver.resolve(..., profile)`，不再次读取聊天的 mutable active model。服务商 SDK 只能处理自己的协议与生成结果，不能控制 UI 的模型选择。
3. 图片优先将官方返回的 HTTPS 结果下载到应用持久目录并在聊天内预览；下载失败才保留带警示的临时链接。配音以 MP3 直接在聊天中播放并分享；ASR 文字持久记录到所属聊天并可引用/复制；视频以单张卡片呈现提交、排队、生成、完成状态，在页面可见时只读自动查询（不重复生成），完成后异步尝试保存 MP4，支持原位播放与分享。
4. 当前视频接入 MiniMax Hailuo 2.3 和 Qwen Wan 2.6；它们采用不同的提交／查询端点，并保留持久视频任务档案；不冒充 Gemini Veo、其他 Wan 型号等未适配的异步任务已支持。已开始生成的外部收费任务不会因页面离开自动退款，也不静默创建新任务重试。
5. 费用与用量采用服务商各自的账号事实源，不把 MiniMax 套餐余额跨服务商共享。UI 明确提示各创作产品可能单独计费。
6. 用户已在设置配置聊天账户并不代表有该商家的图片/视频权限；本轮以官方文档静态能力+用户配置识别为入口判断，真正调用是否获授权由服务商返回，失败应当显示，不静默换账户重试。
7. 旧 `MiniMaxMediaSheet`／`MiniMaxMediaViewModel`／`LocalMiniMaxMediaRuntime` 退出，原 `MiniMaxNativeMediaClient` 仅保留作为 MiniMax provider transport，避免复制业务状态。

## 官方证据源

- MiniMax：https://github.com/MiniMax-AI/cli/blob/main/src/client/endpoints.ts 、https://github.com/MiniMax-AI/cli/blob/main/src/types/api.ts 、https://github.com/MiniMax-AI/cli/blob/main/skill/SKILL.md
- OpenAI GPT Image 官方端点与模型：https://developers.openai.com/api/reference/resources/images/methods/generate （`/v1/images/generations`；新 GPT Image 输出 `b64_json`）；https://developers.openai.com/api/docs/deprecations （旧版 GPT Image 正在退役）
- OpenAI TTS：https://developers.openai.com/api/reference/resources/audio/subresources/speech/methods/create （`gpt-4o-mini-tts`、`/v1/audio/speech`）
- OpenAI STT：https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create （`gpt-transcribe`、`gpt-4o-transcribe`、`/v1/audio/transcriptions`）
- Qwen Image 官方 OpenAI 兼容 API：https://help.aliyun.com/zh/model-studio/qwen-image-generation-and-editing-api-reference （`/compatible-mode/v1/images/generations`，返回 URL，非通用 GPT Image Base64）
- 阿里云万相专业视频模型：https://help.aliyun.com/zh/model-studio/use-video-generation/ ；Wan 异步生成与 24 小时任务／URL 有效期：https://help.aliyun.com/zh/model-studio/text-to-video-guide （已接通 Wan 2.6，其他型号不能据此冒充已适配）
- Gemini 图片生成/REST：https://ai.google.dev/gemini-api/docs/generate-content/image-generation ，模型：https://ai.google.dev/gemini-api/docs/models
- Gemini Veo：https://ai.google.dev/gemini-api/docs/veo （异步视频，不应复用 MiniMax 任务轮询路径）
- DeepSeek Flash Vision：https://api-docs.deepseek.com/guides/vision/
- Claude Vision：https://docs.anthropic.com/zh-CN/docs/build-with-claude/vision
- Kimi 官方 API：https://www.kimi.com/help/kimi-api/api-overview 、https://www.kimi.com/help/kimi-api/api-model-capabilities 、https://www.kimi.com/help/kimi-api/api-troubleshooting （尤其关注 Kimi Code 与开放平台账户隔离）

## 验证说明

代码层含 `LocalMediaCatalogTest`：只有官方 HTTPS 账户与 API Key 才进入创作候选；验证套餐 OAuth 排除、不同凭据来源独立、模型 ID 精确校验、纯文本聊天模型仍可借同一账户调用独立创作产品、代理不可冒用官方创造力。

尚无用户真实付费 API 账户、Android 16/17 的真实网络/媒体回归证据；本次代码级 CI 不等于四家创作接口端到端成功。真实验证前不将“已接通”写作“已成功生成”。


## 源码复审补充（同日）

1. Qwen Image 3.0 兼容端点在当前官方文档中明确要求百炼 Workspace 域名（`{WorkspaceId}.{region}.maas.aliyuncs.com/compatible-mode/v1`）。不再对旧 `dashscope.aliyuncs.com` 聊天路由直接宣称 3.0 文生图已接通；该旧官方入口仍可通过独立 DashScope 异步协议提供已适配的 Wan 2.6 文生视频。API 权限和模型实际开通范围仍以账户响应为准。
2. Qwen Image 3.0 OpenAI 兼容接口仅对 `qwen-image-3.0`/`qwen-image-3.0-pro` 开放；旧 `qwen-image-plus` 未沿用该协议。参见 2026-10-10 官方说明：https://help.aliyun.com/zh/model-studio/qwen-image-generation-and-editing-api-reference 。
3. 新媒体请求复用 Android 可取消的 OkHttp 调用，但不再继承共享短请求 30 秒读取超时。媒体服务 HTTP 200 但返回 `error`/`code` 时标记失败；Gemini Base64 图像依据实际 `inlineData.mimeType` 保存和分享。
4. OpenAI 音频上传优先根据 Android SAF 提供的原始文件名识别合法扩展名，在原始 MIME 缺失时仍允许已明确支持的格式，拒绝将其他音频伪装为 MP3。文档：https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create 。
5. 同一视频创作模型可以连续提交多个任务，`LocalMediaStore` 统一持久保存任务身份、原账户路由指纹、服务商任务 ID、终态与错误。面板退出后进程内媒体运行时仍继续提交。进程崩溃恰在远端接受付费请求但尚未拿到任务编号的极端窗口，记录会恢复为 `OUTCOME_UNKNOWN`，不自动重试；只有用户向服务商核对才可确定远端是否接收。这一风险无法仅靠本机存储完全消除。

## 2026-10-11 复查订正

- 根据阿里云 2026-10-10 更新的 **Qwen-Image 3.0 OpenAI 兼容图片接口**，客户端目录只声明该接口明确支持的 `qwen-image-3.0`、`qwen-image-3.0-pro`。旧 `qwen-image-plus` 的生成能力需要独立核对旧版 DashScope API 与异步生命周期，不能使用 3.0 兼容端点和尺寸参数冒充已接通。
- 各家 API 请求必须先确认业务错误字段，不能因 HTTP 200 判定成功。Gemini inlineData 图片须依据返回的 mimeType 保存和分享，不能强制用 PNG 扩展名。
- 图像、语音生成/转写的同步等待不适用应用通用 30 秒网络超时，媒体传输拥有可取消的长耗时网络客户端。等待不会自动重试已经产生副作用的生成操作。所有创作 POST 请求体标记为不可重放，防止 OkHttp 在连接恢复、503 或认证 follow-up 中自动再发一次收费请求；只读查询和成果下载保留正常传输能力。


## 统一多模态运行时：任务与成果持久化（2026-10-11）

- **业务所有者唯一**：`LocalMediaCreationRuntime` 是主动多模态创作唯一执行入口；`LocalMediaStore` 的 SQLite 数据库统一保存视频任务及创作回执/成果，回执与成果共用同一事务记录。聊天界面不再维护重复的视频任务 Map。Work/Agent 运行中心暂不接管外部媒体任务，避免双写和跨 Feature 所有权冲突。
- **视频提交顺序**：冻结创作路由 → SQLite 先持久化 SUBMITTING 与原账户路线指纹 → 进程级 SupervisorJob 发出外部生成请求 → 成功获取远端 task ID 后持久化为 QUEUED → 手动只读查询 → 持久化终态和视频 URL。失败、取消或断电处于不确定提交时不盲目自动重试。
- **无法消除的崩溃窗口**：服务商已接受收费请求但客户端未收到其 task ID 时，本机无法判断远端是否完成。冷恢复会标记 `OUTCOME_UNKNOWN` 并要求去服务商平台核对；要彻底消除这段风险，需要服务商支持幂等提交键或可查询的请求身份。
- **账户身份匹配**：恢复查询绑定创作账户 ID、路由指纹、服务商、专业模型，不能自动改用当前聊天模型或其他账户。账户已移除时任务档案仍可查看，但查询会要求恢复原配置。
- **成果文件**：本轮新生成的 GPT/Gemini 图片和 MiniMax/OpenAI MP3 配音保存于 `filesDir/media-studio/`，而非会被系统清理的 `cacheDir`；按 provider/model/kind 记入 `LocalMediaStore`，支持跨会话查看、分享和确认删除。应用卸载或清数据仍会删除本地文件，重要成果可使用系统分享另行导出。
- **服务商在线图片**：MiniMax/Qwen 等返回的 HTTPS URL 只作为*临时成果链接*记录，不宣称图片已经离线保存。过期后仍需服务商续签 URL 或用户先前手动下载。
- **诊断与隐私**：状态与失败原因回到持久视频任务记录，不保存提示词或明文 API Key。图片/视频/语音消费和费用不可从文字 Token 账本推算，仍需服务商权威响应对接。
- **验证**：`LocalMediaPersistenceRulesTest` 覆盖崩溃保守恢复、远端 ID 身份附着、终态防迟到覆盖、账户匹配以及文件/链接序列化，仍需真实付费账户与 Android 设备测试。

## 媒体记录恢复与视频状态

媒体正常读写由 `LocalMediaStore` 的 SQLite 事务数据库接管。旧 JSON/SharedPreferences 首次读取先完整解码，回执、成果、视频任务与迁移标记在一次数据库事务中提交；失败回滚，不迁移为空历史。成功后旧 JSON 主备文件移动到 `media-studio/migration-archive/`，旧 preferences 保留但不再读取。归档仅供人工恢复，不是正常运行的第二事实源。已建立的数据库缺失或损坏会显示恢复错误，不能删除损坏数据库后假装空历史继续收费生成。

成果与回执在同一记录中原子完成；删除先提交逻辑删除，再清理本地文件，文件清理失败保留可重试标记。删除或重启均不会重读旧归档而复活卡片；运行中收费任务拒绝删除。所有媒体历史保留，页面按会话/时间索引默认读取 50 条，通过“加载更早记录”扩展，不再冷启动全量解析历史。

视频的服务商状态与本地交付状态分开：服务商成功但本地保存中断时可以恢复或手动重试下载，同一任务的下载合并为一次；可以用原账户查询已成功任务更新过期链接。这些操作不重新提交收费生成。临时图片链接仍不冒充永久下载。

MiniMax 原始视频状态 Preparing、Queueing、Processing、Success、Fail 在供应商边界归一化；Preparing 映射到排队中，Fail 映射到失败。依据：[MiniMax 官方视频状态查询](https://platform.minimax.io/docs/api-reference/video-generation-query)。

## 2026-10-12：成果直接回到聊天（PR #657）

- 聊天和 Work 的模型/账户列表共同来自当前 ModelSelection；修复了 Chat 状态映射遗漏账户导致创作面板空列表的断点。
- 新媒体创作请求在开始时冻结所属 `sessionId`：已生成图片、音频、转写文字以单张卡片直接进入当前聊天，视频任务从提交时进入同一位置并只更新其状态。旧版无 `sessionId` 的索引保留在档案，不跨会话泄露。
- 图片/CDN 返回链接优先安全下载到 `filesDir/media-studio/`，保留退化链接用于下载失败的恢复；MP4 生成成功后异步保存到同目录，本机文件存在时聊天卡片优先播放/分享本机内容。超过上限/服务商链接失效会明确显示，不能宣称已永久落盘。
- Work 现有 `artifactsForUi(sessionId)` 成果索引同步投射到对话尾部，文本和代码在消息中内联预览、图片缩略展示，其他文件可直接打开或分享；索引没有重新创造文件副本，也不把生成成果注入模型的角色对话历史。
- 创作提示词摘要作为本机卡片说明保存，最长 600 字；不保存 API Key。删除结果后卡片随事实索引消失。服务商远端付费视频在任务 ID 未知的异常窗口仍要求人工核实，不盲目重试。
- 功能级代码与 JVM 单元测试不得冒充服务商真实生成/真机联调结果；后续按本 PR 最新提交的 CI 与设备测试判定交付状态。

## 2026-10-12 · 第二批创作聊天体验优化（PR #657）

**真实代码路径**：创作面板 `submitImage/submitSpeech/submitTranscribe` 先在 `LocalMediaCreationJobStore` 持久化 `sessionId + 任务ID + 原始能力 + 模型 + 简短提示词`，由 `LocalMediaCreationRuntime` 的应用级 `SupervisorJob` 执行服务商请求。弹层关闭或转去其他会话不撤销已提交的付费请求。提交即以 `media-result:<任务ID>` 在对话中显示阶段卡；结果文件的 `LocalMediaResult.id` 与任务 ID 相同，成功后保留卡片稳定 key 原位替换为图片/音频/转写成果。此机制不会把创作成果伪造成模型对话文本。

**异常恢复与费用安全**：同步创作先持久化 `PREPARING`，发送 HTTP 前提交 `SUBMITTING`，确认服务商成果后提交 `SAVING`。准备阶段失败/重启为 `NOT_SENT`；明确拒绝为 `REQUEST_REJECTED`；服务商成功后的保存失败为 `SAVE_FAILED`；未确认请求结果的 `SUBMITTING` 在重启时恢复为 `OUTCOME_UNKNOWN`。记录原账户模型、会话及错误，明确提示用户先核对服务商记录，**不自动再次提交付费请求**。当前只保证任务事实可恢复，不表示可以在被系统杀进程时继续云端 HTTP 请求，也不能在服务商不支持幂等/任务查询时自动取回未知结果。删除媒体时一并清理关联回执，防止已删成果残留幽灵卡片。

**聊天内显示精修**：
- 图片按真实宽高显示纵图/横图；本机图片通过采样解码文件，不再因体积大于 12MB 直接当作无缩略图；可点图查看原图，加载与失败状态分离。
- 视频完成卡片保留一个明确播放动作，去掉重复按钮；播放器、分享和本地持久化沿用原实现。
- 配音卡增加持续时间、当前进度、拖动播放位置；切离会话释放播放器。
- Work 成果采用最新事件版本替代首次版本，并保持原位稳定 key；不再静默丢弃 20 条之前的成果。

**视觉迁移边界**：这批增强在当前可编译分支使用 `DsTheme` 过渡层，不复制 `SyMaterial` 渲染逻辑。PR #654 的 V5 完整迁移仍是独立草稿，之后需在合流时将新增卡片迁至统一 `Sy*` 组件；Light/Dark/Matte、320～1440dp 与动态/触觉无障碍需运行截图确认。Figma 编辑端本轮因 Starter MCP 调用限额无法写入，不能声称已经完成画板。

**CI 验收**：以包含本章节的最新 PR head 为准，核实 unit-tests、static-gates、architecture-3-gates、build-arm64 和 merge-gate。服务商付费创作及 Android 16/17 真机链路需要独立实测，不能用 JVM 单测替代。
