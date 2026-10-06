package com.labteto.dshmobile.local.work

import com.labteto.dshmobile.local.session.LocalHarnessSession
import com.labteto.dshmobile.local.session.LocalSessionDomainCodec
import com.labteto.dshmobile.local.session.decodeLocalSessionDomain
import com.labteto.dshmobile.local.session.encodeLocalSessionDomain
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Work-owned typed view of the opaque Work fields persisted inside the Shared Session envelope. */
internal data class LocalWorkSessionDomain(
    val todos: List<LocalTodoItem> = emptyList(),
    val goal: LocalGoal? = null,
)

internal fun LocalHarnessSession.decodeWorkSessionDomain(): LocalWorkSessionDomain =
    LocalWorkSessionDomain(
        todos = decodeLocalSessionDomain(todosPayload),
        goal = goalPayload?.let(::decodeLocalSessionDomain),
    )

internal val LocalHarnessSession.todos: List<LocalTodoItem>
    get() = decodeLocalSessionDomain(todosPayload)

internal val LocalHarnessSession.goal: LocalGoal?
    get() = goalPayload?.let(::decodeLocalSessionDomain)

internal fun LocalHarnessSession.withWorkSessionDomain(
    todos: List<LocalTodoItem> = this.todos,
    goal: LocalGoal? = this.goal,
): LocalHarnessSession = copy(
    todosPayload = encodeLocalSessionDomain(todos).jsonArray,
    goalPayload = goal?.let { encodeLocalSessionDomain(it).jsonObject },
)

internal fun LocalHarnessSession.withWorkSessionDomain(work: LocalWorkState): LocalHarnessSession =
    copy(
        plan = work.plan,
        planMode = work.planMode,
    ).withWorkSessionDomain(
        todos = work.todos,
        goal = work.goal,
    )

/** WorkFeature owns compatibility/validation of Work fields inside the Shared Session envelope. */
internal object LocalWorkSessionDomainCodec : LocalSessionDomainCodec {
    override val id: String = "work"

    override fun normalizeLoaded(session: LocalHarnessSession): LocalHarnessSession {
        val decoded = session.decodeWorkSessionDomain()
        return session.withWorkSessionDomain(
            todos = decoded.todos,
            goal = decoded.goal,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object LocalWorkSessionDomainCodecModule {
    @Provides
    @IntoSet
    fun provideLocalWorkSessionDomainCodec(): LocalSessionDomainCodec =
        LocalWorkSessionDomainCodec
}
