package com.example.elyzareth.core.dataflow

import com.example.elyzareth.core.ledger.EvidenceLedger
import com.example.elyzareth.core.model.ActorRole
import com.example.elyzareth.core.model.ActorType
import com.example.elyzareth.core.model.EvidenceClass
import com.example.elyzareth.core.model.EvidenceEventType
import com.example.elyzareth.core.model.EvidenceRecord
import com.example.elyzareth.core.model.GateLevel
import com.example.elyzareth.core.model.PipelineStage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID

/**
 * Operational implementation of the HumanDecisionContract for Elyzareth OS Baseline 02 (Phase 0).
 *
 * Enforces human authority invariants:
 * - Fail-closed declared actor registry and role authorization policy.
 * - An unconfigured authority (empty `registeredActors` or empty `authorizationPolicy`)
 *   rejects all human decision operations.
 * - Every approval, rejection, review-cycle opening, and veto is validated and prepared
 *   before appending to the Evidence Ledger, and state is published only inside a protected
 *   `NonCancellable` section after evidence recording succeeds.
 * - Eliminates duplicate gate approval state: `GovernorSnapshot` is the sole authoritative
 *   holder of gate verification and approval records.
 */
class ElyzarethHumanDecisionAuthority(
    val projectId: String,
    private val evidenceLedger: EvidenceLedger,
    registeredActors: Map<String, ActorIdentity> = emptyMap(),
    private val authorizationPolicy: HumanAuthorizationPolicy = HumanAuthorizationPolicy()
) : HumanDecisionContract {

    private val authorityMutex = Mutex()

    private val registeredActors: Map<String, ActorIdentity> =
        Collections.unmodifiableMap(LinkedHashMap(registeredActors))

    private val _authorityState = MutableStateFlow(AuthorityState())

    override fun requiresHumanApproval(stage: PipelineStage): Boolean {
        return stage.requiresHumanDecision
    }

    override suspend fun <T> withDecisionLock(block: suspend () -> T): T =
        authorityMutex.withLock { block() }

    override suspend fun registerHumanVeto(
        stage: PipelineStage,
        userId: String,
        reason: String
    ): Result<Unit> {
        return authorityMutex.withLock {
            val identity = try {
                validateActor(
                    actorId = userId,
                    allowedRoles = authorizationPolicy.allowedRolesForVetoAndReview()
                )
            } catch (e: Exception) {
                return@withLock Result.failure(e)
            }

            if (reason.isBlank()) {
                return@withLock Result.failure(
                    IllegalArgumentException("Veto rationale cannot be blank.")
                )
            }

            val now = System.currentTimeMillis()
            val veto = VetoRecord(
                stage = stage,
                userId = identity.actorId,
                reason = reason,
                timestampMillis = now
            )

            val current = _authorityState.value
            val nextApprovals = LinkedHashMap(current.stageApprovals).apply {
                remove(stage)
            }
            val nextVetoes = LinkedHashMap(current.activeVetoes).apply {
                put(stage, veto)
            }
            val nextState = current.copy(
                activeVetoes = Collections.unmodifiableMap(nextVetoes),
                stageApprovals = Collections.unmodifiableMap(nextApprovals)
            )

            val evidence = EvidenceRecord(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                stage = stage,
                timestampMillis = now,
                authorOrActor = identity.actorId,
                actorId = identity.actorId,
                actorType = identity.actorType,
                evidenceClass = EvidenceClass.AUTHORITY,
                eventType = EvidenceEventType.HUMAN_VETOED,
                payloadHash = computeSha256("VETO:${stage.name}:${identity.actorId}:$reason"),
                summary = "Human veto registered by ${identity.actorId} for stage ${stage.displayName}: $reason",
                metadata = mapOf(
                    "action" to "HUMAN_VETO",
                    "userId" to identity.actorId,
                    "role" to identity.role.name,
                    "reason" to reason
                )
            )

            withContext(NonCancellable) {
                val evidenceResult = evidenceLedger.record(evidence)
                if (evidenceResult.isFailure) {
                    return@withContext Result.failure(
                        evidenceResult.exceptionOrNull()
                            ?: IllegalStateException("Evidence append failed")
                    )
                }

                _authorityState.value = nextState
                Result.success(Unit)
            }
        }
    }

    override suspend fun openNewReviewCycle(
        stage: PipelineStage,
        userId: String,
        resolutionNotes: String,
        commitGovernorState: () -> Unit
    ): Result<Unit> {
        return authorityMutex.withLock {
            if (stage == PipelineStage.GATE_5_RELEASE_LOCK || stage == PipelineStage.MASTER_PLAYER) {
                return@withLock Result.failure(
                    IllegalStateException(
                        "Stage ${stage.displayName} review cycle is disabled at Baseline 02."
                    )
                )
            }

            val identity = try {
                validateActor(
                    actorId = userId,
                    allowedRoles = authorizationPolicy.allowedRolesForVetoAndReview()
                )
            } catch (e: Exception) {
                return@withLock Result.failure(e)
            }

            if (resolutionNotes.isBlank()) {
                return@withLock Result.failure(
                    IllegalArgumentException("Review cycle resolution notes cannot be blank.")
                )
            }

            val current = _authorityState.value
            val gateForStage = stageToGate(stage)
            val rejectedGateForStage = gateForStage != null && current.gateRejections.containsKey(gateForStage)
            if (!current.activeVetoes.containsKey(stage) &&
                !current.stageRejections.containsKey(stage) &&
                !rejectedGateForStage
            ) {
                return@withLock Result.failure(
                    IllegalStateException(
                        "Stage ${stage.displayName} does not have an active veto or rejection requiring a new review cycle."
                    )
                )
            }

            val now = System.currentTimeMillis()
            val nextVetoes = LinkedHashMap(current.activeVetoes).apply {
                remove(stage)
            }
            val nextRejections = LinkedHashMap(current.stageRejections).apply {
                remove(stage)
            }
            val nextApprovals = LinkedHashMap(current.stageApprovals).apply {
                remove(stage)
            }
            val nextGateRejections = LinkedHashMap(current.gateRejections).apply {
                if (gateForStage != null) {
                    remove(gateForStage)
                }
            }
            val nextState = current.copy(
                activeVetoes = Collections.unmodifiableMap(nextVetoes),
                stageRejections = Collections.unmodifiableMap(nextRejections),
                gateRejections = Collections.unmodifiableMap(nextGateRejections),
                stageApprovals = Collections.unmodifiableMap(nextApprovals)
            )

            val reviewMetadata = LinkedHashMap<String, String>().apply {
                put("action", "OPEN_NEW_REVIEW_CYCLE")
                put("stage", stage.name)
                if (gateForStage != null) {
                    put("gate", gateForStage.code)
                }
                put("userId", identity.actorId)
                put("role", identity.role.name)
                put("resolutionNotes", resolutionNotes)
            }

            val evidence = EvidenceRecord(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                stage = stage,
                timestampMillis = now,
                authorOrActor = identity.actorId,
                actorId = identity.actorId,
                actorType = identity.actorType,
                evidenceClass = EvidenceClass.AUTHORITY,
                eventType = EvidenceEventType.HUMAN_REVISED,
                payloadHash = computeSha256("NEW_REVIEW_CYCLE:${stage.name}:${identity.actorId}:$resolutionNotes"),
                summary = "New human review cycle opened by ${identity.actorId} for stage ${stage.displayName}: $resolutionNotes",
                metadata = reviewMetadata
            )

            withContext(NonCancellable) {
                val evidenceResult = evidenceLedger.record(evidence)
                if (evidenceResult.isFailure) {
                    return@withContext Result.failure(
                        evidenceResult.exceptionOrNull()
                            ?: IllegalStateException("Evidence append failed")
                    )
                }

                _authorityState.value = nextState
                commitGovernorState()
                Result.success(Unit)
            }
        }
    }

    override suspend fun approveStageTransition(
        stage: PipelineStage,
        userId: String,
        notes: String
    ): Result<Unit> {
        return authorityMutex.withLock {
            val identity = try {
                validateActor(
                    actorId = userId,
                    allowedRoles = authorizationPolicy.allowedRolesForStage(stage)
                )
            } catch (e: Exception) {
                return@withLock Result.failure(e)
            }

            if (notes.isBlank()) {
                return@withLock Result.failure(
                    IllegalArgumentException("Approval notes cannot be blank.")
                )
            }

            val current = _authorityState.value
            val existingVeto = current.activeVetoes[stage]
            if (existingVeto != null) {
                return@withLock Result.failure(
                    IllegalStateException(
                        "Transition for stage ${stage.displayName} is blocked by an active human veto by ${existingVeto.userId}: ${existingVeto.reason}"
                    )
                )
            }
            val existingRejection = current.stageRejections[stage]
            if (existingRejection != null) {
                return@withLock Result.failure(
                    IllegalStateException(
                        "Stage ${stage.displayName} remains rejected until an explicit new review cycle is opened: ${existingRejection.reason}"
                    )
                )
            }
            val gateForStage = stageToGate(stage)
            val existingGateRejection = if (gateForStage != null) current.gateRejections[gateForStage] else null
            if (existingGateRejection != null) {
                return@withLock Result.failure(
                    IllegalStateException(
                        "Gate ${existingGateRejection.gate.code} remains rejected until an explicit new review cycle is opened: ${existingGateRejection.reason}"
                    )
                )
            }

            val now = System.currentTimeMillis()
            val approval = ApprovalRecord(
                stage = stage,
                userId = identity.actorId,
                notes = notes,
                timestampMillis = now
            )

            val nextApprovals = LinkedHashMap(current.stageApprovals).apply {
                put(stage, approval)
            }
            val nextState = current.copy(
                stageApprovals = Collections.unmodifiableMap(nextApprovals)
            )

            val evidence = EvidenceRecord(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                stage = stage,
                timestampMillis = now,
                authorOrActor = identity.actorId,
                actorId = identity.actorId,
                actorType = identity.actorType,
                evidenceClass = EvidenceClass.AUTHORITY,
                eventType = EvidenceEventType.HUMAN_APPROVED,
                payloadHash = computeSha256("APPROVE:${stage.name}:${identity.actorId}:$notes"),
                summary = "Human approval granted by ${identity.actorId} for stage ${stage.displayName}: $notes",
                metadata = mapOf(
                    "action" to "HUMAN_APPROVAL",
                    "userId" to identity.actorId,
                    "role" to identity.role.name,
                    "notes" to notes
                )
            )

            withContext(NonCancellable) {
                val evidenceResult = evidenceLedger.record(evidence)
                if (evidenceResult.isFailure) {
                    return@withContext Result.failure(
                        evidenceResult.exceptionOrNull()
                            ?: IllegalStateException("Evidence append failed")
                    )
                }

                _authorityState.value = nextState
                Result.success(Unit)
            }
        }
    }

    override suspend fun rejectStageTransition(
        stage: PipelineStage,
        userId: String,
        reason: String
    ): Result<Unit> {
        return authorityMutex.withLock {
            val identity = try {
                validateActor(
                    actorId = userId,
                    allowedRoles = authorizationPolicy.allowedRolesForStage(stage)
                )
            } catch (e: Exception) {
                return@withLock Result.failure(e)
            }

            if (reason.isBlank()) {
                return@withLock Result.failure(
                    IllegalArgumentException("Rejection rationale cannot be blank.")
                )
            }

            val now = System.currentTimeMillis()
            val rejection = RejectionRecord(
                stage = stage,
                userId = identity.actorId,
                reason = reason,
                timestampMillis = now
            )

            val current = _authorityState.value
            val nextApprovals = LinkedHashMap(current.stageApprovals).apply {
                remove(stage)
            }
            val nextRejections = LinkedHashMap(current.stageRejections).apply {
                put(stage, rejection)
            }
            val nextState = current.copy(
                stageApprovals = Collections.unmodifiableMap(nextApprovals),
                stageRejections = Collections.unmodifiableMap(nextRejections)
            )

            val evidence = EvidenceRecord(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                stage = stage,
                timestampMillis = now,
                authorOrActor = identity.actorId,
                actorId = identity.actorId,
                actorType = identity.actorType,
                evidenceClass = EvidenceClass.AUTHORITY,
                eventType = EvidenceEventType.HUMAN_REJECTED,
                payloadHash = computeSha256("REJECT:${stage.name}:${identity.actorId}:$reason"),
                summary = "Human rejection registered by ${identity.actorId} for stage ${stage.displayName}: $reason",
                metadata = mapOf(
                    "action" to "HUMAN_REJECTION",
                    "userId" to identity.actorId,
                    "role" to identity.role.name,
                    "reason" to reason
                )
            )

            withContext(NonCancellable) {
                val evidenceResult = evidenceLedger.record(evidence)
                if (evidenceResult.isFailure) {
                    return@withContext Result.failure(
                        evidenceResult.exceptionOrNull()
                            ?: IllegalStateException("Evidence append failed")
                    )
                }

                _authorityState.value = nextState
                Result.success(Unit)
            }
        }
    }

    override suspend fun authorizeGatePassage(
        gate: GateLevel,
        actorId: String,
        expectedRole: String,
        remarks: String,
        commitGovernorState: () -> Unit
    ): Result<Unit> {
        return authorityMutex.withLock {
            // Boundary 9: DO NOT make G5 operational. G5 release criteria intentionally unresolved.
            if (gate == GateLevel.G5) {
                return@withLock Result.failure(
                    IllegalStateException(
                        "Gate G5 authorization is disabled. Release criteria are intentionally unresolved at Baseline 02."
                    )
                )
            }

            val identity = try {
                validateActor(
                    actorId = actorId,
                    allowedRoles = authorizationPolicy.allowedRolesForGate(gate)
                )
            } catch (e: Exception) {
                return@withLock Result.failure(e)
            }

            if (expectedRole.isBlank() || identity.role.name != expectedRole) {
                return@withLock Result.failure(
                    SecurityException(
                        "Signatory role mismatch for $actorId: expected ${identity.role.name} but received '$expectedRole'."
                    )
                )
            }

            if (remarks.isBlank()) {
                return@withLock Result.failure(
                    IllegalArgumentException("Gate authorization remarks cannot be blank.")
                )
            }

            val correspondingStage = mapGateToStage(gate)
            val current = _authorityState.value
            val existingVeto = current.activeVetoes[correspondingStage]
            if (existingVeto != null) {
                return@withLock Result.failure(
                    IllegalStateException(
                        "Gate ${gate.code} passage is blocked by human veto from ${existingVeto.userId}: ${existingVeto.reason}"
                    )
                )
            }
            val existingStageRejection = current.stageRejections[correspondingStage]
            if (existingStageRejection != null) {
                return@withLock Result.failure(
                    IllegalStateException(
                        "Stage ${correspondingStage.displayName} remains rejected until a new review cycle is opened: ${existingStageRejection.reason}"
                    )
                )
            }
            val existingRejection = current.gateRejections[gate]
            if (existingRejection != null) {
                return@withLock Result.failure(
                    IllegalStateException(
                        "Gate ${gate.code} remains rejected until a new review cycle is opened: ${existingRejection.reason}"
                    )
                )
            }

            val now = System.currentTimeMillis()
            val approval = ApprovalRecord(
                stage = correspondingStage,
                userId = identity.actorId,
                notes = remarks,
                timestampMillis = now
            )
            val nextApprovals = LinkedHashMap(current.stageApprovals).apply {
                put(correspondingStage, approval)
            }
            val nextState = current.copy(
                stageApprovals = Collections.unmodifiableMap(nextApprovals)
            )

            val preparedEvidence = EvidenceRecord(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                stage = correspondingStage,
                timestampMillis = now,
                authorOrActor = identity.actorId,
                actorId = identity.actorId,
                actorType = identity.actorType,
                evidenceClass = EvidenceClass.AUTHORITY,
                eventType = EvidenceEventType.HUMAN_APPROVED,
                payloadHash = computeSha256("GATE_PASSAGE:${gate.code}:${identity.actorId}:$remarks"),
                summary = "Human gate authorization granted by ${identity.actorId} for ${gate.code} (${gate.title}): $remarks",
                metadata = mapOf(
                    "action" to "GATE_AUTHORIZATION",
                    "gate" to gate.code,
                    "officerId" to identity.actorId,
                    "role" to identity.role.name,
                    "notes" to remarks
                )
            )

            // COMMIT:
            // Once evidence append begins, defer caller cancellation until the complete
            // in-memory commit sequence (evidence record + authority state + GovernorSnapshot publication) finishes.
            withContext(NonCancellable) {
                val evidenceResult = evidenceLedger.record(preparedEvidence)
                if (evidenceResult.isFailure) {
                    return@withContext Result.failure(
                        evidenceResult.exceptionOrNull()
                            ?: IllegalStateException("Evidence append failed")
                    )
                }

                // Pre-prepared authority state and GovernorSnapshot reference assignments under locks.
                _authorityState.value = nextState
                commitGovernorState()

                Result.success(Unit)
            }
        }
    }

    override suspend fun rejectGatePassage(
        gate: GateLevel,
        officerId: String,
        reason: String,
        commitGovernorState: () -> Unit
    ): Result<Unit> {
        return authorityMutex.withLock {
            if (gate == GateLevel.G5) {
                return@withLock Result.failure(
                    IllegalStateException(
                        "Gate G5 rejection is disabled. Release criteria are intentionally unresolved at Baseline 02."
                    )
                )
            }

            val identity = try {
                validateActor(
                    actorId = officerId,
                    allowedRoles = authorizationPolicy.allowedRolesForGate(gate)
                )
            } catch (e: Exception) {
                return@withLock Result.failure(e)
            }

            if (reason.isBlank()) {
                return@withLock Result.failure(
                    IllegalArgumentException("Gate rejection reason cannot be blank.")
                )
            }

            val correspondingStage = mapGateToStage(gate)
            val now = System.currentTimeMillis()
            val rejection = GateRejectionRecord(
                gate = gate,
                officerId = identity.actorId,
                reason = reason,
                timestampMillis = now
            )
            val current = _authorityState.value
            val nextGateRejections = LinkedHashMap(current.gateRejections).apply {
                put(gate, rejection)
            }
            val nextState = current.copy(
                gateRejections = Collections.unmodifiableMap(nextGateRejections)
            )
            val preparedEvidence = EvidenceRecord(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                stage = correspondingStage,
                timestampMillis = now,
                authorOrActor = identity.actorId,
                actorId = identity.actorId,
                actorType = identity.actorType,
                evidenceClass = EvidenceClass.AUTHORITY,
                eventType = EvidenceEventType.HUMAN_REJECTED,
                payloadHash = computeSha256("GATE_REJECTION:${gate.code}:${identity.actorId}:$reason"),
                summary = "Human gate rejection registered by ${identity.actorId} for ${gate.code} (${gate.title}): $reason",
                metadata = mapOf(
                    "action" to "GATE_REJECTION",
                    "gate" to gate.code,
                    "officerId" to identity.actorId,
                    "role" to identity.role.name,
                    "reason" to reason
                )
            )

            withContext(NonCancellable) {
                val evidenceResult = evidenceLedger.record(preparedEvidence)
                if (evidenceResult.isFailure) {
                    return@withContext Result.failure(
                        evidenceResult.exceptionOrNull()
                            ?: IllegalStateException("Evidence append failed")
                    )
                }

                _authorityState.value = nextState
                commitGovernorState()
                Result.success(Unit)
            }
        }
    }

    override fun hasActiveGateRejection(gate: GateLevel): Boolean =
        _authorityState.value.gateRejections.containsKey(gate)

    override fun hasActiveVeto(stage: PipelineStage): Boolean {
        return _authorityState.value.activeVetoes.containsKey(stage)
    }

    override fun isStageApproved(stage: PipelineStage): Boolean {
        val current = _authorityState.value
        if (current.activeVetoes.containsKey(stage)) return false
        if (current.stageRejections.containsKey(stage)) return false
        val gate = stageToGate(stage)
        if (gate != null && current.gateRejections.containsKey(gate)) return false
        return current.stageApprovals.containsKey(stage)
    }

    private fun validateActor(
        actorId: String,
        allowedRoles: Set<ActorRole>
    ): ActorIdentity {
        if (actorId.isBlank()) {
            throw SecurityException("Blank actor ID")
        }

        val identity = registeredActors[actorId]
            ?: throw SecurityException("Unregistered actor: $actorId")

        if (identity.actorId != actorId) {
            throw SecurityException("Actor identity mismatch")
        }

        if (identity.actorType != ActorType.HUMAN) {
            throw SecurityException("Non-human actor cannot authorize")
        }

        if (identity.role !in allowedRoles || allowedRoles.isEmpty()) {
            throw SecurityException("Actor role not authorized")
        }

        return identity
    }

    private fun mapGateToStage(gate: GateLevel): PipelineStage = when (gate) {
        GateLevel.G1 -> PipelineStage.GATE_1_PRE_PRODUCTION
        GateLevel.G2 -> PipelineStage.GATE_2_CURATION_APPROVAL
        GateLevel.G3 -> PipelineStage.GATE_3_DNA_CONFORMANCE
        GateLevel.G4 -> PipelineStage.GATE_4_FORENSIC_CLEARANCE
        GateLevel.G5 -> PipelineStage.GATE_5_RELEASE_LOCK
    }

    private fun stageToGate(stage: PipelineStage): GateLevel? = when (stage) {
        PipelineStage.GATE_1_PRE_PRODUCTION -> GateLevel.G1
        PipelineStage.GATE_2_CURATION_APPROVAL -> GateLevel.G2
        PipelineStage.GATE_3_DNA_CONFORMANCE -> GateLevel.G3
        PipelineStage.GATE_4_FORENSIC_CLEARANCE -> GateLevel.G4
        PipelineStage.GATE_5_RELEASE_LOCK -> GateLevel.G5
        else -> null
    }

    private fun computeSha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private data class AuthorityState(
        val activeVetoes: Map<PipelineStage, VetoRecord> = Collections.emptyMap(),
        val stageApprovals: Map<PipelineStage, ApprovalRecord> = Collections.emptyMap(),
        val stageRejections: Map<PipelineStage, RejectionRecord> = Collections.emptyMap(),
        val gateRejections: Map<GateLevel, GateRejectionRecord> = Collections.emptyMap()
    )

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

    private data class GateRejectionRecord(
        val gate: GateLevel,
        val officerId: String,
        val reason: String,
        val timestampMillis: Long
    )
}
