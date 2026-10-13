# V6「暖纸墨线」原创功能入口图标

本次为 PR #664 **重新设计的 26 枚语义图标**，不是 Feather 路径换色或同名别名。

- **唯一绘制约束**：24×24 viewBox、圆端圆角、1.75 单位线宽、最小留白 3dp；场景通过 SyIconBox 和 SyIconButton 承托，实际着色跟随主题，无需每种主题复制一份位图。
- **入口适用范围**：侧栏、收缩轨、会话搜索结果、设置一级导航及其直接相关二级入口。返回、关闭、菜单、状态、编辑等操作性控制仍使用通用图标，避免把功能语义与操作语义混为一类。
- **资源源头**：[sy-entry-icons.svg](sy-entry-icons.svg) 为人可审阅的矢量总览；运行时代码 [SyEntryIcons.kt](../../../app/src/main/java/com/labteto/dshmobile/ui/components/SyEntryIcons.kt) 同步 SVG path，完整列表有对应的 JVM 几何测试。
- **设计方向**：杏灰衬底、墨棕主体、造型轻微非对称，拒绝照搬 Feather 的 2px icon stroke 和直白剪影。浅/深/哑光的材质底板由系统令牌控制；源图只保留抽象黑色 path 供动态 tint。
- **交互**：保留既有 onClick、contentDescription、最小 48dp 点击热区、按压/悬停/减少动态效果语义；本次只改视觉层，禁止改功能路由。
- **图标映射**：人物 → `SyEntryIcons.Persona`；新对话 → `SyEntryIcons.NewSession`；项目 → `SyEntryIcons.Projects`；任务 → `SyEntryIcons.Tasks`；设置 → `SyEntryIcons.Settings`；搜索 → `SyEntryIcons.Search`；人物画廊 → `SyEntryIcons.Gallery`；群聊 → `SyEntryIcons.GroupChat`；日记 → `SyEntryIcons.Diary`；工作区文件 → `SyEntryIcons.WorkspaceFiles`；运行中心 → `SyEntryIcons.RunCenter`；工具 → `SyEntryIcons.Tools`；用量 → `SyEntryIcons.Usage`；沉浸阅读 → `SyEntryIcons.Reader`；额度 → `SyEntryIcons.Quota`；外观 → `SyEntryIcons.Appearance`；记忆 → `SyEntryIcons.Memory`；会话存储 → `SyEntryIcons.SessionStorage`；模型 → `SyEntryIcons.Models`；性能 → `SyEntryIcons.Performance`；账户 → `SyEntryIcons.Account`；通知 → `SyEntryIcons.Notifications`；权限 → `SyEntryIcons.Permissions`；高级 → `SyEntryIcons.Advanced`；更新 → `SyEntryIcons.Updates`；对话风格 → `SyEntryIcons.ChatStyle`。
- **验收**：Gradle 单测及 Android CI 验证加载，不等于所有实际主题/壁纸设备截图达标；需要在 38 屏台账中按主题抽查入口对比。
- **Figma 状态**：独立设计文件已建立，但 Figma Starter MCP 配额不足时不能承诺已写入组件；以版本库 SVG + Kotlin 同源文件为本轮交付源。
