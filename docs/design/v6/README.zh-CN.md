# 神言神语 V6 · 现行手机 UI 设计源

> [UI 3.0 权威规范](../UI-3.0.zh-CN.md)决定跨 UI 包接入、替换与验收；V6 只决定当前 Android 手机包内部的视觉和交互。**主线真实 Compose + 已合并 PR 为事实源，任何文档、Figma 和历史素材不得把已删除的入口带回来。**

## 当前唯一有效交付入口

- **[V6 现行实现反向设计规范](V6-CURRENT-IMPLEMENTATION.zh-CN.md)**：三主题的真实 ARGB、L1–L5 材质、区域透明度、尺寸、动效、触觉、7 条顶层路由、21 个设置二级页、工作/聊天/工具等交互与退役清单。
- **[现行页面与退役入口机器清单](current-ui.json)**：`LocalFeatureCatalog` 和 `SettingsDestination` 的对应关系及未合并 UI PR，不得以旧 38 场景序号替代。
- **[打开当前手机单列交互设计稿](mobile/app.html)**：本轮按主线重新组织，只允许当前仍有真实入口的页面/状态。它是离线交互投影，**不是 Android 真实截图，也不是 Figma 已同步证明**。
- [可复用 SVG 入口图标](sy-entry-icons.svg)、[图标源文件映射](SY-ENTRY-ICONS.zh-CN.md)、[文件类 SVG](assets/file-icons-preview.svg)、[逐场景验收台账](STATUS.zh-CN.md)。图标素材是否仍用于现行入口须对照 `SyEntryIcons.kt` 和实际调用；存在素材不代表应恢复旧按钮。

## 以代码为准的视觉归属

`ShenyanFoundation.kt` → `SyColors / SyMetric / SyMotion / SyMaterialPolicy`；`ShenyanMaterialColor.kt` → 动态壁纸区域取色和文字对比；`ShenyanMaterial.kt` → 五层材质和实际模糊；`AppBackground.kt / SySceneBackgroundSurface` → 全局连续场景；`ui/shenyan` 只做代理桥接。禁止每个页面自己生成一套色板、过度透明底、过渡曲线或带背景的图标盒。

当前只有 Android 手机设计，原尺寸参考 393/412dp，另须覆盖 320/360dp 与系统放大字体。暖纸、烟熏玻璃、哑光黑为 V6 三个视觉模式；系统跟随属于设置策略，不是第四套独立材质。只要最终编译并不等于全场景视觉验收。

## 已废弃来源与待合并改动

旧 `shenyan-v6-mobile-unified-20261013.zip`、早期 38 场景 HTML/截图和 V5/V4 文件只可从 Git 历史追溯，**不再是现行设计包、视觉规范或功能入口的输入**。其中含已退役的三选导航、独立运行中心/项目、工作五段、独立检查更新页等；不能重新发布为有效设计素材。

已合并的界面修正主要见 #693、#694、#696–#704、#708、#709、#710；**#711 技能/插件卡片修复仍是待合并**，验收必须核对其最终状态；**#705 UI 3.0 R4 仍为 Draft**，不能把独立完整 UI Pack 宣称为已上线。两项边界及风险详见现行规范和台账。

Figma 当前 Starter MCP 配额耗尽，未写入可编辑画布；仓库设计稿和文档不等于 Figma 同步。待恢复后按代码变量、组件和全页面单列设计逐项对齐，记录节点和对照截图再宣布完成。
