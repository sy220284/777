package com.labteto.dshmobile.ui.screens.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsTransientStateTest {
    @Test fun storageOperationRemainsOwnedAcrossPresentationReplacementAndRejectsDuplicates() = runTest {
        val owner = SettingsStorageOperations(backgroundScope)
        val release = CompletableDeferred<Unit>()
        assertTrue(owner.launch { release.await(); owner.notice.value = "已保存" })
        assertTrue(owner.busy.value)
        assertFalse(owner.launch { fail("不得重复执行") })
        // A rebuilt page observes this same ViewModel owner, including its real live operation.
        val rebuiltPageOwner = owner
        assertTrue(rebuiltPageOwner.busy.value)
        release.complete(Unit)
        runCurrent()
        assertFalse(rebuiltPageOwner.busy.value)
        assertEquals("已保存", rebuiltPageOwner.notice.value)
    }
    @Test fun cancellingTheViewModelReleasesBusyAndANewOwnerNeverRestoresAnOrphanFlag() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val owner = SettingsStorageOperations(scope)
        owner.launch { kotlinx.coroutines.awaitCancellation() }
        assertTrue(owner.busy.value)
        scope.cancel()
        runCurrent()
        assertFalse(owner.busy.value)
        assertFalse(owner.launch { fail("已销毁的ViewModel不得接受新操作") })
        assertFalse(SettingsStorageOperations(backgroundScope).busy.value)
    }
    @Test fun failedProjectionReadPreservesCommittedOutcomeAndDoesNotReplayTheAction() = runTest {
        val owner = SettingsStorageOperations(backgroundScope)
        var writes = 0
        val failure = java.io.IOException("status unavailable")
        var reported: Exception? = null
        owner.launch {
            writes++
            owner.refreshAfterCommit("恢复计划已提交", { throw failure }) { reported = it }
        }
        runCurrent()
        assertEquals(1, writes)
        assertSame(failure, reported)
        assertNull(owner.status.value)
        assertTrue(owner.notice.value.orEmpty().contains("恢复计划已提交"))
        assertTrue(owner.notice.value.orEmpty().contains("无需重复操作"))
        assertFalse(owner.busy.value)
    }

    @Test fun cancellationDuringProjectionReadRetainsTheCommittedReceipt() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val owner = SettingsStorageOperations(scope)
        owner.launch {
            owner.refreshAfterCommit("备份已导出", { kotlinx.coroutines.awaitCancellation() }) {
                fail("取消不得当作刷新错误吞掉")
            }
        }
        assertEquals("备份已导出", owner.notice.value)
        assertTrue(owner.busy.value)
        scope.cancel()
        runCurrent()
        assertFalse(owner.busy.value)
        assertEquals("备份已导出", owner.notice.value)
    }

    @Test fun cancellingModelEditorClearsSecretAndInvalidatesOutstandingCallbacks() {
        val draft = ApiModelEditorState()
        draft.showEditor.value = true
        draft.apiKey.value = "unsaved-secret"
        val generation = draft.editorGeneration.value
        draft.close()
        assertEquals("", draft.apiKey.value)
        assertFalse(draft.showEditor.value)
        assertTrue(draft.editorGeneration.value > generation)
    }
}
