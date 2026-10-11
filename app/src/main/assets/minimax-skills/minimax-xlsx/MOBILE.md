# 手机端 Excel 表格
使用内置 Python 标准库和 upstream/scripts 中 ZIP/XML 工具读取、编辑与检查 XLSX。
按需读取 upstream/references 的创建、编辑、读取、公式和 OOXML 资料；依赖实际存在时才使用其他 Python 包。
创建工作簿时将内容写为 JSON：{"rows":[["项目","数量"],["甲",2],["合计",2]]}，运行 `python scripts/mobile_xlsx.py create content.json result.xlsx`。脚本使用附带模板、写入行列并清理 XML 注释；不要安装 LibreOffice、Excel 或桌面转换器。
对公式使用 formula_check.py 检查引用与语法，并用 Python 实际计算关键合计；需要即时显示计算结果时将实算值写入对应公式的缓存值，不能将未计算结果宣称为已重算。
使用 `python scripts/mobile_xlsx.py read result.xlsx` 提取单元格、公式及缓存值（无需 pandas），复查行列、合计、文字和公式；复杂公式引擎未覆盖的部分保留公式并注明尚未计算。
