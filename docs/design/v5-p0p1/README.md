# 神言神语 V5 · P0/P1 组件增补

这是来自资料库 `shenyan-ui-v5-full-scene-design-20261012.zip` 的 **增量设计源码**，直接复用 V5 原色板、五级材质和响应式规范；不是对 777 App 真实业务代码已经接入的声明。

- 打开 `v5-p0p1-components.html` 查看 20 个组件样例（P0 10、P1 10），可切换暖纸/烟熏/哑光主题。
- 打开 `v5-p0p1-state-matrix.html` 查看 97 种声明变体。
- 阅读 `V5-P0P1-COMPONENT-SPEC.zh-CN.md` 和 `V5-P0P1-COMPONENT-CONTRACTS.json` 获取组件契约与屏幕映射。
- `tests/v5_p0p1_qa.py` 包含结构、交互和浏览器自测；宿主浏览器可能受策略限制，不能将本机失败说成视觉验收通过。

新增文件不替换原 V5 的 `app.html`、`components-v4.html` 和 `compose-v5/`。生产 Android 需另外在 `Sy*` 体系接入真实多模态、工具、任务、媒体与附件状态，严格禁止用演示数据伪装结果。

Figma：已创建 [V5 P0/P1 组件增补文件](https://www.figma.com/design/BbpTJLzT2l9TFlOhZJnyAg)，但因 Starter MCP 调用额度触顶，目前未写入 20 个可编辑组件；应以本目录源码为当前已交付设计参考。
