# MiniMax 模型与官方技能接入

状态：模型接入已随 PR #622 合并；本分支进行手机技能适配，合并后生效。代码和协议回归可在 CI 验证；尚无真实 MiniMax 账户调用、手机长聊或媒体生成验收。

## 用户可见变化

- M3 可选择快速直答或思考；M2.x 保持模型强制思考，不显示无效关闭选项。
- 手工配置 M3.1 Flash Preview 时提供基础、深入、最大思考，避免发送不支持的关闭参数。该型号当前仅 M Plan / MiniMax Code 可用，不作为普通 API Key 默认预置。
- 官方中国与全球入口、OpenAI 与 Anthropic 格式共用型号能力信息。M3 的 100 万、M2.x 的 204800 上下文容量进入现有预算与压缩流程；自定义容量覆盖仍有效。
- OpenAI 格式明确请求分离思考，流式和非流式均保留原始 reasoning_details；同一路由工具续轮传回完整私有信息，换模型或协议时沿用既有隔离规则。
- MiniMax 思考时仍可调温度，范围为官方 0～2；无需为思考模式静默丢弃有效采样选择。
- 接收 HTTP 200 内的 base_resp 错误，保留余额或权限失败原因，避免显示缺少正文或自动重复扣费调用。

## 全链路核对

| 使用过程 | 现有入口与本次处理 |
| --- | --- |
| 配置与模型选择 | LocalModelPresets / LocalModelProfile；保留现有国内配置，识别 api.minimaxi.com、api.minimax.cn、api.minimax.io，代理不冒充官方能力 |
| 工作、单聊与群聊 | 现有请求协调器冻结配置、凭据和路由，经 LocalModelGateway / Adapter 发出；本次在共享适配处修复，未在各模式复制供应商逻辑 |
| 子任务、自动任务及辅助模型 | 沿用既有模型运行时、路由选择及拥有者；使用已选 MiniMax 路由时消费同一能力表与协议适配 |
| 连续工具使用 | OpenAI 私有 replay 保存思考明细，Anthropic 现有原始 thinking/text/tool_use 无损回放继续使用；不将思考当成用户可见进度 |
| 流式、失败、取消与重试 | 延续既有终态验证、取消贯通和接纳后不盲重试；新增 MiniMax 业务错误识别，无第二套重试机制 |
| 图片与视频 | M3 官方支持图片与视频，M2.x 不支持；现有原生传输仅开放文字和图片，视频沿用媒体处理路径，未宣称已实现原生视频或音频直传；MiniMax 图片上限 10 MB，Anthropic 请求体按官方 64 MB |
| 用量与缓存 | 消费实际 usage / 缓存计数，不估算费用；本次未新增显式收费缓存写入、优先服务档位或原生服务端工具 |
| 技能发现与执行 | 复用技能页、Work 激活、skill 读取、文件与终端；目录只包含中文简介，原文和脚本按需读取，不塞入每次聊天 |

模型资料并不替代账户资格或真实服务验证。普通 API Key、M Plan 订阅密钥、媒体 API 权限需按官方说明区分。

## 预置的官方技能

固定来源：MiniMax-AI/skills，提交 `60aaae52bb2af8162732751a4332f62a5fef518b`。

9 项：前端、全栈、着色器、PPT、Excel、Word、多媒体 CLI、音乐生成、歌单创作。各包 MOBILE.md 为手机执行入口，原许可与适用参考资料继续保留。

退出 Android、iOS、React Native、Flutter、PDF 桌面制作、GIF 贴纸六项预置。对应工具链不在 APK 中；不再把它们呈现为手机可执行技能。

Word 使用内置 Python 标准库创建、读取、替换及结构校验 DOCX。PPT 使用项目内 PptxGenJS 4.0.1 生成文件，并以 Python 检查真实包结构。两者提供 HTML 内容预览，预览不代表 Office 最终排版。Excel 使用现有 Python OOXML 脚本，退出 LibreOffice 重算。前端和着色器使用手机 HTML 预览；全栈使用 Node/Python，退出桌面容器依赖。媒体和音乐通过服务端 API 完成，歌单使用用户提供的资料，退出桌面播放器和应用扫描。

升级时将六项旧技能完整移至 `.dsh/retired-skills/minimax-mobile-v2`，退出活动目录，保留用户修改及附件。保留技能仅更新未修改的旧预置入口；尊重用户编辑和删除，新手机资源按需补齐。迁移标记最后写入，失败可以重试。安装仍由 LocalSkillStore 唯一负责，SKILL.md 最后发布。

媒体技能需要有效账户和相应权限，不自动继承模型配置凭据。尚未进行付费 API 或手机设备验收。

## 验证范围

新增官方入口能力、思考开关、两种协议、思考明细流式拼接与跨路由隔离、业务错误及技能升级/删除/编辑/重装/缺包回归。沿用全量单测、Harness 一致性、架构、静态、构建和功能树门禁；最终 PR 记录实际 CI 结果。

官方依据：
- https://platform.minimax.io/docs/api-reference/text-openai-api
- https://platform.minimax.io/docs/api-reference/text-anthropic-api
- https://platform.minimax.io/docs/guides/text-m3-function-call
- https://platform.minimax.io/docs/api-reference/anthropic-api-compatible-cache
- https://github.com/MiniMax-AI/skills
