package com.labteto.dshmobile.local.quality

/**
 * Unified delivery-quality control plane.
 *
 * Guards stay owned by their domains; the pipeline only defines deterministic ordering and the
 * single result contract so Chat, Work and future Agent surfaces do not grow parallel dispatchers.
 */
internal class LocalOutputQualityPipeline(
    private val guards: List<LocalOutputQualityGuard>,
) {
    fun inspect(
        text: String,
        context: LocalOutputQualityContext,
    ): LocalOutputQualityResult {
        var current = text
        var changed = false
        val findings = linkedSetOf<String>()
        guards.forEach { guard ->
            val result = guard.inspect(current, context)
            findings += result.findings
            changed = changed || result.changed || result.text != current
            current = result.text
        }
        return LocalOutputQualityResult(
            text = current,
            findings = findings.toList(),
            changed = changed,
        )
    }
}
