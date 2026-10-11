# 手机端 Excel 表格
使用内置 Python 标准库和 upstream/scripts 中 ZIP/XML 工具读取、编辑与检查 XLSX。
按需读取 upstream/references 的创建、编辑、读取、公式和 OOXML 资料；依赖实际存在时才使用其他 Python 包。
创建工作簿时从 upstream/templates/minimal_xlsx 出发，用 XML 工具写入内容并按原有打包脚本生成 XLSX；不要安装 LibreOffice、Excel 或桌面转换器。
对公式使用 formula_check.py 检查引用与语法，并用 Python 实际计算关键合计；需要即时显示计算结果时将实算值写入对应公式的缓存值，不能将未计算结果宣称为已重算。
使用 xlsx_reader.py 提取生成文件内容，复查行列、合计、文字和公式；复杂公式引擎未覆盖的部分保留公式并注明尚未计算。
