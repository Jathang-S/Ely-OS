package com.example.elyzareth.core.dataflow

import com.example.elyzareth.core.model.GateLevel
import com.example.elyzareth.core.model.PipelineStage

/**
 * Fundamental Governance Contract:
 * Human remains the final creative decision maker.
 *
 * Automated systems and AI models in Elyzareth OS act purely as advisor/generation instruments.
 * No asset can cross into curation, production, or release without human intervention and explicit approval.
 */
interface HumanDecisionContract {

    /**
     * Checks if the given stage transition requires explicit human approval.
     */
    fun requiresHumanApproval(stage: PipelineStage): Boolean

    /**
     * Records a human override or editorial veto at any stage.
     */
    suspend fun registerHumanVeto(
        stage: PipelineStage,
        userId: String,
        reason: String
    ): Result<Unit>

    /**
     * Records an explicit human approval for a gate.
     */
    suspend fun authorizeGatePassage(
        gate: GateLevel,
        officerId: String,
        notes: String
    ): Result<Unit>

    /**
     * Records an explicit human approval for a pipeline stage transition.
     */
    suspend fun approveStageTransition(
        stage: PipelineStage,
        userId: String,
        notes: String
    ): Result<Unit>

    /**
     * Records an explicit human rejection for a pipeline stage transition.
     */
    suspend fun rejectStageTransition(
        stage: PipelineStage,
        userId: String,
        reason: String
    ): Result<Unit>

    /**
     * Checks if an active human veto exists for the given stage.
     */
    fun hasActiveVeto(stage: PipelineStage): Boolean

    /**
     * Checks if human approval has been recorded for the given stage.
     */
    fun isStageApproved(stage: PipelineStage): Boolean
}
