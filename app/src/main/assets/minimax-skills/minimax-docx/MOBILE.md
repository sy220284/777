# 手机端 Word 文档
使用内置 Python 标准库处理 OOXML，不需要 .NET、Office 或 LibreOffice。
## 创建
将标题、段落和表格写入 JSON：
{"title":"报告","paragraphs":[{"text":"背景","style":"Heading1"},{"text":"正文"}],"tables":[[["项目","结果"],["测试","通过"]]]}
运行 `python scripts/mobile_docx.py create content.json report.docx`（脚本路径以本技能目录为基准）。同时生成 report.html，使用工作区预览检查内容、中文、表格和溢出；这份 HTML 用于内容检查，不冒充 Word 的逐像素排版结果。
## 读取与编辑
`python scripts/mobile_docx.py read input.docx` 提取正文与表格文字。
`python scripts/mobile_docx.py replace input.docx output.docx --old 原文 --new 新文` 修改跨文本片段的字串，保留未修改包成员和段落格式。不将复杂文档重新转成简化 JSON，以免丢失图片、页眉或关系。
## 验证
`python scripts/mobile_docx.py validate output.docx` 核对 ZIP、全部 XML 和正文必需部件。交付实际文件和 HTML 内容预览，不能声称完成 Office 渲染。
按需读取 upstream/references 的 OOXML、排版和设计资料；不执行其中旧桌面流程。
