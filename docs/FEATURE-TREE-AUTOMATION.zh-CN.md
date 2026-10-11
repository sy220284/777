# 功能树自动维护与复核

## 工作方式

GitHub Actions「自动维护功能树」在 `main` 更新、定时任务、手动触发和 PR 时扫描全仓库代码/配置文件，分别清点生产 Kotlin/Java、测试、路由、可见界面与工作流。不依赖 PR 描述推断产品能力。

执行顺序：**全量扫描 → 不写文件的预览与候选分类 → 符号/归属/测试证据复核 → 刷新中文功能树与交互视图 → 独立复核 → 创建或更新单一维护 PR**。复核失败不得提交。PR 上的扫描生成可下载的 Actions 检查产物；已有分支内的实际代码与当前主线需独立核实。自动维护操作不得直接绕开主线合并和发布门禁。

持续维护文件：
- `FEATURE-TREE.zh-CN.md`：中文功能树。
- `FEATURE-TREE.zh-CN.html`：同层级交互树，对有直接测试引用的节点标记“局部测试证据”，不作全部验收承诺。
- `FEATURE-SOURCE-INDEX.auto.json`：扫描范围、路由、界面候选与源码内容指纹。
- `FEATURE-REVIEW.auto.json`：**每个末级功能的证据级别**、批准的变更、待确认变更及验证边界。
- `FEATURE-TREE-SOURCE-REGISTRY.json`：已复核的稳定功能身份和源码证据。
- `ACCEPTANCE-MATRIX.json`：仅记录已存在的实际测试证据；移动功能时对齐路径，不新增虚构测试。

## 什么条件才可自动确认功能

扫描器不会根据文件名、类名、文案相似度或模型猜测来删除/新增业务功能。只有源码明确登记、**同时通过独立证据核查**的功能变化才会写入树。例如对应生产入口附近使用：

```kotlin
// FEATURE_TREE: {"id":"chat.diary.revise","path":"第一层｜产品功能与界面 / 聊天功能 / 人物与人设 / 日记纠正","entry":"reviseDiary","owner":"CHAT","test":"app/src/test/java/example/DiaryTest.kt#reviseDiaryWorks"}
```

上面仅是字段格式示意，路径及方法必须在对应仓库中实际存在才会获准。检查要求：稳定唯一 `id`；`path` 末级必须是中文名称；`entry` 必须为当前生产源码的真实声明；`owner` 必须归属七个架构业务模块；`test` 必须指向实际存在的 Kotlin `@Test` 方法且引用对应入口。

身份未变而 `path` 改变时，自动移动/改名并迁移已有验收索引。首次接管旧节点并同时重命名时，可注明 `previous_path`。已确认的功能退役需在仍存留的生产源码中声明 `action: "retire"`、原 `id` 和 `path`，以及中文 `reason`；仅删除文件或登记会保留旧节点并报告“待复核”，避免误删。

**重要区别：通过这些检查，只能证明源码声明、功能归属与测试引用有一致证据，仍无法证明功能实际效果、启动链路、设备交互和所有异常恢复均已完成验收。** 未登记的历史功能保持原有层级；源代码尚无法自动确定的变化只列候选。

## 本地/CI 校验

```bash
python3 .github/scripts/sync-feature-tree.py --self-test
python3 .github/scripts/sync-feature-tree.py --preview
python3 .github/scripts/sync-feature-tree.py
python3 .github/scripts/sync-feature-tree.py --check
python3 .github/scripts/check-feature-tree-sync.py
python3 .github/scripts/check-acceptance-matrix.py
```

预览不会修改文件；后续同步必须执行双视图、全量文件数量、稳定内容指纹、逐叶证据和已有测试方法验证。检查过程有任何缺口就拒绝提交。生成文档的提交本身不会改变实际产品代码；仓库 CI 与真机验收仍按 `VALIDATION.md` 执行。

源码内容指纹是确定性的，不受仅修改生成文档所造成的 Git SHA 变化影响。功能变化必须在修改它的同一个 PR 中同步核对，不能依赖下一次定时任务补漏。
