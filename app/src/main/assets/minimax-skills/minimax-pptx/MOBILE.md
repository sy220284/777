# 手机端演示文稿
通过内置 Node 和纯 JavaScript 的 PptxGenJS 生成 PPTX，不依赖桌面 Office 或 LibreOffice。
先核对当前项目工具说明；已有 PptxGenJS 时复用。否则使用项目 npm 在工作区安装已回归版本 `pptxgenjs@4.0.1`，不要全局安装或改 PATH。
输入 JSON：{"title":"演示标题","slides":[{"title":"第一页","body":["内容一","内容二"]}]}。
运行 `node scripts/mobile_pptx.cjs content.json result.pptx /绝对路径/node_modules/pptxgenjs`。交付 result.pptx 与 result.html，后者可在 777 工作区预览和翻页。
`python scripts/pptx_inspect.py result.pptx` 检查包结构、XML、页数和文字。PPTX 的最终 Office 排版不与 HTML 预览混同。
已有模板按 upstream/references/editing.md 的 OOXML 方法修改，只使用内置 Python 的 zipfile 和 ElementTree；不得执行旧资料中的桌面截图、Office 转换或 markitdown 流程。
按需读取 upstream/references 里的版式和配色资料。
