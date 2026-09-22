package com.labteto.dshmobile.local

import org.junit.Assert.*
import org.junit.Test

class LanguageServerCommandTest {
    @Test fun acceptsExplicitArgumentArrayAndEmptyDisablingValue() {
        assertEquals(listOf("node", "server path/server.js", "--stdio"),
            parseLanguageServerCommand("""["node","server path/server.js","--stdio"]"""))
        assertTrue(parseLanguageServerCommand("").isEmpty())
    }
    @Test fun refusesAmbiguousShellStringsAndNonStringArguments() {
        for (value in listOf("node server.js", "[]", "[123]", "[null]", "[\"\"]")) {
            assertTrue(runCatching { parseLanguageServerCommand(value) }.isFailure)
        }
    }
}
