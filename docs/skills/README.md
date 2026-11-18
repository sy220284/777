# 777 技能资料库

本目录集中归档从西瓜同学公开 GitHub 仓库取得的四套技能，保留各技能原始目录结构，可供设计评审、资料检索和需要时的独立安装。

| 技能 | 文件入口 | 上游仓库 | 本次快照（提交 SHA） |
| --- | --- | --- | --- |
| UI 设计与交互 | [finesse-ui](./finesse-ui/SKILL.md) | [mouse-lin/finesse-skill](https://github.com/mouse-lin/finesse-skill) | `c7a91448baf6e98fecf542915cdb7e3155bddc45` |
| 工作台架构师 | [finesse-brief](./finesse-brief/SKILL.md) | [mouse-lin/finesse-brief](https://github.com/mouse-lin/finesse-brief) | `d1f45618b605587b882a20d72eb0d8ec666990bd` |
| 视频审美与动效 | [frame-smith](./frame-smith/SKILL.md) | [mouse-lin/frame-smith](https://github.com/mouse-lin/frame-smith) | `44b7e716971b46ab801f302d3ba34c5b61442e9f` |
| 专业术语 | [finesse-term](./finesse-term/SKILL.md) | [mouse-lin/finesse-term](https://github.com/mouse-lin/finesse-term) | `e5abc56382ab0c393aa11f9d877c6db91d9956c` |

## 归档边界

- 完整复制上游仓库 `skills/<技能名>/` 下的全部文件，包括 `SKILL.md`、引用文档、数据、示例、脚本、模板及技能目录自带的图片素材。四套技能分别包含 85、10、16、10 个主体文件，合计 **121** 个。
- 各技能目录另附原作者的 `LICENSE`（MIT），保留署名及许可证原文。
- 上游面向不同客户端的重复镜像（如 `.trae/`、`.codebuddy/` 等）和仓库根目录的展示、构建文件不重复导入，避免多份同内容产生维护分叉。
- 具体来源与版本另见 [SOURCES.json](./SOURCES.json)，后续更新可按其中的固定提交版本比对。

## 使用方式

这里只作为 **777 的文档资料库**，不修改 Android 构建、运行时或默认技能扫描路径，也不默认执行上游脚本。需要给具体 Agent 使用时，将整个 `docs/skills/<技能名>/` 目录安装到对应 Agent 支持的技能目录，并按该技能 `SKILL.md` 所述操作；直接复制单个 `SKILL.md` 会遗漏其引用、脚本和示例。

归档日期：2026-11-18。
