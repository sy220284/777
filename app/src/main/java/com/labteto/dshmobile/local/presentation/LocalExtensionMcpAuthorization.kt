package com.labteto.dshmobile.local.presentation

import kotlinx.coroutines.CancellationException

/** One tool-feature rule for multi-service installation, independent of Compose.
 * Network/configuration failures preserve successful dependencies and do not block
 * unrelated services. A user cancellation stops the current batch immediately.
 */
internal data class LocalExtensionMcpAuthorization(
    val serverId: String,
    val endpoint: String,
)

internal data class LocalExtensionMcpAuthorizationFailure(
    val serverId: String,
    val message: String,
)

internal suspend fun authorizeLocalExtensionMcpBatch(
    requests: List<LocalExtensionMcpAuthorization>,
    authorize: suspend (LocalExtensionMcpAuthorization) -> Unit,
    onStart: (LocalExtensionMcpAuthorization, Int, Int) -> Unit = { _, _, _ -> },
    onFinish: (LocalExtensionMcpAuthorization) -> Unit = {},
): List<LocalExtensionMcpAuthorizationFailure> {
    // An official plugin endpoint may also be declared in its skill YAML; one
    // installation attempt must never open the same authorization twice.
    val unique = requests.distinctBy(LocalExtensionMcpAuthorization::serverId)
    val failures = mutableListOf<LocalExtensionMcpAuthorizationFailure>()
    unique.forEachIndexed { index, request ->
        onStart(request, index + 1, unique.size)
        try {
            authorize(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failures += LocalExtensionMcpAuthorizationFailure(
                request.serverId,
                failure.message ?: failure::class.java.simpleName,
            )
        } finally {
            onFinish(request)
        }
    }
    return failures
}
