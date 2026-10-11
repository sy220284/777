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

## 旧版已删除功能的自动识别与更新

除主动新增的稳定 `FEATURE_TREE` 登记外，扫描器还**反向检查历史 447 项源码证据台账**：逐条对比现行功能树和当前 Git 工作目录下的生产源码。结果单独记录在 `FEATURE-REVIEW.auto.json`：

- `obsolete_historical_evidence`：曾经列出的功能，现行树已经没有该路径；区分“旧生产文件全部消失”“旧实现仍存在，可能改名/合并”及“证据不足”。历史路径消失不直接证明产品能力已经删除。
- `current_tree_outdated_sources`：现行功能仍在，但历史记录指向的生产源码文件已消失。这类功能必须追查新入口；禁止仅因文件删除就删除节点。
- `approved_legacy_retirements` 和 `legacy_retirements_requiring_review`：独立审批后准予删除，以及因为仍有源码/测试/历史来源疑点而拒绝删除的项目。

旧功能没有 `FEATURE_TREE` 登记也可以被确认为退役，但必须先在 `docs/FEATURE-TREE-RETIREMENTS.json` 添加审核记录。例如：

```json
{
  "version": 1,
  "approvals": [
    {
      "path": "第一层｜产品功能与界面 / 旧模块 / 已移除功能",
      "source": "app/src/main/java/example/RemovedFeature.kt",
      "symbol": "RemovedFeature",
      "reason": "已核对新实现，原入口确认完全退出"
    }
  ]
}
```

这是数据格式示例，不能直接用于真实功能。工作流会核实原生产源码确曾出现在历史证据或稳定登记中；检查**全部现存 Kotlin/Java 生产源码中无原入口声明**、相关 Kotlin/Java 测试没有引用该入口、验收映射已处置、节点仍为末级功能且没有当前的源码登记。任何一项不满足，就维持旧节点并列出拦截原因。审核记录保留以便追溯；再次运行不会重复删除。

对历史功能已有明确替代实现的情况，应先维护对应的新功能及测试映射，再批准删除旧节点。扫描无法通过“找不到旧文件”证明功能彻底不存在，也不应把更名/合并误判为退役。对于已不在树内的旧台账记录，只标记历史失效，不会错误地重新新增到树中。

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
