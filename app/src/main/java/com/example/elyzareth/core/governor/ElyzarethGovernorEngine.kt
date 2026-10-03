package com.example.elyzareth.core.governor

import com.example.elyzareth.core.dataflow.HumanDecisionContract
import com.example.elyzareth.core.ledger.EvidenceLedger
import com.example.elyzareth.core.model.EvidenceEventType
import com.example.elyzareth.core.model.EvidenceRecord
import com.example.elyzareth.core.model.GateLevel
import com.example.elyzareth.core.model.GateStatus
import com.example.elyzareth.core.model.GateVerificationRecord
import com.example.elyzareth.core.model.HumanSignOff
import com.example.elyzareth.core.model.PipelineStage
import com.example.elyzareth.core.model.ProjectAggregate
import com.example.elyzareth.core.model.ReleasePackage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.UUID

/**
 * Operational Governor Engine for Elyzareth OS Baseline 02.
 * Enforces stage progression rules, checks human decision prerequisites,
 * coordinates with the Evidence Ledger, and keeps Gate 5 and Master Player strictly locked.
 */
class ElyzarethGovernorEngine(
    val projectId: String,
    val projectTitle: String = "Elyzareth Track",
    private val humanDecisionAuthority: HumanDecisionContract,
    private val evidenceLedger: EvidenceLedger,
    initialStage: PipelineStage = PipelineStage.IDEA
) : GovernorContract {

    private val _currentProjectState = MutableStateFlow<ProjectAggregate?>(
        ProjectAggregate(
            id = projectId,
            title = projectTitle,
            conceptIdea = "Creative seed initialized.",
            currentStage = initialStage,
            creativeDnaId = null,
            activeLyricDraftId = null,
            curatedLyricId = null,
            musicStyleSpecId = null,
            visualGuideSpecId = null,
            productionAssetUri = null,
            forensicReportId = null,
            gateRecords = GateLevel.entries.associateWith { gate ->
                GateVerificationRecord(
                    gate = gate,
                    status = GateStatus.LOCKED,
                    criteriaResults = emptyList()
                )
            },
            releasePackageId = null,
            createdAtTimestamp = System.currentTimeMillis(),
            lastModifiedTimestamp = System.currentTimeMillis()
        )
    )
    override val currentProjectState: StateFlow<ProjectAggregate?> = _currentProjectState.asStateFlow()

    private val _pipelineState = MutableStateFlow(
        PipelineState(
            currentStage = initialStage,
            completedStages = emptySet(),
            activeGate = null,
            gateStatuses = GateLevel.entries.associateWith { GateStatus.LOCKED },
            isReleaseLocked = false,
            humanDecisionRequired = initialStage.requiresHumanDecision,
            statusMessage = "Governor initialized at stage: ${initialStage.displayName}"
        )
    )
    override val pipelineState: StateFlow<PipelineState> = _pipelineState.asStateFlow()

    override suspend fun transitionToStage(
        targetStage: PipelineStage,
        rationale: String
    ): Result<PipelineStage> {
        val currentStage = _pipelineState.value.currentStage

        // No-op if target stage is already current stage
        if (targetStage == currentStage) {
            return Result.success(currentStage)
        }

        // Boundary 9 & 11: Gate 5 and Master Player are strictly locked at Baseline 02
        if (targetStage == PipelineStage.GATE_5_RELEASE_LOCK || targetStage == PipelineStage.MASTER_PLAYER) {
            return Result.failure(
                IllegalStateException("Stage ${targetStage.displayName} is non-operational and locked at Baseline 02.")
            )
        }

        // Sequential Progression Invariant: Cannot arbitrarily skip stages
        if (targetStage.stageIndex > currentStage.stageIndex + 1) {
            return Result.failure(
                IllegalStateException(
                    "Invalid stage transition: Cannot jump from ${currentStage.displayName} to ${targetStage.displayName}. Sequential progression required."
                )
            )
        }

        // Backward transition check
        if (targetStage.stageIndex < currentStage.stageIndex) {
            return Result.failure(
                IllegalStateException(
                    "Invalid stage transition: Backward transition from ${currentStage.displayName} to ${targetStage.displayName} is prohibited."
                )
            )
        }

        // Invariant: Human Veto strictly prevents transition
        if (humanDecisionAuthority.hasActiveVeto(currentStage)) {
            return Result.failure(
                IllegalStateException("Stage transition blocked: Active human veto in effect on current stage ${currentStage.displayName}.")
            )
        }
        if (humanDecisionAuthority.hasActiveVeto(targetStage)) {
            return Result.failure(
                IllegalStateException("Stage transition blocked: Active human veto in effect on target stage ${targetStage.displayName}.")
            )
        }

        // Invariant: Human-required transition cannot bypass human authorization
        if (currentStage.requiresHumanDecision && !humanDecisionAuthority.isStageApproved(currentStage)) {
            return Result.failure(
                IllegalStateException(
                    "Human authorization required: Stage ${currentStage.displayName} cannot advance without explicit human approval."
                )
            )
        }

        // Execute transition
        val now = System.currentTimeMillis()
        val newCompleted = _pipelineState.value.completedStages + currentStage

        _pipelineState.value = _pipelineState.value.copy(
            currentStage = targetStage,
            completedStages = newCompleted,
            humanDecisionRequired = targetStage.requiresHumanDecision,
            statusMessage = "Transitioned from ${currentStage.displayName} to ${targetStage.displayName}. Rationale: $rationale"
        )

        _currentProjectState.value = _currentProjectState.value?.copy(
            currentStage = targetStage,
            lastModifiedTimestamp = now
        )

        // Produce and record immutable evidence record
        val evidence = EvidenceRecord(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            stage = targetStage,
            timestampMillis = now,
            authorOrActor = "GovernorEngine",
            eventType = EvidenceEventType.STAGE_TRANSITIONED,
            payloadHash = computeSha256("STAGE_TRANSITION:${currentStage.name}:${targetStage.name}:$rationale"),
            summary = "Governor transitioned from ${currentStage.displayName} to ${targetStage.displayName}. Rationale: $rationale",
            metadata = mapOf(
                "fromStage" to currentStage.name,
                "toStage" to targetStage.name,
                "rationale" to rationale
            )
        )
        evidenceLedger.record(evidence)

        return Result.success(targetStage)
    }

    override suspend fun evaluateGate(gate: GateLevel): Result<GateVerificationRecord> {
        // Boundary 9: Gate 5 is non-operational at Baseline 02
        if (gate == GateLevel.G5) {
            return Result.failure(
                IllegalStateException("Gate 5 evaluation is disabled. Release criteria are intentionally unresolved at Baseline 02.")
            )
        }

        val correspondingStage = when (gate) {
            GateLevel.G1 -> PipelineStage.GATE_1_PRE_PRODUCTION
            GateLevel.G2 -> PipelineStage.GATE_2_CURATION_APPROVAL
            GateLevel.G3 -> PipelineStage.GATE_3_DNA_CONFORMANCE
            GateLevel.G4 -> PipelineStage.GATE_4_FORENSIC_CLEARANCE
            GateLevel.G5 -> PipelineStage.GATE_5_RELEASE_LOCK
        }

        if (humanDecisionAuthority.hasActiveVeto(correspondingStage)) {
            return Result.failure(
                IllegalStateException("Gate ${gate.code} evaluation is blocked by an active human veto.")
            )
        }

        val now = System.currentTimeMillis()
        val updatedStatuses = _pipelineState.value.gateStatuses.toMutableMap()
        updatedStatuses[gate] = GateStatus.HUMAN_REVIEW_REQUIRED

        _pipelineState.value = _pipelineState.value.copy(
            activeGate = gate,
            gateStatuses = updatedStatuses,
            statusMessage = "Gate ${gate.code} evaluated: Pending human sign-off."
        )

        val record = GateVerificationRecord(
            gate = gate,
            status = GateStatus.HUMAN_REVIEW_REQUIRED,
            criteriaResults = emptyList(),
            evaluatedTimestamp = now
        )

        // Log gate evaluation evidence
        val evidence = EvidenceRecord(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            stage = correspondingStage,
            timestampMillis = now,
            authorOrActor = "GovernorEngine",
            eventType = EvidenceEventType.GATE_EVALUATED,
            payloadHash = computeSha256("GATE_EVAL:${gate.code}:$now"),
            summary = "Gate ${gate.code} evaluation triggered: Status is HUMAN_REVIEW_REQUIRED.",
            metadata = mapOf("gate" to gate.code, "status" to GateStatus.HUMAN_REVIEW_REQUIRED.name)
        )
        evidenceLedger.record(evidence)

        return Result.success(record)
    }

    override suspend fun submitHumanGateSignOff(
        gate: GateLevel,
        signOff: HumanSignOff
    ): Result<GateVerificationRecord> {
        // Boundary 9: Gate 5 sign-off is disabled
        if (gate == GateLevel.G5) {
            return Result.failure(
                IllegalStateException("Gate 5 sign-off is disabled. Release criteria remain intentionally unresolved at Baseline 02.")
            )
        }

        val authResult = humanDecisionAuthority.authorizeGatePassage(
            gate = gate,
            officerId = signOff.signatoryId,
            notes = signOff.remarks
        )
        if (authResult.isFailure) {
            return Result.failure(authResult.exceptionOrNull()!!)
        }

        val now = System.currentTimeMillis()
        val updatedStatuses = _pipelineState.value.gateStatuses.toMutableMap()
        updatedStatuses[gate] = GateStatus.APPROVED

        _pipelineState.value = _pipelineState.value.copy(
            gateStatuses = updatedStatuses,
            statusMessage = "Gate ${gate.code} approved by ${signOff.signatoryId}."
        )

        val updatedRecord = GateVerificationRecord(
            gate = gate,
            status = GateStatus.APPROVED,
            criteriaResults = emptyList(),
            humanDecision = signOff,
            evaluatedTimestamp = now
        )

        return Result.success(updatedRecord)
    }

    override suspend fun executeGate5ReleaseLock(authoritySignature: String): Result<ReleasePackage> {
        // Boundary 9 & 10: DO NOT make G5 operational. DO NOT implement cryptographic G5 sealing.
        return Result.failure(
            IllegalStateException("Gate 5 Release Lock is locked and non-operational at Baseline 02.")
        )
    }

    override fun isMasterPlayerUnlocked(): Boolean {
        // Boundary 11: DO NOT implement Master Player functionality. G5 lock is never sealed.
        return false
    }

    private fun computeSha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
