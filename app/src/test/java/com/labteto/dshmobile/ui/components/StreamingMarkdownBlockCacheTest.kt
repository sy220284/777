package com.labteto.dshmobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingMarkdownBlockCacheTest {
    @Test fun everyPrefixMatchesTheFullParserWithoutMarkdownReflow() {
        val examples = listOf(
            "普通回复\n\n第二段文本\n\n结尾",
            "# 标题\n\n段落 **加粗** [链接](https://example.org)\n\n结束",
            "- 第一项\n- 第二项\n\n1. 后续有序\n2. 再一项",
            "> 引用一\n> 引用二\n\n之后的回复",
            "```kotlin\nfun greet() {\n    println(\"hi\")\n\n}\n```\n\n完整代码块后有正文",
            "| 名称 | 数量 |\n| --- | ---: |\n| A | 1 |\n| B | 2 |\n\n说明",
            "普通说明\n名称 | 数量\n--- | ---:\n苹果 | 2\n梨 | 3\n\n说明",
            "标题\r\n\r\n第二段\r\n\r\n结尾",
            "中文😀\n\nemoji 👩‍💻 和代码 `a|b`\n\n结束",
            "a\n\n```\n\n尚未闭合的围栏\n\n更多代码",
        )
        examples.forEachIndexed { index, full ->
            val cache = StreamingMarkdownBlockCache()
            for (length in 0..full.length) {
                val prefix = full.substring(0, length)
                assertEquals("example=$index prefix=$length", parseMarkdown(prefix), cache.parse(prefix))
            }
        }
    }

    @Test fun truncatedOrReplacedStreamNeverReusesOldBlocks() {
        val cache = StreamingMarkdownBlockCache()
        val parts = listOf(
            "第一段\n\n第二段\n\n第三段",
            "第一段\n\n第二段",
            "完全替换\n\n- 列表\n",
            "",
            "新的对话\n\n```sh\necho ok\n```\n\n结束",
        )
        parts.forEachIndexed { index, text ->
            assertEquals("replacement=$index", parseMarkdown(text), cache.parse(text))
        }
    }

    @Test fun randomizedMixedBlocksKeepExactParserResults() {
        val random = kotlin.random.Random(1201)
        val tokens = listOf(
            "中文", "ascii", "\n", "\n\n", "  ", "\n- 项目", "\n> 引用",
            "\n```", "\n| 列 | 值 |", "\n| --- | --- |", "😀", "**强调**",
        )
        repeat(35) { trial ->
            val cache = StreamingMarkdownBlockCache()
            val text = buildString {
                repeat(35) { append(tokens[random.nextInt(tokens.size)]) }
            }
            var end = 0
            while (end < text.length) {
                end = (end + random.nextInt(1, 6)).coerceAtMost(text.length)
                val prefix = text.substring(0, end)
                assertEquals("trial=$trial end=$end", parseMarkdown(prefix), cache.parse(prefix))
            }
        }
    }
}
