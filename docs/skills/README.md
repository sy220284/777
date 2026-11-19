# 777 技能资料库

本目录归档从西瓜同学公开 GitHub 仓库取得的四套技能，保留原始目录结构，并作为 Android App 内置技能包的版本来源。

| 技能中文名称 | 技能标识及文件入口 | 上游仓库 | 本次快照（提交 SHA） |
| --- | --- | --- | --- |
| 界面视觉与交互设计 | [finesse-ui](./finesse-ui/SKILL.md) | [mouse-lin/finesse-skill](https://github.com/mouse-lin/finesse-skill) | `c7a91448baf6e98fecf542915cdb7e3155bddc45` |
| 工作台产品规划 | [finesse-brief](./finesse-brief/SKILL.md) | [mouse-lin/finesse-brief](https://github.com/mouse-lin/finesse-brief) | `d1f45618b605587b882a20d72eb0d8ec666990bd` |
| 视频分镜与动效设计 | [frame-smith](./frame-smith/SKILL.md) | [mouse-lin/frame-smith](https://github.com/mouse-lin/frame-smith) | `44b7e716971b46ab801f302d3ba34c5b61442e9f` |
| 界面术语与需求表达 | [finesse-term](./finesse-term/SKILL.md) | [mouse-lin/finesse-term](https://github.com/mouse-lin/finesse-term) | `e5abc56382ab0c393aa11f9d877c6db91d9956c` |

## 技能用途与中文使用说明

| 技能 | 适用范围 | 使用方式 |
| --- | --- | --- |
| **界面视觉与交互设计** | 网页、移动端界面、组件、弹窗、动效及设计质量审查 | 提供界面截图、组件或产品需求，按设计规则制作或优化方案并检查布局、交互和无障碍 |
| **工作台产品规划** | 个人工作台、业务后台、功能模块、页面层级及数据结构 | 说明目标用户、业务对象及关键任务，形成可交付的工作台规格，再交给界面设计或开发 |
| **界面术语与需求表达** | 界面现象、组件命名、交互流程、状态与验收要求 | 输入自己的描述或陌生术语，得到准确中文术语、需求表达及与当前问题相关的注意点 |
| **视频分镜与动效设计** | 宣传视频、短视频、分镜、节奏、转场及动态视觉 | 提供主题、时长、画幅和素材，编排镜头时间线；真实渲染使用设备已有的视频运行工具 |

各项技能在 App 的能力中心显示中文名称及“适用范围/使用方式”简介。打开技能详情可阅读位于 `SKILL.md` 开头的**中文使用指南**，随后为保留的上游完整规则；技能内部标识保持不变，以保证既有调用和引用路径继续可用。

## 归档边界

- 完整复制上游仓库 `skills/<技能名>/` 下的全部文件，包括 `SKILL.md`、引用文档、数据、示例、脚本、模板及技能目录自带的图片素材。四套技能分别包含 85、10、16、10 个主体文件，合计 **121** 个。
- 各技能目录另附原作者的 `LICENSE`（MIT），保留署名及许可证原文。
- 上游面向不同客户端的重复镜像（如 `.trae/`、`.codebuddy/` 等）和仓库根目录的展示、构建文件不重复导入，避免多份同内容产生维护分叉。
- 具体来源与版本另见 [SOURCES.json](./SOURCES.json)，后续更新可按其中的固定提交版本比对。

## 使用方式

上述四套原始资料仍完整保留在本目录。Android App 同时内置全部 125 个来源文件（约 4.68 MB），打包路径为 `app/src/main/assets/creative-skills/<技能名>/`，包括参考资料、脚本、模板与二进制图片。

安装或更新 App 后，`LocalSkillManager` 会通过 `LocalSkillStore` 将四套技能一次性注册到本机 `.dsh/skills/`：能力中心可查看与管理，模型可按 `skill(name=...)` 读取完整规则，并按需读取 `references/`、`data/`、`examples/`、`scripts/`、`templates/` 等文字资料。运行时的 `SKILL.md` 增加中文名称、适用范围、使用方式及中文使用指南；`UPSTREAM-SKILL.md` 保留未改动的上游原文。

用户修改过的技能规则和资源在升级时保留；用户主动删除的内置技能不会在以后每次启动时自动恢复，但可从预置技能目录重新安装。文档、脚本和素材均随包离线可用；脚本由现有执行器和权限控制，不会因内置而自动运行。视频渲染等依赖仍需用户实际配置对应工具。

归档日期：2026-11-18。
