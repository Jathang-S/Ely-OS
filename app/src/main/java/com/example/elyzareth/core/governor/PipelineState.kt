package com.example.elyzareth.core.governor

import com.example.elyzareth.core.model.GateLevel
import com.example.elyzareth.core.model.GateStatus
import com.example.elyzareth.core.model.PipelineStage

/**
 * Snapshot of the OS Governor pipeline state.
 */
data class PipelineState(
    val currentStage: PipelineStage,
    val completedStages: Set<PipelineStage>,
    val activeGate: GateLevel?,
    val gateStatuses: Map<GateLevel, GateStatus>,
    val isReleaseLocked: Boolean,
    val humanDecisionRequired: Boolean,
    val statusMessage: String
) {
    companion object {
        fun initial(): PipelineState = PipelineState(
            currentStage = PipelineStage.IDEA,
            completedStages = emptySet(),
            activeGate = null,
            gateStatuses = GateLevel.entries.associateWith { GateStatus.LOCKED },
            isReleaseLocked = false,
            humanDecisionRequired = true,
            statusMessage = "Elyzareth OS Initialized. Stage: Idea Inception."
        )
    }
}
