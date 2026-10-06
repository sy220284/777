package com.labteto.dshmobile.local.runtime

import com.labteto.dshmobile.local.persistence.LOCAL_HARNESS_PREFERENCES_NAME
import android.content.Context
import com.labteto.dshmobile.observability.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Thin process-level Runtime Kernel.
 *
 * It owns process start-once semantics, lifecycle scope and failure projection. Concrete Feature,
 * provider and startup construction stays behind [LocalRuntimeBootstrapPort] in the app composition
 * root, keeping Shared Runtime independent from product semantics.
 */
@Singleton
class LocalRuntimeKernel @Inject internal constructor(
    @ApplicationContext context: Context,
    private val runtimeStateStore: LocalRuntimeStateStore,
    private val bootstrap: LocalRuntimeBootstrapPort,
) {
    private val started = AtomicBoolean(false)
    private val preferences = context.getSharedPreferences(LOCAL_HARNESS_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, throwable ->
                AppLog.error("LocalRuntimeKernel", "runtime coroutine failure", throwable)
                runtimeStateStore.projection.publishError(
                    throwable.message?.takeIf(String::isNotBlank)
                        ?: "本机 Runtime 后台任务失败：${throwable::class.java.simpleName}",
                )
            },
    )

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val initialSessionId = preferences.getString(KEY_SESSION_ID, null)
            ?.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString()
        preferences.edit().putString(KEY_SESSION_ID, initialSessionId).apply()
        bootstrap.initialize(scope, initialSessionId)
        scope.launch {
            runCatching {
                bootstrap.prepareAndRestore(scope, initialSessionId)
            }.onFailure { error ->
                AppLog.error("LocalRuntimeKernel", "runtime bootstrap failed", error)
                runtimeStateStore.projection.update {
                    it.copy(
                        loading = false,
                        error = "本机 Runtime 初始化失败：${error.message ?: error::class.java.simpleName}",
                    )
                }
            }
        }
    }
}
