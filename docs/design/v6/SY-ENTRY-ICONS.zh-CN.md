# V6「暖纸墨线」入口图标：24 枚现行

PR #664 设计的 **26 枚路径**仅在 Kotlin 生产代码中保留；按当前主线入口审计，**实际有效的功能入口图标为 24 枚**。`Projects` 与 `RunCenter` 属于已移除的独立项目/运行中心入口，仅供历史代码与迁移追溯，不得放进现行侧栏、设计稿或新导出素材。

- **唯一绘制约束**：24×24 viewBox、圆端圆角、1.75 单位线宽、最小留白 3dp；场景通过 SyIconBox 和 SyIconButton 承托，实际着色跟随主题，无需每种主题复制一份位图。
- **入口适用范围**：侧栏、收缩轨、会话搜索结果、设置一级导航及其直接相关二级入口。返回、关闭、菜单、状态、编辑等操作性控制仍使用通用图标，避免把功能语义与操作语义混为一类。
- **资源源头**：[sy-entry-icons.svg](sy-entry-icons.svg) 为人可审阅的矢量总览；运行时代码 [SyEntryIcons.kt](../../../app/src/main/java/com/labteto/dshmobile/ui/components/SyEntryIcons.kt) 保留全部原始路径；当前矢量总览实物仅输出 **24 枚现行图标**，不再绘制已移除的两枚。路径存在不代表已废弃功能还应有入口。
- **设计方向**：杏灰衬底、墨棕主体、造型轻微非对称，拒绝照搬 Feather 的 2px icon stroke 和直白剪影。浅/深/哑光的材质底板由系统令牌控制；源图只保留抽象黑色 path 供动态 tint。
- **交互**：保留既有 onClick、contentDescription、最小 48dp 点击热区、按压/悬停/减少动态效果语义；本次只改视觉层，禁止改功能路由。
- **图标映射**：人物 → `SyEntryIcons.Persona`；新对话 → `SyEntryIcons.NewSession`；任务 → `SyEntryIcons.Tasks`；设置 → `SyEntryIcons.Settings`；搜索 → `SyEntryIcons.Search`；人物画廊 → `SyEntryIcons.Gallery`；群聊 → `SyEntryIcons.GroupChat`；日记 → `SyEntryIcons.Diary`；工作区文件 → `SyEntryIcons.WorkspaceFiles`；工具 → `SyEntryIcons.Tools`；用量 → `SyEntryIcons.Usage`；沉浸阅读 → `SyEntryIcons.Reader`；额度 → `SyEntryIcons.Quota`；外观 → `SyEntryIcons.Appearance`；记忆 → `SyEntryIcons.Memory`；会话存储 → `SyEntryIcons.SessionStorage`；模型 → `SyEntryIcons.Models`；性能 → `SyEntryIcons.Performance`；账户 → `SyEntryIcons.Account`；通知 → `SyEntryIcons.Notifications`；权限 → `SyEntryIcons.Permissions`；高级 → `SyEntryIcons.Advanced`；更新 → `SyEntryIcons.Updates`；对话风格 → `SyEntryIcons.ChatStyle`。
- **验收**：Gradle 单测及 Android CI 验证加载，不等于所有实际主题/壁纸设备截图达标；应在现行 7 路由与 21 个设置二级目的地的使用场景中核对，不再以历史固定屏幕数量作为验收分母。
- **Figma 状态**：独立设计文件已建立，但 Figma Starter MCP 配额不足时不能承诺已写入组件；以版本库 SVG + Kotlin 同源文件为本轮交付源。

## 导出规则与剩余代码

- 生产代码为了兼容保留 `SyEntryIcons.Projects`、`SyEntryIcons.RunCenter` 的路径定义，不表示侧栏仍有这些页面；此处不擅自删除 Kotlin 符号或改写 Feature 行为。
- **仓库 SVG 总览及离线单文件原型均只导出 24 枚现行入口 SVG**，旧两枚不进入可点击页面、组件样张或默认素材目录。
- 同一功能的 24dp viewBox / 1.75 描边 / 48dp 命中区 / 三主题 tint 继续沿用；设置检查更新属于页面动作而非独立导航页。
