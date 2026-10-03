package com.example.elyzareth.core.dataflow

import com.example.elyzareth.core.ledger.EvidenceLedger
import com.example.elyzareth.core.model.EvidenceEventType
import com.example.elyzareth.core.model.EvidenceRecord
import com.example.elyzareth.core.model.GateLevel
import com.example.elyzareth.core.model.PipelineStage
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Operational implementation of the HumanDecisionContract for Elyzareth OS Baseline 02.
 * Enforces human authority invariants:
 * - Every approval, rejection, and veto is explicit and logged to the Evidence Ledger.
 * - Automated/system agents cannot manufacture human approval.
 * - Human vetoes immediately invalidate pending approvals and block governed transitions.
 */
class ElyzarethHumanDecisionAuthority(
    val projectId: String,
    private val evidenceLedger: EvidenceLedger
) : HumanDecisionContract {

    private val activeVetoes = ConcurrentHashMap<PipelineStage, VetoRecord>()
    private val stageApprovals = ConcurrentHashMap<PipelineStage, ApprovalRecord>()
    private val stageRejections = ConcurrentHashMap<PipelineStage, RejectionRecord>()
    private val gateApprovals = ConcurrentHashMap<GateLevel, GateApprovalRecord>()

    private val automatedActorBlacklist = setOf(
        "SYSTEM",
        "AUTOMATION",
        "BOT",
        "SCRIPT",
        "ALGORITHM",
        "AI_SERVICE",
        "AUTO_APPROVER",
        "SYNTHETIC_AGENT"
    )

    override fun requiresHumanApproval(stage: PipelineStage): Boolean {
        return stage.requiresHumanDecision
    }

    override suspend fun registerHumanVeto(
        stage: PipelineStage,
        userId: String,
        reason: String
    ): Result<Unit> {
        val validationResult = validateHumanActor(userId)
        if (validationResult.isFailure) {
            return Result.failure(validationResult.exceptionOrNull()!!)
        }
        if (reason.isBlank()) {
            return Result.failure(IllegalArgumentException("Veto rationale cannot be blank."))
        }

        // Veto strictly supersedes and invalidates any existing approval for this stage
        stageApprovals.remove(stage)

        val veto = VetoRecord(
            stage = stage,
            userId = userId,
            reason = reason,
            timestampMillis = System.currentTimeMillis()
        )
        activeVetoes[stage] = veto

        // Emit immutable evidence record
        val evidence = EvidenceRecord(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            stage = stage,
            timestampMillis = veto.timestampMillis,
            authorOrActor = userId,
            eventType = EvidenceEventType.HUMAN_VETOED,
            payloadHash = computeSha256("VETO:${stage.name}:$userId:$reason"),
            summary = "Human veto registered by $userId for stage ${stage.displayName}: $reason",
            metadata = mapOf(
                "action" to "HUMAN_VETO",
                "userId" to userId,
                "reason" to reason
            )
        )
        evidenceLedger.record(evidence)

        return Result.success(Unit)
    }

    override suspend fun approveStageTransition(
        stage: PipelineStage,
        userId: String,
        notes: String
    ): Result<Unit> {
        val validationResult = validateHumanActor(userId)
        if (validationResult.isFailure) {
            return Result.failure(validationResult.exceptionOrNull()!!)
        }

        // Invariant: Automated code must not silently override a human veto
        if (hasActiveVeto(stage)) {
            val veto = activeVetoes[stage]
            return Result.failure(
                IllegalStateException(
                    "Transition for stage ${stage.displayName} is blocked by an active human veto by ${veto?.userId}: ${veto?.reason}"
                )
            )
        }

        val approval = ApprovalRecord(
            stage = stage,
            userId = userId,
            notes = notes,
            timestampMillis = System.currentTimeMillis()
        )
        stageApprovals[stage] = approval
        stageRejections.remove(stage)

        // Emit immutable evidence record
        val evidence = EvidenceRecord(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            stage = stage,
            timestampMillis = approval.timestampMillis,
            authorOrActor = userId,
            eventType = EvidenceEventType.HUMAN_APPROVED,
            payloadHash = computeSha256("APPROVE:${stage.name}:$userId:$notes"),
            summary = "Human approval granted by $userId for stage ${stage.displayName}: $notes",
            metadata = mapOf(
                "action" to "HUMAN_APPROVAL",
                "userId" to userId,
                "notes" to notes
            )
        )
        evidenceLedger.record(evidence)

        return Result.success(Unit)
    }

    override suspend fun rejectStageTransition(
        stage: PipelineStage,
        userId: String,
        reason: String
    ): Result<Unit> {
        val validationResult = validateHumanActor(userId)
        if (validationResult.isFailure) {
            return Result.failure(validationResult.exceptionOrNull()!!)
        }
        if (reason.isBlank()) {
            return Result.failure(IllegalArgumentException("Rejection rationale cannot be blank."))
        }

        stageApprovals.remove(stage)
        val rejection = RejectionRecord(
            stage = stage,
            userId = userId,
            reason = reason,
            timestampMillis = System.currentTimeMillis()
        )
        stageRejections[stage] = rejection

        val evidence = EvidenceRecord(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            stage = stage,
            timestampMillis = rejection.timestampMillis,
            authorOrActor = userId,
            eventType = EvidenceEventType.HUMAN_REJECTED,
            payloadHash = computeSha256("REJECT:${stage.name}:$userId:$reason"),
            summary = "Human rejection registered by $userId for stage ${stage.displayName}: $reason",
            metadata = mapOf(
                "action" to "HUMAN_REJECTION",
                "userId" to userId,
                "reason" to reason
            )
        )
        evidenceLedger.record(evidence)

        return Result.success(Unit)
    }

    override suspend fun authorizeGatePassage(
        gate: GateLevel,
        officerId: String,
        notes: String
    ): Result<Unit> {
        val validationResult = validateHumanActor(officerId)
        if (validationResult.isFailure) {
            return Result.failure(validationResult.exceptionOrNull()!!)
        }

        // Boundary 9: DO NOT make G5 operational. G5 release criteria intentionally unresolved.
        if (gate == GateLevel.G5) {
            return Result.failure(
                IllegalStateException("Gate G5 authorization is disabled. Release criteria are intentionally unresolved at Baseline 02.")
            )
        }

        val correspondingStage = when (gate) {
            GateLevel.G1 -> PipelineStage.GATE_1_PRE_PRODUCTION
            GateLevel.G2 -> PipelineStage.GATE_2_CURATION_APPROVAL
            GateLevel.G3 -> PipelineStage.GATE_3_DNA_CONFORMANCE
            GateLevel.G4 -> PipelineStage.GATE_4_FORENSIC_CLEARANCE
            GateLevel.G5 -> PipelineStage.GATE_5_RELEASE_LOCK
        }

        if (hasActiveVeto(correspondingStage)) {
            val veto = activeVetoes[correspondingStage]
            return Result.failure(
                IllegalStateException("Gate ${gate.code} passage is blocked by human veto from ${veto?.userId}: ${veto?.reason}")
            )
        }

        val approval = GateApprovalRecord(
            gate = gate,
            officerId = officerId,
            notes = notes,
            timestampMillis = System.currentTimeMillis()
        )
        gateApprovals[gate] = approval

        val evidence = EvidenceRecord(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            stage = correspondingStage,
            timestampMillis = approval.timestampMillis,
            authorOrActor = officerId,
            eventType = EvidenceEventType.HUMAN_APPROVED,
            payloadHash = computeSha256("GATE_PASSAGE:${gate.code}:$officerId:$notes"),
            summary = "Human gate authorization granted by $officerId for ${gate.code} (${gate.title}): $notes",
            metadata = mapOf(
                "action" to "GATE_AUTHORIZATION",
                "gate" to gate.code,
                "officerId" to officerId,
                "notes" to notes
            )
        )
        evidenceLedger.record(evidence)

        return Result.success(Unit)
    }

    override fun hasActiveVeto(stage: PipelineStage): Boolean {
        return activeVetoes.containsKey(stage)
    }

    override fun isStageApproved(stage: PipelineStage): Boolean {
        // If there is an active veto, approval is strictly void
        if (hasActiveVeto(stage)) return false
        return stageApprovals.containsKey(stage)
    }

    private fun validateHumanActor(actorId: String): Result<Unit> {
        if (actorId.isBlank()) {
            return Result.failure(IllegalArgumentException("Human actor ID cannot be blank."))
        }
        val normalized = actorId.trim().uppercase()
        if (automatedActorBlacklist.contains(normalized) ||
            normalized.contains("BOT") ||
            normalized.contains("AUTO") ||
            normalized.contains("SYS") ||
            normalized.contains("SYNTHETIC") ||
            normalized.contains("ALGORITHM") ||
            normalized.contains("SCRIPT")
        ) {
            return Result.failure(
                SecurityException("Automated or synthetic actor '$actorId' cannot perform human authority decisions.")
            )
        }
        return Result.success(Unit)
    }

    private fun computeSha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private data class VetoRecord(
        val stage: PipelineStage,
        val userId: String,
        val reason: String,
        val timestampMillis: Long
    )

    private data class ApprovalRecord(
        val stage: PipelineStage,
        val userId: String,
        val notes: String,
        val timestampMillis: Long
    )

    private data class RejectionRecord(
        val stage: PipelineStage,
        val userId: String,
        val reason: String,
        val timestampMillis: Long
    )

    private data class GateApprovalRecord(
        val gate: GateLevel,
        val officerId: String,
        val notes: String,
        val timestampMillis: Long
    )
}
