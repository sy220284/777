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
| Kimi Code | 项目预置部分 K3 型号有图片／视频输入标签；视频输入不等于视频生成 | 官方公开 API 文档说明 Vision 是输入理解、TTS/ASR 暂不可用；Kimi Code 的编码套餐与开放平台 API Key 不互通，不能把输入模态当成生成服务 | **未接通创作**；避免按视频输入标签错误暴露生成按钮 |
| 智谱 GLM | 按具体 GLM 模型区分文本、图片理解 | 官方存在 CogView / CogVideo 家族专业生成产品，但本轮未核实与用户的聊天模型账户相同的模型、版本与专用端点细节 | **未接通创作**；有官方可用接口时新增适配器与模型条目 |
| 自定义 OpenAI 兼容代理 | 仅能通过具体协议探测确认已实现的聊天／图像输入 | **不能**因为自称 OpenAI 兼容就默认兼容 `/images/generations`、语音、视频或账户费用 | **默认不接通付费创作**，必须明确配置可验证的专属适配 |

## 统一业务流及状态事实

`聊天/工作「+」入口 → LocalMediaStudioSheetContent → LocalMediaCatalog.offers() → 用户选择[操作 + 创作模型 + 凭据来源账户] → LocalMediaCreationRuntime → 提供商原生传输 → 标准化结果 → 查看/分享/草稿回填`

1. 业务操作只有 `IMAGE` / `VIDEO` / `SPEECH` / `TRANSCRIBE` 四种；每种能力只显示已接入可执行模型，不复制服务商专用面板；列表按照用户已配置凭据来源生成。对应底层选择精确到 profile ID + 服务商 + 操作 + 创作模型 ID。
2. 执行时冻结选项并复用 `LocalModelCredentialResolver.resolve(..., profile)`，不再次读取聊天的 mutable active model。服务商 SDK 只能处理自己的协议与生成结果，不能控制 UI 的模型选择。
3. 图片统一归一化为 HTTPS 结果链接或本机缓存图片（通过 Android FileProvider 分享）；配音统一本机 MP3 缓存文件；ASR 统一文字结果供复制/插入当前输入；视频统一任务 ID → 显式查询 → 下载链接。
4. 当前视频接入 MiniMax Hailuo 2.3 和 Qwen Wan 2.6；它们采用不同的提交／查询端点，并保留会话内任务编号；不冒充 Gemini Veo、Qwen Wan 等平台异步任务已支持。已开始生成的外部收费任务不会因页面离开自动退款，也不静默创建新任务重试。
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
5. 同一视频创作模型可以连续提交多个任务，当前会话以路由分组保留任务编号并允许选择旧任务回查。**这仍不是持久运行中心**：异步响应尚未在外部 API 接受后立即进入数据库，弹层被关闭、进程退出时任务 ID 可能丢失。需要作为后续最高优先级的业务 Owner 生命周期升级，不应在这次 PR 中假称完全解决。

## 2026-10-11 复查订正

- 根据阿里云 2026-10-10 更新的 **Qwen-Image 3.0 OpenAI 兼容图片接口**，客户端目录只声明该接口明确支持的 `qwen-image-3.0`、`qwen-image-3.0-pro`。旧 `qwen-image-plus` 的生成能力需要独立核对旧版 DashScope API 与异步生命周期，不能使用 3.0 兼容端点和尺寸参数冒充已接通。
- 各家 API 请求必须先确认业务错误字段，不能因 HTTP 200 判定成功。Gemini inlineData 图片须依据返回的 mimeType 保存和分享，不能强制用 PNG 扩展名。
- 图像、语音生成/转写的同步等待不适用应用通用 30 秒网络超时，媒体传输拥有可取消的长耗时网络客户端。等待不会自动重试已经产生副作用的生成操作。
