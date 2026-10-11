# 777 本机工作区工具指南（App 管理，自动更新）

此文件随 777 版本刷新；`.dsh/tools/README.md` 由用户维护且不会被覆盖。实时能力、授权和安装状态以当前 `capability_search`、`environment_info` 和实际工具表为准；文档只说明正确操作路径。

这里存放你或智能体创建的可复用脚本；Node.js、Python、Git 由 777 的运行时管理，语言服务器由 LSP 组件管理器管理。它们不需要复制进本目录。

## 正确使用顺序
1. 先调用 `capability_search` 搜索所需能力；查看本机环境用 `environment_info`，代码智能状态用 `lsp_status`。
2. “发现候选”只说明找到了入口；真正可用以语言服务器启动并成功完成查询为准。请勿通过创建 PATH 同名占位文件来让检测变绿。
3. 查定义用 `lsp_definition`；引用/调用关系用 `lsp_references`；类型用 `lsp_hover`；文件/全局符号分别用 `lsp_symbols` / `lsp_workspace_symbols`；错误用 `lsp_diagnostics`。
4. LSP 先使用项目里兼容的 `node_modules`，然后使用 App 已安装的共享服务；都没有时，TypeScript/JavaScript 或 Python 的首次语义调用可申请按需安装。拒绝、失败时用 `read` / `grep` / 构建测试继续任务。
5. `lsp_diagnostics` 返回“待发布/超时”不等于“没有诊断”；应检查状态和明确错误，不要反复做同一无效操作。

## 自建脚本
- 先用 `read` 查看脚本，检查参数和实际运行时；JavaScript 使用 `node .dsh/tools/脚本.js`，Python 使用 `python3 .dsh/tools/脚本.py`。
- Android 应用可写目录下的 shell/npm bin 包装文件可能不能直接执行；使用 777 自带的 Node/Python 解释器运行对应源文件。
- 避免自行下载另一套运行时、反复修改 PATH、在 runtime/bin 放占位脚本、解 APK 猜命令。工具安装/卸载需要的授权由 777 管理。

此文档供导航；工具和权限以本轮实际提供的工具定义、`capability_search` 与运行时检查为准。用户自己创建的脚本及说明由用户管理。
