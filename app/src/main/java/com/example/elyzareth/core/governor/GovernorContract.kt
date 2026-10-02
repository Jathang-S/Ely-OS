package com.example.elyzareth.core.governor

import com.example.elyzareth.core.model.GateLevel
import com.example.elyzareth.core.model.GateVerificationRecord
import com.example.elyzareth.core.model.HumanSignOff
import com.example.elyzareth.core.model.PipelineStage
import com.example.elyzareth.core.model.ProjectAggregate
import com.example.elyzareth.core.model.ReleasePackage
import kotlinx.coroutines.flow.StateFlow

/**
 * Shared contract for the Elyzareth OS Governor.
 * The Governor acts as the overarching OS orchestrator, enforcing state transitions,
 * verifying prerequisite stages, maintaining the audit trail, and guarding gate integrity.
 *
 * Core Rule: Human remains the final creative decision maker at every transition.
 */
interface GovernorContract {

    /**
     * Active state stream of the current project aggregate.
     */
    val currentProjectState: StateFlow<ProjectAggregate?>

    /**
     * Active pipeline state stream containing stage progression and active gate status.
     */
    val pipelineState: StateFlow<PipelineState>

    /**
     * Request transition to a new stage.
     * The Governor validates all prerequisites and gate sign-offs before advancing.
     */
    suspend fun transitionToStage(targetStage: PipelineStage, rationale: String): Result<PipelineStage>

    /**
     * Triggers evaluation criteria for a specific gate (G1-G5).
     */
    suspend fun evaluateGate(gate: GateLevel): Result<GateVerificationRecord>

    /**
     * Human Authority Sign-Off for a gate.
     * Gates can NEVER be marked as APPROVED without explicit human sign-off.
     */
    suspend fun submitHumanGateSignOff(gate: GateLevel, signOff: HumanSignOff): Result<GateVerificationRecord>

    /**
     * Seals Gate 5 and generates the cryptographic ReleasePackage.
     * Precondition: G1, G2, G3, G4 must be APPROVED.
     */
    suspend fun executeGate5ReleaseLock(authoritySignature: String): Result<ReleasePackage>

    /**
     * Verifies whether Master Player playback is permitted for the active project.
     * Only true when Gate 5 is sealed and ReleasePackage is present.
     */
    fun isMasterPlayerUnlocked(): Boolean
}
