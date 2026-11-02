package com.projectfocus.feature.scene.source

/** Source x4.h candidates: omitted enabled builtins plus all custom history rows. */
data class SourceSceneEnableChoices(
    val builtins: List<SourceSceneOption>,
    val customHistory: List<SourceSceneEnableCandidate>,
)

/** History stays visible when enabled; optionId remains separate from iconKey/name. */
data class SourceSceneEnableCandidate(
    val option: SourceSceneOption,
    val alreadyEnabled: Boolean,
)
