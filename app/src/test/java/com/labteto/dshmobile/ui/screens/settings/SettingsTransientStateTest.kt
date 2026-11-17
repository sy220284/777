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
