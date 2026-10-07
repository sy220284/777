package com.labteto.dshmobile.local.model

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelConfigurationTransactionTest {
    private class Fixture {
        var journal: String? = null
        var token: String? = "old"
        var secret = "old-key"
        var active = "old"
        var restoreFails = false
        var cleanupFails = false
        var cleanupErrors = 0
        fun owner() = LocalModelConfigurationTransaction(
            Json, { journal }, { journal = it },
            { if (cleanupFails) throw IOException("cleanup"); journal = null },
            { token },
            { if (restoreFails) throw IOException("rollback"); secret = "old-key"; token = "old" },
            { cleanupErrors++ },
        )
        val record: JsonObject = buildJsonObject { put("version", 1); put("transaction", "new") }
    }

    @Test
    fun failedCommitRestoresCredentialAndDoesNotPublishActivation() = runBlocking {
        val fixture = Fixture()
        val result = runCatching {
            fixture.owner().commit(fixture.record, persist = {
                fixture.secret = "new-key"
                fixture.token = "new" // SharedPreferences memory may change despite commit(false).
                throw IOException("disk full")
            }, activate = { fixture.active = "new" })
        }
        assertTrue(result.isFailure)
        assertEquals("old-key", fixture.secret)
        assertEquals("old", fixture.token)
        assertEquals("old", fixture.active)
        assertNull(fixture.journal)
    }

    @Test
    fun processDeathBeforeDecisionRollsBackOnNextOwner() = runBlocking {
        val fixture = Fixture()
        runCatching {
            fixture.owner().commit(fixture.record, persist = {
                fixture.secret = "new-key"
                throw AssertionError("process death before durable metadata")
            }, activate = { fixture.active = "new" })
        }
        assertNotNull(fixture.journal)
        fixture.owner().recover()
        assertEquals("old-key", fixture.secret)
        assertEquals("old", fixture.token)
        assertNull(fixture.journal)
    }

    @Test
    fun processDeathAfterDecisionKeepsCommittedCredentialOnNextOwner() = runBlocking {
        val fixture = Fixture()
        runCatching {
            fixture.owner().commit(fixture.record, persist = {
                fixture.secret = "new-key"; fixture.token = "new"
            }, activate = { throw AssertionError("process death after durable metadata") })
        }
        assertNotNull(fixture.journal)
        fixture.owner().recover()
        assertEquals("new-key", fixture.secret)
        assertEquals("new", fixture.token)
        assertNull(fixture.journal)
    }

    @Test
    fun failedRollbackKeepsJournalAndUnconfirmedMemoryTokenCannotBecomeSuccess() = runBlocking {
        val fixture = Fixture()
        val owner = fixture.owner()
        fixture.restoreFails = true
        val failure = runCatching {
            owner.commit(fixture.record, persist = {
                fixture.secret = "new-key"; fixture.token = "new"
                throw IOException("unconfirmed commit")
            }, activate = { fixture.active = "new" })
        }.exceptionOrNull()
        assertEquals(1, failure!!.suppressed.size)
        assertNotNull(fixture.journal)
        fixture.restoreFails = false
        owner.recover()
        assertEquals("old-key", fixture.secret)
        assertEquals("old", fixture.token)
        assertEquals("old", fixture.active)
        assertNull(fixture.journal)
    }

    @Test
    fun cleanupFailurePreservesDurableSuccessAndRecoveryDoesNotRevertIt() = runBlocking {
        val fixture = Fixture()
        fixture.cleanupFails = true
        fixture.owner().commit(fixture.record, persist = {
            fixture.secret = "new-key"; fixture.token = "new"
        }, activate = { fixture.active = "new" })
        assertEquals("new", fixture.active)
        assertEquals(1, fixture.cleanupErrors)
        assertNotNull(fixture.journal)
        fixture.cleanupFails = false
        fixture.owner().recover()
        assertEquals("new-key", fixture.secret)
        assertEquals("new", fixture.token)
        assertNull(fixture.journal)
    }
}
