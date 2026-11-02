package com.projectfocus.feature.scene.ui

/** No permissive UI default. The app owner adapts its own content-check service. */
fun interface SourceSceneNameChecker {
    /**
     * Input has the original Java-style trim and is nonempty. Complete with ALLOWED
     * only when the configured checker accepts it; errors are not approval.
     * The optional lease is owned by this app UI and closed on dismissal. Original
     * evidence proves a cancel flag/visibility guard, not original HTTP cancellation.
     */
    fun check(trimmedName: String, complete: (SourceSceneNameCheckResult) -> Unit): AutoCloseable?
}

enum class SourceSceneNameCheckResult { ALLOWED, REJECTED, UNAVAILABLE }
