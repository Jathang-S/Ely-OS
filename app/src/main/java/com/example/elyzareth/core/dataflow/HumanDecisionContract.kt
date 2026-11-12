package com.example.elyzareth.core.dataflow

import com.example.elyzareth.core.model.ActorRole
import com.example.elyzareth.core.model.ActorType
import com.example.elyzareth.core.model.GateLevel
import com.example.elyzareth.core.model.PipelineStage
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * Declared actor identity record for Phase 0 role and type validation.
 *
 * Phase 0 Note:
 * - Provides declared actor identity and role validation only.
 * - Does NOT represent cryptographic authentication or hardware-backed user presence,
 *   which belong to Phase 1 (KERNEL_1.0 / AUTH-004).
 */
data class ActorIdentity(
    val actorId: String,
    val actorType: ActorType,
    val role: ActorRole
)

/**
 * Fail-closed authorization policy mapping stages, gates, and veto/review actions
 * to permitted human roles.
 *
 * Important Policy Note:
 * - An unconfigured policy (`HumanAuthorizationPolicy()`) is intentionally deny-all.
 * - Any stage, gate, or veto/review role mapping supplied via `proposedBaselinePolicy()`
 *   remains `PROPOSED / PENDING HUMAN APPROVAL` and is not frozen as constitutional law.
 */
class HumanAuthorizationPolicy(
    stageApprovalRoles: Map<PipelineStage, Set<ActorRole>> =
        emptyMap(),

    gateAuthorizationRoles: Map<GateLevel, Set<ActorRole>> =
        emptyMap(),

    vetoAndReviewRoles: Set<ActorRole> =
        emptySet()
) {

    private val frozenStageRoles: Map<PipelineStage, Set<ActorRole>> =
        Collections.unmodifiableMap(
            LinkedHashMap(
                stageApprovalRoles.mapValues { (_, roles) ->
                    Collections.unmodifiableSet(
                        LinkedHashSet(roles)
                    )
                }
            )
        )

    private val frozenGateRoles: Map<GateLevel, Set<ActorRole>> =
        Collections.unmodifiableMap(
            LinkedHashMap(
                gateAuthorizationRoles.mapValues { (_, roles) ->
                    Collections.unmodifiableSet(
                        LinkedHashSet(roles)
                    )
                }
            )
        )

    private val frozenVetoRoles: Set<ActorRole> =
        Collections.unmodifiableSet(
            LinkedHashSet(vetoAndReviewRoles)
        )

    fun allowedRolesForStage(
        stage: PipelineStage
    ): Set<ActorRole> =
        frozenStageRoles[stage] ?: emptySet()

    fun allowedRolesForGate(
        gate: GateLevel
    ): Set<ActorRole> =
        frozenGateRoles[gate] ?: emptySet()

    fun allowedRolesForVetoAndReview(): Set<ActorRole> =
        frozenVetoRoles

    companion object {
        val ALL_HUMAN_ROLES: Set<ActorRole> =
            Collections.unmodifiableSet(
                LinkedHashSet(
                    listOf(
                        ActorRole.HUMAN_CREATOR,
                        ActorRole.HUMAN_CURATOR,
                        ActorRole.HUMAN_AUDITOR
                    )
                )
            )

        /**
         * PROPOSED / PENDING HUMAN APPROVAL:
         * Default stage role mapping used by test fixtures that explicitly opt in.
         * Not active unless explicitly injected.
         */
        val PROPOSED_STAGE_ROLES: Map<PipelineStage, Set<ActorRole>> =
            Collections.unmodifiableMap(
                LinkedHashMap(
                    PipelineStage.entries.associateWith { stage ->
                        when (stage) {
                            PipelineStage.CURATION -> setOf(
                                ActorRole.HUMAN_CREATOR,
                                ActorRole.HUMAN_CURATOR
                            )
                            else -> ALL_HUMAN_ROLES
                        }
                    }
                )
            )

        /**
         * PROPOSED / PENDING HUMAN APPROVAL:
         * Default gate role mapping used by test fixtures that explicitly opt in.
         * Not active unless explicitly injected.
         */
        val PROPOSED_GATE_ROLES: Map<GateLevel, Set<ActorRole>> =
            Collections.unmodifiableMap(
                LinkedHashMap(
                    mapOf(
                        GateLevel.G1 to setOf(ActorRole.HUMAN_CREATOR, ActorRole.HUMAN_CURATOR),
                        GateLevel.G2 to setOf(ActorRole.HUMAN_CREATOR, ActorRole.HUMAN_AUDITOR),
                        GateLevel.G3 to setOf(ActorRole.HUMAN_CREATOR, ActorRole.HUMAN_CURATOR),
                        GateLevel.G4 to setOf(ActorRole.HUMAN_CREATOR, ActorRole.HUMAN_AUDITOR)
                    )
                )
            )

        /**
         * PROPOSED / PENDING HUMAN APPROVAL:
         * Constructs the proposed baseline policy for test fixtures.
         */
        fun proposedBaselinePolicy(): HumanAuthorizationPolicy =
            HumanAuthorizationPolicy(
                stageApprovalRoles = PROPOSED_STAGE_ROLES,
                gateAuthorizationRoles = PROPOSED_GATE_ROLES,
                vetoAndReviewRoles = ALL_HUMAN_ROLES
            )
    }
}

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
     * Opens a new human review cycle for a previously vetoed or rejected stage,
     * clearing the active veto/rejection/approval for [stage] only through an explicit,
     * evidence-logged human action and invoking [commitGovernorState] inside a protected
     * NonCancellable commit section.
     */
    suspend fun openNewReviewCycle(
        stage: PipelineStage,
        userId: String,
        resolutionNotes: String,
        commitGovernorState: () -> Unit
    ): Result<Unit>

    /**
     * Coordinated gate authorization that validates the registered actor identity and role,
     * appends the authority evidence record, records the corresponding stage approval,
     * and invokes [commitGovernorState] inside a protected NonCancellable commit section.
     */
    suspend fun authorizeGatePassage(
        gate: GateLevel,
        actorId: String,
        expectedRole: String,
        remarks: String,
        commitGovernorState: () -> Unit
    ): Result<Unit>

    /**
     * Runs a governor transition while holding the same authority mutex used by veto,
     * rejection and approval operations. This makes the veto check + evidence append +
     * snapshot publication one serialized decision with respect to human decisions.
     */
    suspend fun <T> withDecisionLock(block: suspend () -> T): T

    /**
     * Records an explicit human rejection for a gate.
     */
    /**
     * Coordinated gate rejection. Implementations must append rejection evidence and
     * publish the governor's REJECTED snapshot within one protected commit section.
     */
    suspend fun rejectGatePassage(
        gate: GateLevel,
        officerId: String,
        reason: String,
        commitGovernorState: () -> Unit
    ): Result<Unit>

    /** True while a gate rejection remains unresolved by an explicit review cycle. */
    fun hasActiveGateRejection(gate: GateLevel): Boolean

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
