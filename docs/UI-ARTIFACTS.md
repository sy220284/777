# UI 效果图

本目录保存当前 Android 界面的稳定视觉基准，用于设计复盘、回归比对和发布说明。

## 当前文件

| 文件 | 对应界面 |
|---|---|
| `docs/images/banner.jpg` | README 项目横幅 |
| `docs/images/current/navigation.png` | 当前导航与会话 |
| `docs/images/current/chat.png` | 当前角色聊天 |
| `docs/images/current/work.png` | 当前工作执行 |
| `docs/images/current/character-tuning.png` | 当前人物行为调节 |
| `docs/images/current/usage.png` | 当前模型价格与消耗 |

## 当前还应补齐的基准

后续主要界面发生视觉改动时，优先补齐：

- 聊天模式完整页面。
- 工作模式完整页面。
- 人物行为调节弹窗。
- 人物图集。
- 群聊成员管理。
- Token 统计首页。
- Token 请求日志 / 请求详情。
- 运行中心。
- 设置页。
- Runtime 诊断。

不为凑数量生成截图；只保存能承担设计验收或稳定回归的基准。

## 规则

1. 截图必须对应当前主线实现。
2. 主导航、信息层级或关键状态发生变化时，同步更新截图。
3. 同一页面的新稳定版本直接替换旧基准；确实需要历史对比时使用明确日期后缀。
4. 文件名使用小写短横线。
5. 截图不得包含真实密钥、账号、私人对话、通知正文、敏感文件路径或其他隐私数据。
6. 浅色 / 深色、窄屏、大字号等专项截图只有在承担实际回归价值时才入库。
7. 自动截图和参考 Harness 抓取工具说明见 `tools/capture/README.md`。

UI 设计原则见 [UI-UX.zh-CN.md](UI-UX.zh-CN.md)。
