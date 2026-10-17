# ui-pack-zhaoxi · 朝夕独立 UI 包（E8.16 正确性修复版）

> 方向更新（E8.16）：PR #738（移除旧 V6 UI，Draft）确认朝夕的正确形态是
> **全新独立 UI 接管应用**——不与 V6 并列、不带回旧组件，待 #738 合并后经中立接口接入。

对齐**已合并**的 PR #705（merged 2026-10-17，head `283230d5`，1019 commits）的实际形状：
`UiApplicationScenePack`（class + 构造注入 Feature Ports）/ 双门禁准入 / `UiApplicationPackSelection` /
`LocalUiSceneCatalog` **78 场景**（75 非 hostProvided）/ `LocalUiConnectedCapabilities` **37 能力**。

**合并版关键事实（E8.8→E8.11 三次重构的动因）**

- R4 收敛为**单一独立 V6**：旧 Legacy Shell 已实际删除，AppRoot 唯一装配 `LocalV6StandaloneViewModel`；
  会话级 `ConversationUiPack` 契约已从 `:ui-host` 删除（Next 测试实现一并移除）。
- 接入标准即 `V6ApplicationScenePack`：**class 构造注入端口，场景与能力按注入端口条件声明**，
  不存在静态全量 manifest；`UiSceneRenderScope` 的导航只是提案，Host 唯一提交。
- 契约新增 `SceneFrame(state, content)`：Pack 自有的视觉连续性层（场景转场归包，导航归 Host）。
- 开发期自查可用 `UiApplicationPackRegistry.inspectArchitectureSlice` 探针（不许可生产准入）。

## 结构

```
ui-pack-zhaoxi/
├── build.gradle.kts                     # 仅依赖 :ui-host + :ui-contract（同 ui-pack-v6）
└── src/main/kotlin/com/labteto/dshmobile/uipack/zhaoxi/
    ├── ZhaoxiUiPackManifest.kt          # 全量目标清单：75 场景 + 37 能力（条件构建的截取源）
    ├── ZhaoxiPorts.kt                   # ZhaoxiConversationPorts / ZhaoxiSecondaryPorts（对齐 V6 形状）
    ├── ZhaoxiApplicationScenePack.kt    # 应用级 Pack：class + 条件 manifest/renderers + SceneFrame
    ├── ZhaoxiConversationScaffold.kt    # conversation.* 域共享外壳（实装底座）
    ├── components/                      # Zx* 组件库 32 个（Primitives/Feedback/Conversation/Shell/Empty）
    ├── scenes/                          # 75 场景实装（SceneCommon/Conversation/Work/Persona/Slot/
    │                                    #   Automation/Settings/Shell/UiDocument 八个分域文件）
    └── theme/
        ├── ZhaoxiTheme.kt               # 主题层（含环境光/壁纸背景令牌）
        └── ZhaoxiTokens.kt              # 生成产物（tools/generate_tokens.py）
src/test/kotlin/.../ZhaoxiPackCoverageTest.kt  # 包结构一致性回归骨架（对齐 V6FullSceneCoverageTest 职责）
```

## 准入双门禁

| 门禁 | 内容 |
|---|---|
| `checkCompatibility` | 契约版本 / 场景 / 能力 / 窗口 / 无障碍 / 明暗 |
| renderer 覆盖四查 | missing / undeclared / hostService / unknown 全空才 `Admitted` |

- `renderers.keys` 必须恰好等于 `manifest.scenes`；hostProvided 三场景（conversation.attachments /
  shell.notifications / system.permissions）由宿主渲染，声明即违规。
- 持久选择走宿主 `UiApplicationPackSelection`：写失败不伪报、存档失效显式拒绝 + 可选稳定项，
  **不静默回退 V6**。

## 接入步骤（生产）

1. `settings.gradle.kts` 加 `include(":ui-pack-zhaoxi")`。
2. App 组合根注入端口，构造 `ZhaoxiApplicationScenePack(...)`；注册进
   `UiApplicationPackRegistry`（与 V6 并列候选）。
3. 开发期用 `inspectArchitectureSlice("zhaoxi", requirements)` 自查覆盖与越界；
   `Rejected(Incomplete)` 时按 `coverage` 四查出明细。
4. 组件按 `components/` 逐个从占位替换为实装（签名见 compose/朝夕-Compose实施规格.md）。

## 与"替代 V6"标准的差距（阻塞项）

- **设计层缺口（E8.12 审计登记，E8.13 已补齐原型）**：4 个场景曾无界面设计（shell.onboarding /
  work.plan_review / automation.persona_interaction / chat.gallery_portrait +
  gallery_story / persona_transfer 入口），组件规格（compose 规格 §1.5）与原型页面
  （45 视图 / 22 Sheet）均已落地。详见《能力界面覆盖审计-E8.12》。
- **75 场景实装**：✅ E8.14 完成分发，E8.16 完成正确性修复——契约签名逐一对照 main
  （命令 identity 补全、回执检查后才清草稿、非法导航目标清零、非法 requirement 字段移除、
  空列表显式降级）；`zxMaterial` 材质与 `SceneFrame` 转场已从注释落地为实装。
  数据全部经端口 Flow/挂起读取绑定，动作只经端口 submit，回执只呈现不伪造。
- **行为契约回归**：V6 有 V6FullSceneCoverageTest / LocalV6StandaloneBootTest 等证据；
  朝夕目前只有结构一致性骨架（ZhaoxiPackCoverageTest），173 动作行为契约回归待补。
- **组合根接线**：App 侧需将真实 Feature Ports 注入 ZhaoxiConversationPorts /
  ZhaoxiSecondaryPorts（字段已对齐 V6 形状，接入点即 `LocalApplicationUiHost` 的候选注册）。
  少数场景的运行时参数（当前人物名、模型清单、群成员列表）需组合根在导航提案中携带，
  E8.14 以 requirement 提取 + 空态降级处理。
- **设备验收**：Android 16/17 仪器化、真机视觉/无障碍（与 V6 同等欠账，合并版 CODE_ONLY
  未覆盖，不代表不需要）。
- 包未接入主线构建，编译验证留待 `include(":ui-pack-zhaoxi")` 后进行；
  接口面以合并版 `:ui-host`（包 `com.labteto.dshmobile.uihost`）与 `:ui-contract` v1 源码为准。
