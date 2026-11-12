package com.example.elyzareth.core.governor

import com.example.elyzareth.core.dataflow.HumanDecisionContract
import com.example.elyzareth.core.ledger.EvidenceLedger
import com.example.elyzareth.core.model.ActorType
import com.example.elyzareth.core.model.EvidenceClass
import com.example.elyzareth.core.model.EvidenceEventType
import com.example.elyzareth.core.model.EvidenceRecord
import com.example.elyzareth.core.model.GateLevel
import com.example.elyzareth.core.model.GateCriterionCheck
import com.example.elyzareth.core.model.GateStatus
import com.example.elyzareth.core.model.GateVerificationRecord
import com.example.elyzareth.core.model.HumanSignOff
import com.example.elyzareth.core.model.PipelineStage
import com.example.elyzareth.core.model.ProjectAggregate
import com.example.elyzareth.core.model.ReleasePackage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.UUID

/**
 * Authoritative immutable governance snapshot combining the canonical [ProjectAggregate]
 * and its derived [PipelineState].
 *
 * Consumers requiring a transactionally consistent view of project and pipeline state
 * must observe [ElyzarethGovernorEngine.governorSnapshot].
 */
data class GovernorSnapshot(
    val project: ProjectAggregate,
    val pipeline: PipelineState
)

/**
 * Operational Governor Engine for Elyzareth OS Baseline 02 (Phase 0).
 *
 * Enforces:
 * - Single authoritative `GovernorSnapshot` state publication.
 * - Consistent lock acquisition order: Governor (`governorMutex`) -> Authority -> EvidenceLedger.
 * - Mandatory sequential stage transitions (no stage skipping, no self-transitions, no backward jumps).
 * - Sequential gate prerequisites (`G1`..`G4`) and stage-to-gate alignment.
 * - Evidence-first commit inside `withContext(NonCancellable)` so no state is published if evidence
 *   recording fails, and cancellation cannot split evidence append from snapshot publication.
 * - Gate 5 and Master Player remain strictly locked (`isReleaseLocked = false`).
 */
class ElyzarethGovernorEngine(
    val projectId: String,
    val projectTitle: String = "Elyzareth Track",
    private val humanDecisionAuthority: HumanDecisionContract,
    private val evidenceLedger: EvidenceLedger,
    initialStage: PipelineStage = PipelineStage.IDEA
) : GovernorContract {

    private val governorMutex = Mutex()

    init {
        require(initialStage == PipelineStage.IDEA) {
            "A new project must start at IDEA. Non-IDEA initialization requires a separately validated recovery/restore path."
        }
    }

    private val initialProject: ProjectAggregate = immutableProject(
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

    private val initialPipeline: PipelineState = derivePipelineState(
        project = initialProject,
        completedStages = emptySet(),
        activeGate = null,
        statusMessage = "Governor initialized at stage: ${initialStage.displayName}"
    )

    private val initialSnapshot = GovernorSnapshot(
        project = initialProject,
        pipeline = initialPipeline
    )

    private val _governorSnapshot = MutableStateFlow(initialSnapshot)

    /**
     * Sole authoritative governance state stream.
     */
    val governorSnapshot: StateFlow<GovernorSnapshot> = _governorSnapshot.asStateFlow()

    // Legacy GovernorContract compatibility projections only:
    private val _currentProjectState = MutableStateFlow<ProjectAggregate?>(initialSnapshot.project)
    override val currentProjectState: StateFlow<ProjectAggregate?> = _currentProjectState.asStateFlow()

    private val _pipelineState = MutableStateFlow(initialSnapshot.pipeline)
    override val pipelineState: StateFlow<PipelineState> = _pipelineState.asStateFlow()

    override suspend fun transitionToStage(
        targetStage: PipelineStage,
        rationale: String
    ): Result<PipelineStage> {
        return governorMutex.withLock {
            humanDecisionAuthority.withDecisionLock {
            val currentSnapshot = _governorSnapshot.value
            val currentStage = currentSnapshot.project.currentStage

            // Boundary 9 & 11: Gate 5 and Master Player are strictly locked at Baseline 02
            if (targetStage == PipelineStage.GATE_5_RELEASE_LOCK || targetStage == PipelineStage.MASTER_PLAYER) {
                return@withDecisionLock Result.failure(
                    IllegalStateException(
                        "Stage ${targetStage.displayName} is non-operational and locked at Baseline 02."
                    )
                )
            }

            if (rationale.isBlank()) {
                return@withDecisionLock Result.failure(
                    IllegalArgumentException("Stage transition rationale cannot be blank.")
                )
            }

            // Mandatory sequential transition validation (rejects self-transition, skipping, and backward transition)
            validateSequentialTransition(
                currentStage = currentStage,
                targetStage = targetStage
            ).getOrElse {
                return@withDecisionLock Result.failure(it)
            }

            // Invariant: Human Veto strictly prevents transition
            if (humanDecisionAuthority.hasActiveVeto(currentStage)) {
                return@withDecisionLock Result.failure(
                    IllegalStateException(
                        "Stage transition blocked: Active human veto in effect on current stage ${currentStage.displayName}."
                    )
                )
            }
            if (humanDecisionAuthority.hasActiveVeto(targetStage)) {
                return@withDecisionLock Result.failure(
                    IllegalStateException(
                        "Stage transition blocked: Active human veto in effect on target stage ${targetStage.displayName}."
                    )
                )
            }

            // A veto/rejection against any previously completed human-decision stage
            // reopens that decision and blocks further progression until reviewed.
            for (completedStage in currentSnapshot.pipeline.completedStages) {
                if (humanDecisionAuthority.hasActiveVeto(completedStage)) {
                    return@withDecisionLock Result.failure(
                        IllegalStateException(
                            "Stage transition blocked: completed stage ${completedStage.displayName} has an active human veto."
                        )
                    )
                }
                if (completedStage.requiresHumanDecision &&
                    !humanDecisionAuthority.isStageApproved(completedStage)
                ) {
                    return@withDecisionLock Result.failure(
                        IllegalStateException(
                            "Stage transition blocked: completed stage ${completedStage.displayName} requires renewed human approval."
                        )
                    )
                }
                val completedGate = stageToGate(completedStage)
                if (completedGate != null) {
                    val completedGateStatus = currentSnapshot.project.gateRecords[completedGate]?.status
                    if (completedGateStatus != GateStatus.APPROVED ||
                        humanDecisionAuthority.hasActiveGateRejection(completedGate)
                    ) {
                        return@withDecisionLock Result.failure(
                            IllegalStateException(
                                "Stage transition blocked: completed gate ${completedGate.code} is not in an active APPROVED state."
                            )
                        )
                    }
                }
            }

            // Gate stage prerequisite: Cannot advance out of a gate stage (G1..G4) unless that gate is APPROVED
            val requiredGateForCurrentStage = stageToGate(currentStage)
            if (requiredGateForCurrentStage != null) {
                val gateStatus = currentSnapshot.project.gateRecords[requiredGateForCurrentStage]?.status
                if (gateStatus != GateStatus.APPROVED) {
                    return@withDecisionLock Result.failure(
                        IllegalStateException(
                            "Gate ${requiredGateForCurrentStage.code} must be APPROVED before advancing from ${currentStage.displayName} to ${targetStage.displayName}."
                        )
                    )
                }
            }

            // Invariant: Human-required transition cannot bypass human authorization
            if (currentStage.requiresHumanDecision && !humanDecisionAuthority.isStageApproved(currentStage)) {
                return@withDecisionLock Result.failure(
                    IllegalStateException(
                        "Human authorization required: Stage ${currentStage.displayName} cannot advance without explicit human approval."
                    )
                )
            }

            // PREPARE proposed immutable state and evidence BEFORE appending to ledger
            val now = System.currentTimeMillis()
            val newCompleted = LinkedHashSet(currentSnapshot.pipeline.completedStages).apply {
                add(currentStage)
            }
            val proposedProject = immutableProject(
                currentSnapshot.project.copy(
                    currentStage = targetStage,
                    lastModifiedTimestamp = now
                )
            )
            val statusMessage =
                "Transitioned from ${currentStage.displayName} to ${targetStage.displayName}. Rationale: $rationale"
            val proposedPipeline = derivePipelineState(
                project = proposedProject,
                completedStages = newCompleted,
                activeGate = currentSnapshot.pipeline.activeGate,
                statusMessage = statusMessage
            )
            val proposedSnapshot = GovernorSnapshot(
                project = proposedProject,
                pipeline = proposedPipeline
            )

            val evidence = EvidenceRecord(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                stage = targetStage,
                timestampMillis = now,
                authorOrActor = "GovernorEngine",
                actorId = "GovernorEngine",
                actorType = ActorType.SYSTEM,
                evidenceClass = EvidenceClass.ANALYTICAL,
                eventType = EvidenceEventType.STAGE_TRANSITIONED,
                payloadHash = computeSha256("STAGE_TRANSITION:${currentStage.name}:${targetStage.name}:$rationale"),
                summary = "Governor transitioned from ${currentStage.displayName} to ${targetStage.displayName}. Rationale: $rationale",
                metadata = mapOf(
                    "fromStage" to currentStage.name,
                    "toStage" to targetStage.name,
                    "rationale" to rationale
                )
            )

            // COMMIT inside NonCancellable so evidence append and snapshot publication cannot be split
            withContext(NonCancellable) {
                val evidenceResult = evidenceLedger.record(evidence)
                if (evidenceResult.isFailure) {
                    return@withContext Result.failure(
                        evidenceResult.exceptionOrNull()
                            ?: IllegalStateException("Evidence append failed")
                    )
                }

                commitAuthoritativeSnapshot(proposedSnapshot)
                Result.success(targetStage)
            }
            }
        }
    }

    override suspend fun evaluateGate(gate: GateLevel): Result<GateVerificationRecord> =
        evaluateGate(gate, emptyList())

    override suspend fun evaluateGate(
        gate: GateLevel,
        criteriaResults: List<GateCriterionCheck>
    ): Result<GateVerificationRecord> {
        return governorMutex.withLock {
            humanDecisionAuthority.withDecisionLock {
                // Boundary 9: Gate 5 is non-operational at Baseline 02
                if (gate == GateLevel.G5) {
                    return@withDecisionLock Result.failure(
                        IllegalStateException(
                            "Gate 5 evaluation is disabled. Release criteria are intentionally unresolved at Baseline 02."
                        )
                    )
                }

                if (criteriaResults.isEmpty()) {
                    return@withDecisionLock Result.failure(
                        IllegalArgumentException(
                            "Gate ${gate.code} cannot be evaluated without explicit criterion results. Empty evaluations are not valid evidence."
                        )
                    )
                }
                if (criteriaResults.any {
                        it.id.isBlank() || it.label.isBlank() || it.explanation.isBlank()
                    } || criteriaResults.map { it.id }.distinct().size != criteriaResults.size
                ) {
                    return@withDecisionLock Result.failure(
                        IllegalArgumentException("Gate criteria must have unique non-blank IDs, labels and explanations.")
                    )
                }

                val correspondingStage = mapGateToStage(gate)
                val currentSnapshot = _governorSnapshot.value
                val currentStage = currentSnapshot.project.currentStage

                // Prerequisite 1: Project must currently be at the corresponding gate stage
                if (currentStage != correspondingStage) {
                    return@withDecisionLock Result.failure(
                        IllegalStateException(
                            "Cannot evaluate ${gate.code} while project is at stage ${currentStage.displayName}; requires ${correspondingStage.displayName}."
                        )
                    )
                }

                // Prerequisite 2: All preceding gates G1..G(n-1) must be APPROVED
                val precedingGates = GateLevel.entries.filter { it.ordinal < gate.ordinal }
                for (prevGate in precedingGates) {
                    val prevStatus = currentSnapshot.project.gateRecords[prevGate]?.status
                    if (prevStatus != GateStatus.APPROVED) {
                        return@withDecisionLock Result.failure(
                            IllegalStateException(
                                "Cannot evaluate ${gate.code}: preceding gate ${prevGate.code} is not APPROVED (current: $prevStatus)."
                            )
                        )
                    }
                }

                if (humanDecisionAuthority.hasActiveVeto(correspondingStage)) {
                    return@withDecisionLock Result.failure(
                        IllegalStateException(
                            "Gate ${gate.code} evaluation is blocked by an active human veto."
                        )
                    )
                }
                if (humanDecisionAuthority.hasActiveGateRejection(gate)) {
                    return@withDecisionLock Result.failure(
                        IllegalStateException(
                            "Gate ${gate.code} remains rejected until an explicit new review cycle is opened."
                        )
                    )
                }

                // PREPARE immutable gate record, proposed snapshot, and evidence record
                val now = System.currentTimeMillis()
                val evaluatedRecord = immutableGateRecord(
                    GateVerificationRecord(
                        gate = gate,
                        status = GateStatus.HUMAN_REVIEW_REQUIRED,
                        criteriaResults = criteriaResults.toList(),
                        evaluatedTimestamp = now
                    )
                )

                val updatedGateRecords = LinkedHashMap(currentSnapshot.project.gateRecords).apply {
                    put(gate, evaluatedRecord)
                }
                val proposedProject = immutableProject(
                    currentSnapshot.project.copy(
                        gateRecords = updatedGateRecords,
                        lastModifiedTimestamp = now
                    )
                )
                val proposedPipeline = derivePipelineState(
                    project = proposedProject,
                    completedStages = currentSnapshot.pipeline.completedStages,
                    activeGate = gate,
                    statusMessage = "Gate ${gate.code} evaluated: Pending human sign-off."
                )
                val proposedSnapshot = GovernorSnapshot(
                    project = proposedProject,
                    pipeline = proposedPipeline
                )

                val orderedCriteria = criteriaResults.sortedBy { it.id }
                val criteriaEnvelope = orderedCriteria.joinToString(separator = "") { criterion ->
                    buildString {
                        append(criterion.id.length).append(':').append(criterion.id)
                        append(criterion.label.length).append(':').append(criterion.label)
                        append(if (criterion.satisfied) "1" else "0")
                        append(criterion.explanation.length).append(':').append(criterion.explanation)
                    }
                }
                val criteriaMetadata = buildMap<String, String> {
                    orderedCriteria.forEachIndexed { index, criterion ->
                        put("criteria.$index.id", criterion.id)
                        put("criteria.$index.label", criterion.label)
                        put("criteria.$index.satisfied", criterion.satisfied.toString())
                        put("criteria.$index.explanation", criterion.explanation)
                    }
                }
                val evidence = EvidenceRecord(
                    id = UUID.randomUUID().toString(),
                    projectId = projectId,
                    stage = correspondingStage,
                    timestampMillis = now,
                    authorOrActor = "GovernorEngine",
                    actorId = "GovernorEngine",
                    actorType = ActorType.SYSTEM,
                    evidenceClass = EvidenceClass.ANALYTICAL,
                    eventType = EvidenceEventType.GATE_EVALUATED,
                    payloadHash = computeSha256("GATE_EVAL:${gate.code}:$now:$criteriaEnvelope"),
                    summary = "Gate ${gate.code} evaluated against ${criteriaResults.size} explicit criteria: Status is HUMAN_REVIEW_REQUIRED.",
                    metadata = mapOf(
                        "gate" to gate.code,
                        "status" to GateStatus.HUMAN_REVIEW_REQUIRED.name,
                        "criteriaCount" to criteriaResults.size.toString(),
                        "satisfiedCount" to criteriaResults.count { it.satisfied }.toString()
                    ) + criteriaMetadata
                )

                // COMMIT inside NonCancellable
                withContext(NonCancellable) {
                    val evidenceResult = evidenceLedger.record(evidence)
                    if (evidenceResult.isFailure) {
                        return@withContext Result.failure(
                            evidenceResult.exceptionOrNull()
                                ?: IllegalStateException("Evidence append failed")
                        )
                    }

                    commitAuthoritativeSnapshot(proposedSnapshot)
                    Result.success(evaluatedRecord)
                }
            }
        }
    }

    override suspend fun submitHumanGateSignOff(
        gate: GateLevel,
        signOff: HumanSignOff
    ): Result<GateVerificationRecord> {
        return governorMutex.withLock {
            // Boundary 9: Gate 5 sign-off is disabled
            if (gate == GateLevel.G5) {
                return@withLock Result.failure(
                    IllegalStateException(
                        "Gate 5 sign-off is disabled. Release criteria remain intentionally unresolved at Baseline 02."
                    )
                )
            }

            if (signOff.signedAtTimestamp <= 0L) {
                return@withLock Result.failure(
                    IllegalArgumentException("Human sign-off timestamp must be positive.")
                )
            }
            if (signOff.signatoryRole.isBlank()) {
                return@withLock Result.failure(
                    IllegalArgumentException("Human sign-off role cannot be blank.")
                )
            }

            val correspondingStage = mapGateToStage(gate)
            val currentSnapshot = _governorSnapshot.value
            val currentStage = currentSnapshot.project.currentStage

            // Prerequisite 1: Project must be at the corresponding gate stage
            if (currentStage != correspondingStage) {
                return@withLock Result.failure(
                    IllegalStateException(
                        "Cannot sign off ${gate.code} while project is at stage ${currentStage.displayName}; requires ${correspondingStage.displayName}."
                    )
                )
            }

            // Prerequisite 2: Preceding gates must be APPROVED
            val precedingGates = GateLevel.entries.filter { it.ordinal < gate.ordinal }
            for (prevGate in precedingGates) {
                val prevStatus = currentSnapshot.project.gateRecords[prevGate]?.status
                if (prevStatus != GateStatus.APPROVED) {
                    return@withLock Result.failure(
                        IllegalStateException(
                            "Cannot sign off ${gate.code}: preceding gate ${prevGate.code} is not APPROVED."
                        )
                    )
                }
            }

            // Prerequisite 3: Gate must have been evaluated and currently be in HUMAN_REVIEW_REQUIRED
            val existingGateRecord = currentSnapshot.project.gateRecords[gate]
            if (existingGateRecord?.status != GateStatus.HUMAN_REVIEW_REQUIRED) {
                return@withLock Result.failure(
                    IllegalStateException(
                        "Gate ${gate.code} cannot be signed off before a valid evaluation places it in HUMAN_REVIEW_REQUIRED (current: ${existingGateRecord?.status})."
                    )
                )
            }
            if (existingGateRecord.criteriaResults.isEmpty() ||
                existingGateRecord.criteriaResults.any { !it.satisfied }
            ) {
                return@withLock Result.failure(
                    IllegalStateException(
                        "Gate ${gate.code} cannot be approved: every required criterion must be present and satisfied."
                    )
                )
            }

            if (humanDecisionAuthority.hasActiveVeto(correspondingStage)) {
                return@withLock Result.failure(
                    IllegalStateException(
                        "Gate ${gate.code} sign-off is blocked by an active human veto."
                    )
                )
            }

            // PREPARE complete proposed snapshot BEFORE calling authority commit operation
            val now = System.currentTimeMillis()
            val updatedRecord = immutableGateRecord(
                GateVerificationRecord(
                    gate = gate,
                    status = GateStatus.APPROVED,
                    criteriaResults = existingGateRecord.criteriaResults,
                    humanDecision = signOff,
                    evaluatedTimestamp = now
                )
            )
            val updatedGateRecords = LinkedHashMap(currentSnapshot.project.gateRecords).apply {
                put(gate, updatedRecord)
            }
            val proposedProject = immutableProject(
                currentSnapshot.project.copy(
                    gateRecords = updatedGateRecords,
                    lastModifiedTimestamp = now
                )
            )
            val proposedPipeline = derivePipelineState(
                project = proposedProject,
                completedStages = currentSnapshot.pipeline.completedStages,
                activeGate = gate,
                statusMessage = "Gate ${gate.code} approved by ${signOff.signatoryId}."
            )
            val proposedSnapshot = GovernorSnapshot(
                project = proposedProject,
                pipeline = proposedPipeline
            )

            // Coordinate with Authority (Governor -> Authority -> Ledger lock order)
            val authResult = humanDecisionAuthority.authorizeGatePassage(
                gate = gate,
                actorId = signOff.signatoryId,
                expectedRole = signOff.signatoryRole,
                remarks = signOff.remarks
            ) {
                commitAuthoritativeSnapshot(proposedSnapshot)
            }

            if (authResult.isFailure) {
                return@withLock Result.failure(
                    authResult.exceptionOrNull()
                        ?: IllegalStateException("Gate sign-off authorization failed")
                )
            }

            Result.success(updatedRecord)
        }
    }

    override suspend fun rejectHumanGatePassage(
        gate: GateLevel,
        officerId: String,
        reason: String
    ): Result<GateVerificationRecord> {
        return governorMutex.withLock {
            if (gate == GateLevel.G5) {
                return@withLock Result.failure(
                    IllegalStateException("G5 rejection is disabled at Baseline 02.")
                )
            }
            if (reason.isBlank()) {
                return@withLock Result.failure(IllegalArgumentException("Gate rejection reason cannot be blank."))
            }

            val stage = mapGateToStage(gate)
            val currentSnapshot = _governorSnapshot.value
            if (currentSnapshot.project.currentStage != stage) {
                return@withLock Result.failure(
                    IllegalStateException("Cannot reject ${gate.code} while project is not at ${stage.displayName}.")
                )
            }
            val existing = currentSnapshot.project.gateRecords[gate]
            if (existing?.status != GateStatus.HUMAN_REVIEW_REQUIRED || existing.criteriaResults.isEmpty()) {
                return@withLock Result.failure(
                    IllegalStateException("${gate.code} must have an explicit criteria evaluation before human rejection.")
                )
            }

            val now = System.currentTimeMillis()
            val rejectedRecord = immutableGateRecord(
                existing.copy(
                    status = GateStatus.REJECTED,
                    humanDecision = null,
                    evaluatedTimestamp = now
                )
            )
            val updatedGateRecords = LinkedHashMap(currentSnapshot.project.gateRecords).apply {
                put(gate, rejectedRecord)
            }
            val proposedProject = immutableProject(
                currentSnapshot.project.copy(
                    gateRecords = updatedGateRecords,
                    lastModifiedTimestamp = now
                )
            )
            val proposedPipeline = derivePipelineState(
                project = proposedProject,
                completedStages = currentSnapshot.pipeline.completedStages,
                activeGate = gate,
                statusMessage = "Gate ${gate.code} rejected by $officerId: $reason"
            )
            val proposedSnapshot = GovernorSnapshot(proposedProject, proposedPipeline)

            val rejectionResult = humanDecisionAuthority.rejectGatePassage(
                gate = gate,
                officerId = officerId,
                reason = reason
            ) {
                commitAuthoritativeSnapshot(proposedSnapshot)
            }
            if (rejectionResult.isFailure) {
                return@withLock Result.failure(
                    rejectionResult.exceptionOrNull() ?: IllegalStateException("Gate rejection failed")
                )
            }
            Result.success(rejectedRecord)
        }
    }

    override suspend fun openNewReviewCycle(
        stage: PipelineStage,
        userId: String,
        resolutionNotes: String
    ): Result<Unit> {
        return governorMutex.withLock {
            if (stage == PipelineStage.GATE_5_RELEASE_LOCK || stage == PipelineStage.MASTER_PLAYER) {
                return@withLock Result.failure(
                    IllegalStateException(
                        "Stage ${stage.displayName} review cycle is disabled at Baseline 02."
                    )
                )
            }

            val currentSnapshot = _governorSnapshot.value
            val gateForStage = stageToGate(stage)
            val now = System.currentTimeMillis()

            val updatedGateRecords = LinkedHashMap(currentSnapshot.project.gateRecords).apply {
                if (gateForStage != null) {
                    put(
                        gateForStage,
                        immutableGateRecord(
                            GateVerificationRecord(
                                gate = gateForStage,
                                status = GateStatus.PENDING_EVALUATION,
                                criteriaResults = emptyList(),
                                humanDecision = null,
                                evaluatedTimestamp = null
                            )
                        )
                    )
                }
            }

            val proposedProject = immutableProject(
                currentSnapshot.project.copy(
                    gateRecords = updatedGateRecords,
                    lastModifiedTimestamp = now
                )
            )
            val proposedPipeline = derivePipelineState(
                project = proposedProject,
                completedStages = currentSnapshot.pipeline.completedStages,
                activeGate = currentSnapshot.pipeline.activeGate,
                statusMessage = "New review cycle opened for ${stage.displayName} by $userId: $resolutionNotes"
            )
            val proposedSnapshot = GovernorSnapshot(
                project = proposedProject,
                pipeline = proposedPipeline
            )

            humanDecisionAuthority.openNewReviewCycle(
                stage = stage,
                userId = userId,
                resolutionNotes = resolutionNotes
            ) {
                commitAuthoritativeSnapshot(proposedSnapshot)
            }
        }
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

    private fun validateSequentialTransition(
        currentStage: PipelineStage,
        targetStage: PipelineStage
    ): Result<Unit> {
        if (targetStage == currentStage) {
            return Result.failure(
                IllegalStateException("Self-transition is prohibited.")
            )
        }

        if (targetStage.ordinal < currentStage.ordinal) {
            return Result.failure(
                IllegalStateException(
                    "Invalid stage transition: Backward transition from ${currentStage.displayName} to ${targetStage.displayName} is prohibited."
                )
            )
        }

        val expectedNextOrdinal = currentStage.ordinal + 1

        if (targetStage.ordinal != expectedNextOrdinal) {
            return Result.failure(
                IllegalStateException(
                    "Invalid stage transition: $currentStage -> $targetStage. " +
                        "Mandatory intermediate stages cannot be skipped."
                )
            )
        }

        return Result.success(Unit)
    }

    private fun commitAuthoritativeSnapshot(snapshot: GovernorSnapshot) {
        // Single authoritative publication:
        _governorSnapshot.value = snapshot

        // Compatibility projections only:
        _currentProjectState.value = snapshot.project
        _pipelineState.value = snapshot.pipeline
    }

    private fun immutableGateRecord(
        record: GateVerificationRecord
    ): GateVerificationRecord =
        record.copy(
            criteriaResults = Collections.unmodifiableList(
                ArrayList(record.criteriaResults)
            )
        )

    private fun immutableProject(
        project: ProjectAggregate
    ): ProjectAggregate {
        val frozenGateRecords =
            project.gateRecords.mapValues { (_, record) ->
                immutableGateRecord(record)
            }

        return project.copy(
            gateRecords = Collections.unmodifiableMap(
                LinkedHashMap(frozenGateRecords)
            )
        )
    }

    private fun derivePipelineState(
        project: ProjectAggregate,
        completedStages: Set<PipelineStage>,
        activeGate: GateLevel?,
        statusMessage: String
    ): PipelineState = PipelineState(
        currentStage = project.currentStage,
        completedStages = Collections.unmodifiableSet(
            LinkedHashSet(completedStages)
        ),
        activeGate = activeGate,
        gateStatuses = Collections.unmodifiableMap(
            LinkedHashMap(project.gateRecords.mapValues { it.value.status })
        ),
        isReleaseLocked = false,
        humanDecisionRequired = project.currentStage.requiresHumanDecision,
        statusMessage = statusMessage
    )

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
}
