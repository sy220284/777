package com.labteto.dshmobile.local

import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspacePatchPolicyTest {
    @Test
    fun acceptsOrdinaryWorkspacePatchAndDeletion() {
        validateWorkspacePatchPaths(
            """
            diff --git a/src/old.txt b/src/old.txt
            deleted file mode 100644
            --- a/src/old.txt
            +++ /dev/null
            @@ -1 +0,0 @@
            -old
            """.trimIndent(),
        )
    }

    @Test
    fun rejectsTraversalAbsoluteAndAmbiguousPaths() {
        for (patch in listOf(
            """
            diff --git a/src/a.txt b/../outside.txt
            --- a/src/a.txt
            +++ b/../outside.txt
            """.trimIndent(),
            """
            --- /etc/hosts
            +++ /etc/hosts
            """.trimIndent(),
            """
            rename from src/a.txt
            rename to ../outside.txt
            """.trimIndent(),
            """
            --- "a/path with spaces.txt"
            +++ "b/path with spaces.txt"
            """.trimIndent(),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                validateWorkspacePatchPaths(patch)
            }
        }
    }
}
