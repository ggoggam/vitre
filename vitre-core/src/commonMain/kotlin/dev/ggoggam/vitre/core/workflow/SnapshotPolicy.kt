package dev.ggoggam.vitre.core.workflow

/**
 * Host-owned limits and redaction for page snapshots. Configure on [WorkflowEngine]; agent adapters
 * apply the same policy through their driver. Tool arguments cannot turn off these protections.
 *
 * Passwords, one-time codes and payment-card autocomplete fields are always redacted. [redactSelectors]
 * additionally redacts matching elements and their descendants, including text, names and links.
 * Redacting a selected option also masks the value copied onto its parent select control.
 * Invalid selectors fail the snapshot. Redaction applies to snapshots only: explicit extraction and
 * arbitrary JavaScript remain separate capabilities that the host must control.
 */
data class SnapshotPolicy(
    val valueLimit: Int = 200,
    val redactSelectors: List<String> = emptyList(),
) {
    init {
        require(valueLimit in 1..10_000) { "valueLimit must be between 1 and 10000" }
    }
}
