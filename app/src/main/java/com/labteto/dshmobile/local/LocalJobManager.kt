package com.labteto.dshmobile.local

import com.labteto.dshmobile.harness.jobs.HarnessJobManager
import kotlinx.coroutines.CoroutineScope

/** Android projection adapter for the process-agnostic core job controller. */
class LocalJobManager(
    scope: CoroutineScope,
    onChanged: (List<LocalJobInfo>) -> Unit,
) {
    private val delegate = HarnessJobManager(
        scope = scope,
        onChanged = { jobs ->
            onChanged(jobs.map { LocalJobInfo(it.id, it.label, it.status) })
        },
    )

    fun start(label: String, block: suspend (String, (String) -> Unit) -> String): String =
        delegate.start(label, block)

    fun list(): String = delegate.list()

    fun listAgents(): String = delegate.listAgents()

    fun output(id: String): String = delegate.output(id)

    fun kill(id: String): String = delegate.kill(id)

    fun send(id: String, message: String): String = delegate.send(id, message)

    fun drainMessages(id: String): List<String> = delegate.drainMessages(id)

    fun stopAll() = delegate.stopAll()
}
