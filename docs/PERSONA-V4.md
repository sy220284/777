# 人物档案 V4 · 实施说明

> 状态：PR #604 Draft，尚未合并；以最新 CI 和设备验证为准。本文件与架构 3.0 保持单一功能所有权。

## 数据边界

- 核心身份：`name`、`franchise`、`coreIdentity`。
- 人物事实：`facts: List<CharacterFact>`；每项包含稳定ID、category、content、关联事实ID、主观视角、剧情阶段、来源类型与可选出处。
- 标准分类：personality, selfNarrative, identityGap, valuesAndTradeoffs, subjectiveBeliefs, biography, definingChoices, emotionalImprints, lifeGravity, unfinishedBusiness, relationships, limitsAndCosts, sensorySignature, preferencesAndHabits, voiceStyle, customFacts。
- 人物背景与原作：已有 `PersonaLoreEntry`，继续按需召回。人物事实的 `temporalScope` 仅使用故事显式 `storyStage` 匹配，绝不借用场景钟点。图集中按故事编辑剧情阶段 ID，保存后重新进入该故事时从归档上下文装载。未设阶段时隐藏阶段限定事实；世界书继续按剧透等级和关键词检索。完整设备回归仍以CI和真实执行记录为准。
- 当前情绪、主动性、注意、关系、记忆、人物成长：由独立 Chat 运行时持有，禁止写成预设回复脚本。
- 保存、编辑、AI与预设在新 V4 路径消费人物事实。关键运行时的人物底色、生活、注意力和行为提示已改为优先读取 V4 事实，避免已存在事实时从旧描述恢复冲突内容。仓库仍保留部分旧结构和非关键链路引用，尚需审计并最终移除，不能宣称V4结构已完全纯化。

## 写入和检索

人物编辑器默认显示姓名、作品来源、核心身份、稳定性格、价值取舍、经历、重要关系。高级编辑增加其余12类、可自定义类别及原作世界书。AI仅根据依据生成相应事实，无依据字段留空。提示词稳定层放人物身份和关键价值；深度事实随当前问题和已知故事摘要按需注入。自动补全保留用户编辑过的事实。

## 数据格式与兼容政策

按用户要求，不维持旧人物卡和旧图集文件自动升级：
- 本机人物库路径：`personas-v4.json`，内部版本4。
- 人物图集：`persona-gallery-v6.json` 独立文件；图集文档现行内部版本仍5。
- 分享及档案导入：仅接受schema 4；Markdown/Word只解析V4载荷。
- 旧版数据不会被自动读取，也不会由新实现负责迁移。独立 Chat 会话历史遵循各自现行契约。

## 回归范围

21名预设、编辑与AI生成、完整/精简分享、人物合并、JSON/Markdown/Word导入导出、短期/长期人物投影、故事和知识边界、多人对话、Android UI交互、旧版本拒绝，以及模型Token/缓存回归。

## UI设计交付状态

Figma工作文件：https://www.figma.com/design/Paa3Wl36qSt6jcrn2Ppv1V 。已创建文件，但由于Figma Starter MCP限额，画布设计尚未完成。当前以Compose人物编辑器实现为UI基线，Figma后续需在额度可用时同步。
