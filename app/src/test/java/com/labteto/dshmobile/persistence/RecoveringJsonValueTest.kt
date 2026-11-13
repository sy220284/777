package com.labteto.dshmobile.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveringJsonValueTest {
    @Test
    fun missingPayloadIsLegitimateEmptyState() {
        val recovered = recoverJsonValue(
            primary = null,
            backup = null,
            label = "test",
            emptyValue = { emptyList<String>() },
            decode = { raw -> raw.split(",") },
        )

        assertEquals(RecoveringJsonSource.MISSING, recovered.source)
        assertEquals(emptyList<String>(), recovered.value)
        assertNull(recovered.raw)
    }

    @Test
    fun validPrimaryWinsOverBackup() {
        val recovered = recoverJsonValue(
            primary = "primary",
            backup = "backup",
            label = "test",
            emptyValue = { "" },
            decode = { raw -> raw },
        )

        assertEquals(RecoveringJsonSource.PRIMARY, recovered.source)
        assertEquals("primary", recovered.value)
        assertEquals("primary", recovered.raw)
    }

    @Test
    fun corruptPrimaryRecoversLastKnownGoodBackup() {
        val recovered = recoverJsonValue(
            primary = "{broken",
            backup = "backup",
            label = "test",
            emptyValue = { "" },
            decode = { raw ->
                if (raw.startsWith("{")) error("corrupt")
                raw
            },
        )

        assertEquals(RecoveringJsonSource.BACKUP, recovered.source)
        assertEquals("backup", recovered.value)
        assertEquals("backup", recovered.raw)
    }

    @Test(expected = CorruptPersistedJsonException::class)
    fun corruptPrimaryAndBackupFailInsteadOfBecomingEmpty() {
        recoverJsonValue(
            primary = "{broken-primary",
            backup = "{broken-backup",
            label = "test",
            emptyValue = { emptyMap<String, String>() },
            decode = { error("corrupt") },
        )
    }
}
