package com.example.elyzareth.core.governor

import com.example.elyzareth.core.dataflow.ActorIdentity
import com.example.elyzareth.core.dataflow.ElyzarethHumanDecisionAuthority
import com.example.elyzareth.core.dataflow.HumanAuthorizationPolicy
import com.example.elyzareth.core.ledger.EvidenceLedger
import com.example.elyzareth.core.ledger.InMemoryEvidenceLedger
import com.example.elyzareth.core.model.ActorRole
import com.example.elyzareth.core.model.ActorType
import com.example.elyzareth.core.model.EvidenceClass
import com.example.elyzareth.core.model.EvidenceEventType
import com.example.elyzareth.core.model.EvidenceRecord
import com.example.elyzareth.core.model.GENESIS_RECORD_HASH
import com.example.elyzareth.core.model.GateLevel
import com.example.elyzareth.core.model.GateCriterionCheck
import com.example.elyzareth.core.model.GateStatus
import com.example.elyzareth.core.model.HumanSignOff
import com.example.elyzareth.core.model.PipelineStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.charset.CharacterCodingException

/**
 * Phase 0 (BASELINE_02_SEALED) acceptance unit test suite for Elyzareth OS.
 *
 * Covers:
 * 1. Evidence Ledger canonical serialization, SHA-256 vectors, strict UTF-16 validation,
 *    pre-derivation external hash validation, and concurrent writes.
 * 2. Fail-closed Human Decision Authority actor registry and configurable role policy.
 * 3. Governor sequential transitions, gate prerequisites, single authoritative GovernorSnapshot,
 *    deep immutability, evidence-first failure rollback, and NonCancellable commit semantics.
 */
class GovernorEvidenceAuthorityTest {

    private val testProjectId = "test_track_001"
    private lateinit var evidenceLedger: InMemoryEvidenceLedger
    private lateinit var humanAuthority: ElyzarethHumanDecisionAuthority
    private lateinit var governor: ElyzarethGovernorEngine

    private val defaultTestActors: Map<String, ActorIdentity> = mapOf(
        "curator_alice" to ActorIdentity("curator_alice", ActorType.HUMAN, ActorRole.HUMAN_CURATOR),
        "creative_lead_bob" to ActorIdentity("creative_lead_bob", ActorType.HUMAN, ActorRole.HUMAN_CREATOR),
        "director_bob" to ActorIdentity("director_bob", ActorType.HUMAN, ActorRole.HUMAN_CREATOR),
        "executive_carol" to ActorIdentity("executive_carol", ActorType.HUMAN, ActorRole.HUMAN_CREATOR),
        "auditor_dave" to ActorIdentity("auditor_dave", ActorType.HUMAN, ActorRole.HUMAN_AUDITOR),
        "officer_elena" to ActorIdentity("officer_elena", ActorType.HUMAN, ActorRole.HUMAN_CREATOR),
        "user_1" to ActorIdentity("user_1", ActorType.HUMAN, ActorRole.HUMAN_CREATOR),
        "chief_officer" to ActorIdentity("chief_officer", ActorType.HUMAN, ActorRole.HUMAN_CREATOR),
        "curator_frank" to ActorIdentity("curator_frank", ActorType.HUMAN, ActorRole.HUMAN_CURATOR)
    )

    @Before
    fun setup() {
        evidenceLedger = InMemoryEvidenceLedger()
        humanAuthority = ElyzarethHumanDecisionAuthority(
            projectId = testProjectId,
            evidenceLedger = evidenceLedger,
            registeredActors = defaultTestActors,
            authorizationPolicy = HumanAuthorizationPolicy.proposedBaselinePolicy()
        )
        governor = ElyzarethGovernorEngine(
            projectId = testProjectId,
            projectTitle = "Baseline 02 Verification Track",
            humanDecisionAuthority = humanAuthority,
            evidenceLedger = evidenceLedger,
            initialStage = PipelineStage.IDEA
        )
    }

    private fun passingGateCriteria(id: String = "human-reviewed-criteria"): List<GateCriterionCheck> =
        listOf(
            GateCriterionCheck(
                id = id,
                label = "Required evidence reviewed",
                satisfied = true,
                explanation = "Explicit test fixture result; production criteria remain a separate implementation responsibility."
            )
        )

    private suspend fun advanceToGate1Stage(
        gov: ElyzarethGovernorEngine = governor,
        auth: ElyzarethHumanDecisionAuthority = humanAuthority
    ) {
        auth.approveStageTransition(PipelineStage.IDEA, "user_1", "Approve Idea")
        assertTrue(gov.transitionToStage(PipelineStage.LYRIC, "To Lyric").isSuccess)
        assertTrue(gov.transitionToStage(PipelineStage.EVIDENCE, "To Evidence").isSuccess)
        assertTrue(gov.transitionToStage(PipelineStage.CURATION, "To Curation").isSuccess)
        auth.approveStageTransition(PipelineStage.CURATION, "curator_alice", "Approve Curation")
        assertTrue(gov.transitionToStage(PipelineStage.CREATIVE_DNA, "To Creative DNA").isSuccess)
        auth.approveStageTransition(PipelineStage.CREATIVE_DNA, "user_1", "Approve DNA")
        assertTrue(gov.transitionToStage(PipelineStage.STYLE_AND_VISUAL, "To Style & Visual").isSuccess)
        auth.approveStageTransition(PipelineStage.STYLE_AND_VISUAL, "user_1", "Approve Style")
        assertTrue(gov.transitionToStage(PipelineStage.PRODUCTION, "To Production").isSuccess)
        assertTrue(gov.transitionToStage(PipelineStage.FORENSIC_VERIFICATION, "To Forensic").isSuccess)
        assertTrue(gov.transitionToStage(PipelineStage.GATE_1_PRE_PRODUCTION, "To Gate 1").isSuccess)
    }

    @Test
    fun test1_validStageTransition_succeeds() = runTest {
        val approvalResult = humanAuthority.approveStageTransition(
            stage = PipelineStage.IDEA,
            userId = "curator_alice",
            notes = "Theme and seed concept approved for drafting."
        )
        assertTrue("Human approval should succeed", approvalResult.isSuccess)

        val transitionResult = governor.transitionToStage(
            targetStage = PipelineStage.LYRIC,
            rationale = "Proceeding to initial lyrical drafting."
        )

        assertTrue("Valid transition should succeed", transitionResult.isSuccess)
        assertEquals(PipelineStage.LYRIC, governor.governorSnapshot.value.pipeline.currentStage)
        assertEquals(PipelineStage.LYRIC, governor.governorSnapshot.value.project.currentStage)
        assertTrue(governor.governorSnapshot.value.pipeline.completedStages.contains(PipelineStage.IDEA))
    }

    @Test
    fun test2_invalidStageTransition_fails() = runTest {
        val skipResult = governor.transitionToStage(
            targetStage = PipelineStage.PRODUCTION,
            rationale = "Skipping ahead to production."
        )
        assertTrue("Skipping prerequisite stages must fail", skipResult.isFailure)
        assertEquals(PipelineStage.IDEA, governor.governorSnapshot.value.pipeline.currentStage)

        humanAuthority.approveStageTransition(PipelineStage.IDEA, "curator_alice", "OK")
        governor.transitionToStage(PipelineStage.LYRIC, "To lyric")

        val backwardResult = governor.transitionToStage(
            targetStage = PipelineStage.IDEA,
            rationale = "Attempting backward jump"
        )
        assertTrue("Backward transition must fail", backwardResult.isFailure)
    }

    @Test
    fun test3_humanRequiredTransition_cannotBypassHumanAuthorization() = runTest {
        val transitionResult = governor.transitionToStage(
            targetStage = PipelineStage.LYRIC,
            rationale = "Bypassing human approval"
        )

        assertTrue("Transition requiring human approval must fail if not authorized", transitionResult.isFailure)
        val error = transitionResult.exceptionOrNull()?.message ?: ""
        assertTrue("Error must state human authorization is required", error.contains("Human authorization required"))
        assertEquals(PipelineStage.IDEA, governor.governorSnapshot.value.pipeline.currentStage)
    }

    @Test
    fun test4_humanApproval_allowsAppropriateTransition() = runTest {
        val approval = humanAuthority.approveStageTransition(
            stage = PipelineStage.IDEA,
            userId = "creative_lead_bob",
            notes = "Artistic direction validated."
        )
        assertTrue(approval.isSuccess)
        assertTrue(humanAuthority.isStageApproved(PipelineStage.IDEA))

        val transition1 = governor.transitionToStage(PipelineStage.LYRIC, "Starting lyrics")
        assertTrue(transition1.isSuccess)
        assertEquals(PipelineStage.LYRIC, governor.governorSnapshot.value.pipeline.currentStage)

        val transition2 = governor.transitionToStage(PipelineStage.EVIDENCE, "Logging generation metadata")
        assertTrue(transition2.isSuccess)
        assertEquals(PipelineStage.EVIDENCE, governor.governorSnapshot.value.pipeline.currentStage)
    }

    @Test
    fun test5_humanVeto_blocksTransition() = runTest {
        humanAuthority.approveStageTransition(
            stage = PipelineStage.IDEA,
            userId = "director_bob",
            notes = "Initial green light"
        )
        assertTrue(humanAuthority.isStageApproved(PipelineStage.IDEA))

        val vetoResult = humanAuthority.registerHumanVeto(
            stage = PipelineStage.IDEA,
            userId = "executive_carol",
            reason = "Sensitive themes conflict with artist DNA policy."
        )
        assertTrue(vetoResult.isSuccess)
        assertTrue(humanAuthority.hasActiveVeto(PipelineStage.IDEA))
        assertFalse("Veto must invalidate previous approval", humanAuthority.isStageApproved(PipelineStage.IDEA))

        val transitionResult = governor.transitionToStage(
            targetStage = PipelineStage.LYRIC,
            rationale = "Attempting to push past veto"
        )
        assertTrue("Active veto must block transition", transitionResult.isFailure)
        val errorMessage = transitionResult.exceptionOrNull()?.message ?: ""
        assertTrue("Error should mention veto", errorMessage.contains("veto"))
        assertEquals(PipelineStage.IDEA, governor.governorSnapshot.value.pipeline.currentStage)

        val overrideAttempt = humanAuthority.approveStageTransition(
            stage = PipelineStage.IDEA,
            userId = "director_bob",
            notes = "Trying to clear veto without human resolution"
        )
        assertTrue("Attempting to approve a vetoed stage must fail", overrideAttempt.isFailure)
    }

    @Test
    fun test6_humanVeto_producesEvidenceRecord() = runTest {
        val initialSize = evidenceLedger.size()

        val vetoResult = humanAuthority.registerHumanVeto(
            stage = PipelineStage.CURATION,
            userId = "auditor_dave",
            reason = "Unauthorized stanza detected in lyric draft."
        )
        assertTrue(vetoResult.isSuccess)

        val records = evidenceLedger.getRecordsForStage(testProjectId, PipelineStage.CURATION)
        assertEquals(initialSize + 1, records.size)

        val vetoEvidence = records.last()
        assertEquals(EvidenceEventType.HUMAN_VETOED, vetoEvidence.eventType)
        assertEquals("auditor_dave", vetoEvidence.authorOrActor)
        assertEquals(testProjectId, vetoEvidence.projectId)
        assertEquals(PipelineStage.CURATION, vetoEvidence.stage)
        assertEquals("HUMAN_VETO", vetoEvidence.metadata["action"])
        assertTrue(vetoEvidence.summary.contains("Unauthorized stanza detected"))
        assertTrue("Timestamp must be valid", vetoEvidence.timestampMillis > 0L)
    }

    @Test
    fun test7_humanApproval_producesEvidenceRecord() = runTest {
        val initialSize = evidenceLedger.size()

        val approvalResult = humanAuthority.approveStageTransition(
            stage = PipelineStage.IDEA,
            userId = "officer_elena",
            notes = "Concept aligns perfectly with release goals."
        )
        assertTrue(approvalResult.isSuccess)

        val records = evidenceLedger.getRecordsForStage(testProjectId, PipelineStage.IDEA)
        assertEquals(initialSize + 1, records.size)

        val approvalEvidence = records.last()
        assertEquals(EvidenceEventType.HUMAN_APPROVED, approvalEvidence.eventType)
        assertEquals("officer_elena", approvalEvidence.authorOrActor)
        assertEquals(testProjectId, approvalEvidence.projectId)
        assertEquals(PipelineStage.IDEA, approvalEvidence.stage)
        assertEquals("HUMAN_APPROVAL", approvalEvidence.metadata["action"])
        assertTrue(approvalEvidence.summary.contains("Concept aligns perfectly"))
    }

    @Test
    fun test8_evidenceRecords_containCorrectProjectAndStage() = runTest {
        humanAuthority.approveStageTransition(PipelineStage.IDEA, "user_1", "Approved")
        governor.transitionToStage(PipelineStage.LYRIC, "To Lyric")

        val allRecords = evidenceLedger.recordsFlow.value
        assertEquals(2, allRecords.size)

        val record1 = allRecords[0]
        assertEquals(testProjectId, record1.projectId)
        assertEquals(PipelineStage.IDEA, record1.stage)
        assertEquals(EvidenceEventType.HUMAN_APPROVED, record1.eventType)
        assertTrue(record1.id.isNotBlank())
        assertTrue(record1.payloadHash.isNotBlank())

        val record2 = allRecords[1]
        assertEquals(testProjectId, record2.projectId)
        assertEquals(PipelineStage.LYRIC, record2.stage)
        assertEquals(EvidenceEventType.STAGE_TRANSITIONED, record2.eventType)
        assertTrue(record2.id.isNotBlank())
        assertTrue(record2.payloadHash.isNotBlank())
    }

    @Test
    fun test9_automatedCode_cannotCreateFalseHumanApprovalEvidence() = runTest {
        val blankResult = humanAuthority.approveStageTransition(
            stage = PipelineStage.IDEA,
            userId = "",
            notes = "Empty actor"
        )
        assertTrue("Blank actor must fail", blankResult.isFailure)

        val systemResult = humanAuthority.approveStageTransition(
            stage = PipelineStage.IDEA,
            userId = "SYSTEM",
            notes = "Automated bypass attempt"
        )
        assertTrue("SYSTEM actor must fail", systemResult.isFailure)

        val botResult = humanAuthority.approveStageTransition(
            stage = PipelineStage.IDEA,
            userId = "BOT_AUTO_APPROVER",
            notes = "Synthetic approval attempt"
        )
        assertTrue("BOT actor must fail", botResult.isFailure)

        val records = evidenceLedger.recordsFlow.value
        assertTrue("No false evidence records may exist", records.isEmpty())
        assertFalse("Stage must not be approved", humanAuthority.isStageApproved(PipelineStage.IDEA))
    }

    @Test
    fun test10_g5ReleaseLock_remainsUnavailable() = runTest {
        val lockResult = governor.executeGate5ReleaseLock("mock_signature")
        assertTrue("Gate 5 release lock must fail", lockResult.isFailure)
        val lockError = lockResult.exceptionOrNull()?.message ?: ""
        assertTrue(lockError.contains("locked and non-operational"))

        assertFalse("Master Player must be locked", governor.isMasterPlayerUnlocked())

        val evalResult = governor.evaluateGate(GateLevel.G5)
        assertTrue("Evaluating Gate 5 must fail", evalResult.isFailure)

        val signOff = HumanSignOff(
            signatoryId = "chief_officer",
            signatoryRole = "Executive",
            signedAtTimestamp = System.currentTimeMillis(),
            remarks = "Attempting G5 seal"
        )
        val signOffResult = governor.submitHumanGateSignOff(GateLevel.G5, signOff)
        assertTrue("Signing off Gate 5 must fail", signOffResult.isFailure)

        val stageTransitionResult = governor.transitionToStage(PipelineStage.GATE_5_RELEASE_LOCK, "Advance to G5")
        assertTrue("Transitioning to G5 stage must fail", stageTransitionResult.isFailure)
    }

    @Test
    fun test11_humanRejection_producesEvidenceAndRecordsState() = runTest {
        val rejectionResult = humanAuthority.rejectStageTransition(
            stage = PipelineStage.IDEA,
            userId = "curator_frank",
            reason = "Theme lacks musicality and coherence."
        )
        assertTrue(rejectionResult.isSuccess)
        assertFalse(humanAuthority.isStageApproved(PipelineStage.IDEA))

        val records = evidenceLedger.getRecordsForStage(testProjectId, PipelineStage.IDEA)
        assertEquals(1, records.size)
        val rejectionEvidence = records.first()
        assertEquals(EvidenceEventType.HUMAN_REJECTED, rejectionEvidence.eventType)
        assertEquals("curator_frank", rejectionEvidence.authorOrActor)
        assertEquals("HUMAN_REJECTION", rejectionEvidence.metadata["action"])

        assertTrue("Direct approval after rejection must fail", humanAuthority.approveStageTransition(
            PipelineStage.IDEA, "user_1", "Attempt to bypass rejection"
        ).isFailure)
        assertTrue(governor.openNewReviewCycle(
            PipelineStage.IDEA, "user_1", "The rejected idea has been revised for a new review."
        ).isSuccess)
        assertTrue("Approval is allowed only after the new review cycle", humanAuthority.approveStageTransition(
            PipelineStage.IDEA, "user_1", "Re-approved after review"
        ).isSuccess)
    }

    @Test
    fun test12_governor_maintainsCoherentPipelineState() = runTest {
        humanAuthority.approveStageTransition(PipelineStage.IDEA, "user_1", "OK")
        governor.transitionToStage(PipelineStage.LYRIC, "To Lyric")
        governor.transitionToStage(PipelineStage.EVIDENCE, "To Evidence")

        val snapshot = governor.governorSnapshot.value
        val state = snapshot.pipeline
        assertEquals(PipelineStage.EVIDENCE, state.currentStage)
        assertEquals(setOf(PipelineStage.IDEA, PipelineStage.LYRIC), state.completedStages)
        assertFalse(state.isReleaseLocked)
        assertFalse(state.humanDecisionRequired)

        val projectState = snapshot.project
        assertNotNull(projectState)
        assertEquals(PipelineStage.EVIDENCE, projectState.currentStage)
    }

    @Test
    fun test13_selfTransition_and_stageSkipping_rejectedWithZeroEvidence() = runTest {
        humanAuthority.approveStageTransition(PipelineStage.IDEA, "user_1", "Approve Idea")
        val baselineRecordsCount = evidenceLedger.size()
        val baselineSnapshot = governor.governorSnapshot.value

        // 1. Self-transition (IDEA -> IDEA) rejected
        val selfTransition = governor.transitionToStage(PipelineStage.IDEA, "Self transition")
        assertTrue("Self-transition must be rejected", selfTransition.isFailure)
        assertEquals(baselineRecordsCount, evidenceLedger.size())
        assertSame(baselineSnapshot, governor.governorSnapshot.value)

        // 2. Direct IDEA -> GATE_2_CURATION_APPROVAL jump rejected
        val gateJump = governor.transitionToStage(PipelineStage.GATE_2_CURATION_APPROVAL, "Jump to Gate 2")
        assertTrue("Direct IDEA -> GATE_2 jump must be rejected", gateJump.isFailure)
        assertEquals(baselineRecordsCount, evidenceLedger.size())
        assertSame(baselineSnapshot, governor.governorSnapshot.value)

        // 3. Direct IDEA -> PRODUCTION jump rejected
        val prodJump = governor.transitionToStage(PipelineStage.PRODUCTION, "Jump to Production")
        assertTrue("Direct IDEA -> PRODUCTION jump must be rejected", prodJump.isFailure)
        assertEquals(baselineRecordsCount, evidenceLedger.size())
        assertSame(baselineSnapshot, governor.governorSnapshot.value)
    }

    @Test
    fun test14_evidenceWriteFailure_abortsGovernorAndAuthorityMutations_and_preservesSnapshot() = runTest {
        var allowWrites = true
        val delegateLedger = InMemoryEvidenceLedger()
        val controllableLedger = object : EvidenceLedger by delegateLedger {
            override suspend fun record(evidence: EvidenceRecord): Result<EvidenceRecord> {
                if (!allowWrites) {
                    return Result.failure(IllegalStateException("Simulated ledger write failure"))
                }
                return delegateLedger.record(evidence)
            }
        }

        val auth = ElyzarethHumanDecisionAuthority(
            projectId = testProjectId,
            evidenceLedger = controllableLedger,
            registeredActors = defaultTestActors,
            authorizationPolicy = HumanAuthorizationPolicy.proposedBaselinePolicy()
        )
        val gov = ElyzarethGovernorEngine(
            projectId = testProjectId,
            humanDecisionAuthority = auth,
            evidenceLedger = controllableLedger
        )

        // 1. When evidence write succeeds, one authoritative snapshot is published
        assertTrue(auth.approveStageTransition(PipelineStage.IDEA, "user_1", "Approved").isSuccess)
        assertTrue(gov.transitionToStage(PipelineStage.LYRIC, "To Lyric").isSuccess)
        val committedSnapshot = gov.governorSnapshot.value
        assertEquals(PipelineStage.LYRIC, committedSnapshot.project.currentStage)
        assertEquals(PipelineStage.LYRIC, committedSnapshot.pipeline.currentStage)

        // 2. When evidence write fails, Governor and Authority mutations abort without changing state
        allowWrites = false
        val failedTransition = gov.transitionToStage(PipelineStage.EVIDENCE, "Should fail on ledger write")
        assertTrue(failedTransition.isFailure)
        assertSame("Snapshot reference must remain unchanged on evidence failure", committedSnapshot, gov.governorSnapshot.value)

        val failedApproval = auth.approveStageTransition(PipelineStage.CURATION, "curator_alice", "Should fail")
        assertTrue(failedApproval.isFailure)
        assertFalse(auth.isStageApproved(PipelineStage.CURATION))

        val failedVeto = auth.registerHumanVeto(PipelineStage.LYRIC, "executive_carol", "Should fail")
        assertTrue(failedVeto.isFailure)
        assertFalse(auth.hasActiveVeto(PipelineStage.LYRIC))

        val failedRejection = auth.rejectStageTransition(PipelineStage.CURATION, "curator_alice", "Should fail")
        assertTrue(failedRejection.isFailure)
    }

    @Test
    fun test15_gateLifecycle_enforcesStagePrerequisite_sequence_and_blocksAdvanceWhenGateNotApproved() = runTest {
        // Evaluating G1 while at IDEA must fail
        assertTrue(governor.evaluateGate(GateLevel.G1, passingGateCriteria("g1-before-stage")).isFailure)

        // Advance sequentially to GATE_1_PRE_PRODUCTION
        advanceToGate1Stage()
        assertEquals(PipelineStage.GATE_1_PRE_PRODUCTION, governor.governorSnapshot.value.project.currentStage)

        val signOffG1 = HumanSignOff(
            signatoryId = "user_1",
            signatoryRole = "HUMAN_CREATOR",
            signedAtTimestamp = 1700000000000L,
            remarks = "G1 verified and approved"
        )

        // 1. Sign-off on G1 before evaluateGate(G1) must be rejected
        val prematureSignOff = governor.submitHumanGateSignOff(GateLevel.G1, signOffG1)
        assertTrue("Gate sign-off before evaluation must fail", prematureSignOff.isFailure)

        // 2. G1 not approved: advancing to GATE_2_CURATION_APPROVAL must be rejectedeven if stage approval is called
        humanAuthority.approveStageTransition(PipelineStage.GATE_1_PRE_PRODUCTION, "user_1", "Stage approved but gate not approved")
        val advanceWithoutGateApproval = governor.transitionToStage(
            PipelineStage.GATE_2_CURATION_APPROVAL,
            "Trying to leave G1 without gate approval"
        )
        assertTrue("Cannot advance past G1 stage unless G1 is APPROVED", advanceWithoutGateApproval.isFailure)

        // Evaluating G2 before G1 is approved must also fail
        assertTrue(governor.evaluateGate(GateLevel.G2, passingGateCriteria("g2-before-g1")).isFailure)

        // 3. Evaluate G1 -> HUMAN_REVIEW_REQUIRED, then sign off -> APPROVED, then advance to G2
        val evalG1 = governor.evaluateGate(GateLevel.G1, passingGateCriteria("g1-criteria"))
        assertTrue(evalG1.isSuccess)
        assertEquals(GateStatus.HUMAN_REVIEW_REQUIRED, governor.governorSnapshot.value.pipeline.gateStatuses[GateLevel.G1])

        val approvedG1 = governor.submitHumanGateSignOff(GateLevel.G1, signOffG1)
        assertTrue(approvedG1.isSuccess)
        assertEquals(GateStatus.APPROVED, governor.governorSnapshot.value.project.gateRecords[GateLevel.G1]?.status)
        assertEquals(GateStatus.APPROVED, governor.governorSnapshot.value.pipeline.gateStatuses[GateLevel.G1])

        val advanceToG2 = governor.transitionToStage(PipelineStage.GATE_2_CURATION_APPROVAL, "G1 approved, advancing to G2")
        assertTrue("Transition to G2 must succeed after G1 approval", advanceToG2.isSuccess)
        assertEquals(PipelineStage.GATE_2_CURATION_APPROVAL, governor.governorSnapshot.value.project.currentStage)

        // 4. G5 remains inaccessible and isReleaseLocked remains false
        assertTrue(governor.evaluateGate(GateLevel.G5).isFailure)
        assertFalse(governor.isMasterPlayerUnlocked())
        assertFalse(governor.governorSnapshot.value.pipeline.isReleaseLocked)
    }

    @Test
    fun test16_evidenceLedger_validatesExternalHashes_preservesSnapshotOnRejection_and_handlesConcurrentWrites() = runTest {
        val validPayloadHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        val firstRecord = EvidenceRecord(
            id = "rec_1",
            projectId = testProjectId,
            stage = PipelineStage.IDEA,
            timestampMillis = 1700000000001L,
            authorOrActor = "user_1",
            actorId = "user_1",
            actorType = ActorType.HUMAN,
            evidenceClass = EvidenceClass.AUTHORITY,
            eventType = EvidenceEventType.HUMAN_APPROVED,
            payloadHash = validPayloadHash,
            summary = "First record"
        )
        val firstResult = evidenceLedger.record(firstRecord)
        assertTrue(firstResult.isSuccess)
        val committedFirst = firstResult.getOrThrow()
        assertEquals(GENESIS_RECORD_HASH, committedFirst.previousHash)
        assertTrue(evidenceLedger.verifyChainIntegrity())

        val snapshotAfterFirst = evidenceLedger.recordsFlow.value

        // 1. Failed external previousHash -> Rejected & no snapshot publication (same reference)
        val badPrevHashRecord = firstRecord.copy(
            id = "rec_bad_prev",
            previousHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        )
        val badPrevResult = evidenceLedger.record(badPrevHashRecord)
        assertTrue("Mismatched external previousHash must be rejected", badPrevResult.isFailure)
        assertSame("Snapshot reference must not change on rejection", snapshotAfterFirst, evidenceLedger.recordsFlow.value)
        assertEquals(1, evidenceLedger.size())

        // 2. Failed external recordHash -> Rejected & no snapshot publication (same reference)
        val badRecordHashRecord = firstRecord.copy(
            id = "rec_bad_rechash",
            previousHash = committedFirst.recordHash,
            recordHash = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        )
        val badRecordHashResult = evidenceLedger.record(badRecordHashRecord)
        assertTrue("Mismatched external recordHash must be rejected", badRecordHashResult.isFailure)
        assertSame("Snapshot reference must not change on rejection", snapshotAfterFirst, evidenceLedger.recordsFlow.value)

        // 3. Blank non-null signature -> Rejected
        val blankSignatureRecord = firstRecord.copy(
            id = "rec_blank_sig",
            signature = "   "
        )
        val blankSigResult = evidenceLedger.record(blankSignatureRecord)
        assertTrue("Blank non-null signature must be rejected", blankSigResult.isFailure)
        assertSame(snapshotAfterFirst, evidenceLedger.recordsFlow.value)

        // 4. Concurrent duplicate IDs -> Exactly one append succeeds
        val duplicateCandidates = (1..8).map { index ->
            async(start = CoroutineStart.LAZY) {
                evidenceLedger.record(
                    firstRecord.copy(
                        id = "rec_dup_concurrent",
                        summary = "Concurrent attempt $index"
                    )
                )
            }
        }
        val dupResults = duplicateCandidates.awaitAll()
        assertEquals("Exactly one concurrent duplicate ID append may succeed", 1, dupResults.count { it.isSuccess })
        assertEquals(7, dupResults.count { it.isFailure })

        // 5. Concurrent ledger writes with unique IDs -> All appear once, hash chain valid
        val concurrentWrites = (1..12).map { index ->
            async(start = CoroutineStart.LAZY) {
                evidenceLedger.record(
                    firstRecord.copy(
                        id = "rec_concurrent_$index",
                        timestampMillis = 1700000001000L + index,
                        summary = "Concurrent write $index"
                    )
                )
            }
        }
        val concurrentResults = concurrentWrites.awaitAll()
        assertEquals(12, concurrentResults.count { it.isSuccess })
        assertEquals(1 + 1 + 12, evidenceLedger.size())
        assertTrue("Hash chain must remain valid across concurrent writes", evidenceLedger.verifyChainIntegrity())
    }

    @Test
    fun test17_canonicalSerialization_matchesExactSha256Vectors_rejectsMalformedUtf16_and_enforcesDeepImmutability() = runTest {
        val basePayloadHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val vectorA = EvidenceRecord(
            id = "evt_vec_01",
            projectId = "proj_01",
            stage = PipelineStage.IDEA,
            timestampMillis = 1700000000000L,
            authorOrActor = "creator_alice",
            actorId = "creator_alice",
            actorType = ActorType.HUMAN,
            evidenceClass = EvidenceClass.AUTHORITY,
            eventType = EvidenceEventType.HUMAN_APPROVED,
            artifactId = null,
            parentEventId = null,
            payloadHash = basePayloadHash,
            previousHash = GENESIS_RECORD_HASH,
            summary = "Approved",
            metadata = linkedMapOf("a" to "1", "b" to "2"),
            signature = null
        )

        val vectorB = vectorA.copy(artifactId = "")
        val vectorC = vectorA.copy(metadata = linkedMapOf("b" to "2", "a" to "1"))

        // 1. Exact Rev. 7 SHA-256 test vectors A, B, and C
        assertEquals(
            "4fa771f1959e7e796430aab5cc1c2e9b18965e18776ff7ba98721b865ee8d553",
            vectorA.computeRecordHash()
        )
        assertEquals(
            "23ee9376356cc87c70414325f4c01b965c9758eb1538fc2897811b8b9bee6383",
            vectorB.computeRecordHash()
        )
        assertEquals(
            "4fa771f1959e7e796430aab5cc1c2e9b18965e18776ff7ba98721b865ee8d553",
            vectorC.computeRecordHash()
        )
        assertNotEquals(vectorA.computeRecordHash(), vectorB.computeRecordHash())

        // 2. Multibyte UTF-8 byte lengths (must use UTF-8 byte count, not UTF-16 String.length)
        val multibyteRecord = vectorA.copy(id = "evt_utf8", summary = "🎵é")
        // "🎵" is 4 UTF-8 bytes (2 UTF-16 chars), "é" is 2 UTF-8 bytes (1 UTF-16 char) => total 6 UTF-8 bytes
        assertTrue(multibyteRecord.signableEnvelope().contains("summary=VAL:6:🎵é;"))

        // 3. Malformed UTF-16 (unpaired high surrogate) rejected before append
        val malformedRecord = vectorA.copy(id = "evt_malformed", summary = "Unpaired surrogate: \uD800")
        try {
            malformedRecord.computeRecordHash()
            fail("Expected CharacterCodingException on malformed UTF-16")
        } catch (_: CharacterCodingException) {
            // Expected
        }
        val snapshotBeforeMalformed = evidenceLedger.recordsFlow.value
        val malformedAppendResult = evidenceLedger.record(malformedRecord)
        assertTrue("Malformed UTF-16 record must be rejected before append", malformedAppendResult.isFailure)
        assertSame(snapshotBeforeMalformed, evidenceLedger.recordsFlow.value)

        // 4. Deep immutability of committed EvidenceRecord metadata, ledger snapshots, and GovernorSnapshot
        val callerMutableMap = hashMapOf("k1" to "v1")
        val committed = evidenceLedger.record(vectorA.copy(metadata = callerMutableMap)).getOrThrow()
        callerMutableMap["k1"] = "mutated_outside"
        assertEquals("v1", committed.metadata["k1"])

        try {
            (committed.metadata as MutableMap<String, String>)["k2"] = "v2"
            fail("Expected UnsupportedOperationException when mutating committed metadata")
        } catch (_: UnsupportedOperationException) {
            // Expected
        }

        try {
            (evidenceLedger.recordsFlow.value as MutableList<EvidenceRecord>).clear()
            fail("Expected UnsupportedOperationException when mutating ledger snapshot")
        } catch (_: UnsupportedOperationException) {
            // Expected
        }

        val govSnapshot = governor.governorSnapshot.value
        try {
            (govSnapshot.project.gateRecords as MutableMap<GateLevel, *>).clear()
            fail("Expected UnsupportedOperationException when mutating project.gateRecords")
        } catch (_: UnsupportedOperationException) {
            // Expected
        }
        try {
            (govSnapshot.project.gateRecords[GateLevel.G1]!!.criteriaResults as MutableList<*>).clear()
            fail("Expected UnsupportedOperationException when mutating GateVerificationRecord.criteriaResults")
        } catch (_: UnsupportedOperationException) {
            // Expected
        }
        try {
            (govSnapshot.pipeline.completedStages as MutableSet<*>).clear()
            fail("Expected UnsupportedOperationException when mutating pipeline.completedStages")
        } catch (_: UnsupportedOperationException) {
            // Expected
        }
        try {
            (govSnapshot.pipeline.gateStatuses as MutableMap<*, *>).clear()
            fail("Expected UnsupportedOperationException when mutating pipeline.gateStatuses")
        } catch (_: UnsupportedOperationException) {
            // Expected
        }
    }

    @Test
    fun test18_vetoResolution_requiresExplicitNewReviewCycle_beforeReApproval() = runTest {
        assertTrue(humanAuthority.approveStageTransition(PipelineStage.IDEA, "director_bob", "Initial approval").isSuccess)
        assertTrue(humanAuthority.registerHumanVeto(PipelineStage.IDEA, "executive_carol", "Vetoed for revision").isSuccess)
        assertTrue(humanAuthority.hasActiveVeto(PipelineStage.IDEA))

        // Direct re-approval while veto is active must fail
        assertTrue(humanAuthority.approveStageTransition(PipelineStage.IDEA, "director_bob", "Bypass attempt").isFailure)

        // Open explicit new review cycle
        val reviewCycleResult = governor.openNewReviewCycle(
            stage = PipelineStage.IDEA,
            userId = "executive_carol",
            resolutionNotes = "Revised concept addresses DNA concerns; opening new review cycle."
        )
        assertTrue(reviewCycleResult.isSuccess)
        assertFalse(humanAuthority.hasActiveVeto(PipelineStage.IDEA))
        assertFalse("Opening review cycle clears veto but requires fresh approval", humanAuthority.isStageApproved(PipelineStage.IDEA))

        // Fresh approval now succeeds
        assertTrue(humanAuthority.approveStageTransition(PipelineStage.IDEA, "director_bob", "Re-approved in cycle 2").isSuccess)
        assertTrue(humanAuthority.isStageApproved(PipelineStage.IDEA))
        assertTrue(evidenceLedger.verifyChainIntegrity())
    }

    @Test
    fun test19_concurrentTransitions_serializedByGovernorMutex() = runTest {
        humanAuthority.approveStageTransition(PipelineStage.IDEA, "user_1", "Approved")

        val attempts = (1..10).map { index ->
            async(start = CoroutineStart.LAZY) {
                governor.transitionToStage(PipelineStage.LYRIC, "Concurrent transition attempt $index")
            }
        }
        val results = attempts.awaitAll()
        assertEquals("Exactly 1 transition from IDEA to LYRIC may succeed", 1, results.count { it.isSuccess })
        assertEquals("Remaining 9 concurrent transitions must fail deterministically", 9, results.count { it.isFailure })
        assertEquals(PipelineStage.LYRIC, governor.governorSnapshot.value.project.currentStage)
        assertTrue(evidenceLedger.verifyChainIntegrity())
    }

    @Test
    fun test20_declaredActorRegistry_and_unconfiguredPolicy_failClosed() = runTest {
        val registeredActors = mapOf(
            "sylvia_sys" to ActorIdentity(
                actorId = "sylvia_sys",
                actorType = ActorType.HUMAN,
                role = ActorRole.HUMAN_CURATOR
            ),
            "ai_drafter" to ActorIdentity(
                actorId = "ai_drafter",
                actorType = ActorType.AI,
                role = ActorRole.AI_GENERATOR
            ),
            "creator_alice" to ActorIdentity(
                actorId = "creator_alice",
                actorType = ActorType.HUMAN,
                role = ActorRole.HUMAN_CREATOR
            )
        )

        // 1. Unconfigured policy (default HumanAuthorizationPolicy()) is deny-all for stage, gate, and veto/review
        val unconfiguredPolicyAuthority = ElyzarethHumanDecisionAuthority(
            projectId = testProjectId,
            evidenceLedger = evidenceLedger,
            registeredActors = registeredActors,
            authorizationPolicy = HumanAuthorizationPolicy()
        )
        assertTrue(
            "Unconfigured stage policy must reject",
            unconfiguredPolicyAuthority.approveStageTransition(PipelineStage.IDEA, "creator_alice", "Notes").isFailure
        )
        assertTrue(
            "Unconfigured gate policy must reject",
            unconfiguredPolicyAuthority.authorizeGatePassage(GateLevel.G1, "creator_alice", "HUMAN_CREATOR", "Notes") { }.isFailure
        )
        assertTrue(
            "Unconfigured veto/review policy must reject",
            unconfiguredPolicyAuthority.registerHumanVeto(PipelineStage.IDEA, "creator_alice", "Reason").isFailure
        )

        // 2. Empty actor registry must fail closed even with a policy
        val emptyRegistryAuthority = ElyzarethHumanDecisionAuthority(
            projectId = testProjectId,
            evidenceLedger = evidenceLedger,
            registeredActors = emptyMap(),
            authorizationPolicy = HumanAuthorizationPolicy.proposedBaselinePolicy()
        )
        assertTrue(
            "Empty actor registry must reject",
            emptyRegistryAuthority.approveStageTransition(PipelineStage.IDEA, "creator_alice", "Notes").isFailure
        )

        // 3. Configured actor registry + policy validates roles and rejects AI / unregistered / out-of-scope actors
        val scopedPolicy = HumanAuthorizationPolicy(
            stageApprovalRoles = mapOf(
                PipelineStage.IDEA to setOf(ActorRole.HUMAN_CREATOR),
                PipelineStage.CURATION to setOf(ActorRole.HUMAN_CREATOR, ActorRole.HUMAN_CURATOR)
            ),
            gateAuthorizationRoles = mapOf(
                GateLevel.G1 to setOf(ActorRole.HUMAN_CREATOR, ActorRole.HUMAN_CURATOR),
                GateLevel.G4 to setOf(ActorRole.HUMAN_CREATOR, ActorRole.HUMAN_AUDITOR)
            ),
            vetoAndReviewRoles = HumanAuthorizationPolicy.ALL_HUMAN_ROLES
        )
        val configuredAuthority = ElyzarethHumanDecisionAuthority(
            projectId = testProjectId,
            evidenceLedger = evidenceLedger,
            registeredActors = registeredActors,
            authorizationPolicy = scopedPolicy
        )

        // sylvia_sys (HUMAN_CURATOR) succeeds on permitted curator operation (CURATION)
        assertTrue(
            configuredAuthority.approveStageTransition(PipelineStage.CURATION, "sylvia_sys", "Curated lyrics endorsed").isSuccess
        )

        // ai_drafter (AI_GENERATOR) rejected on human decisions
        assertTrue(
            configuredAuthority.approveStageTransition(PipelineStage.CURATION, "ai_drafter", "AI self-approval").isFailure
        )

        // creator_alice (HUMAN_CREATOR) succeeds on permitted creator operation (IDEA & G1)
        assertTrue(
            configuredAuthority.approveStageTransition(PipelineStage.IDEA, "creator_alice", "Creator approved").isSuccess
        )
        assertTrue(
            configuredAuthority.authorizeGatePassage(GateLevel.G1, "creator_alice", "HUMAN_CREATOR", "G1 authorized") { }.isSuccess
        )

        // Unregistered actor rejected
        assertTrue(
            configuredAuthority.approveStageTransition(PipelineStage.IDEA, "unregistered_bob", "Unregistered").isFailure
        )

        // sylvia_sys rejected on operation outside HUMAN_CURATOR scope (G4 requires HUMAN_CREATOR or HUMAN_AUDITOR)
        assertTrue(
            configuredAuthority.authorizeGatePassage(GateLevel.G4, "sylvia_sys", "HUMAN_CURATOR", "Out of scope") { }.isFailure
        )
    }

    @Test
    fun test21_cancellationBeforeLockAcquisition_and_cancellationDuringProtectedCommit() = runTest {
        val appendEntered = CompletableDeferred<Unit>()
        val releaseAppend = CompletableDeferred<Unit>()
        var suspendAtAppend = false

        val delegateLedger = InMemoryEvidenceLedger()
        val controllableLedger = object : EvidenceLedger by delegateLedger {
            private val gateMutex = Mutex()
            override val recordsFlow: StateFlow<List<EvidenceRecord>>
                get() = delegateLedger.recordsFlow

            override suspend fun record(evidence: EvidenceRecord): Result<EvidenceRecord> {
                if (suspendAtAppend) {
                    appendEntered.complete(Unit)
                    releaseAppend.await()
                }
                return gateMutex.withLock {
                    delegateLedger.record(evidence)
                }
            }
        }

        val auth = ElyzarethHumanDecisionAuthority(
            projectId = testProjectId,
            evidenceLedger = controllableLedger,
            registeredActors = defaultTestActors,
            authorizationPolicy = HumanAuthorizationPolicy.proposedBaselinePolicy()
        )
        val gov = ElyzarethGovernorEngine(
            projectId = testProjectId,
            humanDecisionAuthority = auth,
            evidenceLedger = controllableLedger
        )

        // Advance to GATE_1_PRE_PRODUCTION and evaluate G1
        advanceToGate1Stage(gov, auth)
        assertTrue(gov.evaluateGate(GateLevel.G1, passingGateCriteria("g1-cancellation")).isSuccess)
        val recordsBeforeProtectedCommit = controllableLedger.size()

        // 1. Cancellation during protected commit:
        // Start submitHumanGateSignOff(G1), wait until it enters evidenceLedger.record() inside NonCancellable,
        // then cancel the calling coroutine while simultaneously holding a second waiter queued on governorMutex.
        suspendAtAppend = true
        val signOff = HumanSignOff(
            signatoryId = "user_1",
            signatoryRole = "HUMAN_CREATOR",
            signedAtTimestamp = 1700000005000L,
            remarks = "Sign-off under cancellation test"
        )

        val commitJob = launch {
            gov.submitHumanGateSignOff(GateLevel.G1, signOff)
        }

        // Wait until commitJob has entered the protected NonCancellable append boundary (holding governorMutex)
        appendEntered.await()

        // 2. Cancellation before lock acquisition:
        // Launch a second operation that waits on governorMutex and cancel it before the lock is released
        val queuedJob = launch(start = CoroutineStart.UNDISPATCHED) {
            gov.evaluateGate(GateLevel.G2)
        }
        queuedJob.cancel(CancellationException("Cancelled while waiting for governorMutex"))
        queuedJob.join()
        assertTrue(queuedJob.isCancelled)

        // Now cancel commitJob while it is suspended inside the protected NonCancellable append boundary,
        // then release the append so the protected commit completes
        commitJob.cancel(CancellationException("Caller cancelled mid-commit"))
        releaseAppend.complete(Unit)
        commitJob.join()

        // Verify that the protected commit completed both evidence append AND GovernorSnapshot publication together,
        // and that the cancelled queuedJob produced zero evidence records
        assertEquals(recordsBeforeProtectedCommit + 1, controllableLedger.size())
        assertEquals(GateStatus.APPROVED, gov.governorSnapshot.value.project.gateRecords[GateLevel.G1]?.status)
        assertEquals(GateStatus.APPROVED, gov.governorSnapshot.value.pipeline.gateStatuses[GateLevel.G1])
        assertEquals(GateStatus.LOCKED, gov.governorSnapshot.value.project.gateRecords[GateLevel.G2]?.status)
        assertTrue(controllableLedger.verifyChainIntegrity())
    }

    @Test
    fun test22_transitionIsSerializedAgainstConcurrentHumanVeto() = runTest {
        val vetoAppendEntered = CompletableDeferred<Unit>()
        val releaseVetoAppend = CompletableDeferred<Unit>()
        var pauseVetoAppend = false
        val delegateLedger = InMemoryEvidenceLedger()
        val controlledLedger = object : EvidenceLedger by delegateLedger {
            override suspend fun record(evidence: EvidenceRecord): Result<EvidenceRecord> {
                if (pauseVetoAppend && evidence.eventType == EvidenceEventType.HUMAN_VETOED) {
                    vetoAppendEntered.complete(Unit)
                    releaseVetoAppend.await()
                }
                return delegateLedger.record(evidence)
            }
        }
        val auth = ElyzarethHumanDecisionAuthority(
            projectId = testProjectId,
            evidenceLedger = controlledLedger,
            registeredActors = defaultTestActors,
            authorizationPolicy = HumanAuthorizationPolicy.proposedBaselinePolicy()
        )
        val gov = ElyzarethGovernorEngine(
            projectId = testProjectId,
            humanDecisionAuthority = auth,
            evidenceLedger = controlledLedger
        )
        assertTrue(auth.approveStageTransition(PipelineStage.IDEA, "user_1", "Initial approval").isSuccess)

        pauseVetoAppend = true
        val vetoJob = async {
            auth.registerHumanVeto(PipelineStage.IDEA, "executive_carol", "Human veto wins serialization")
        }
        vetoAppendEntered.await()

        // The transition must wait for the authority decision lock held by the veto.
        val transitionJob = async(start = CoroutineStart.UNDISPATCHED) {
            gov.transitionToStage(PipelineStage.LYRIC, "Concurrent transition attempt")
        }
        releaseVetoAppend.complete(Unit)

        assertTrue(vetoJob.await().isSuccess)
        val transitionResult = transitionJob.await()
        assertTrue("A committed veto must block the waiting transition", transitionResult.isFailure)
        assertEquals(PipelineStage.IDEA, gov.governorSnapshot.value.project.currentStage)
        assertTrue(auth.hasActiveVeto(PipelineStage.IDEA))
        assertTrue(delegateLedger.getRecordsForStage(testProjectId, PipelineStage.LYRIC).none {
            it.eventType == EvidenceEventType.STAGE_TRANSITIONED
        })
        assertTrue(delegateLedger.verifyChainIntegrity())
    }

    @Test
    fun test23_gateRejectionBlocksApprovalUntilExplicitReviewCycle() = runTest {
        advanceToGate1Stage()
        assertTrue(governor.evaluateGate(GateLevel.G1, passingGateCriteria("g1-reject-cycle")).isSuccess)

        val rejected = governor.rejectHumanGatePassage(
            gate = GateLevel.G1,
            officerId = "user_1",
            reason = "Evidence package requires correction."
        )
        assertTrue(rejected.isSuccess)
        assertEquals(GateStatus.REJECTED, governor.governorSnapshot.value.project.gateRecords[GateLevel.G1]?.status)
        assertTrue(humanAuthority.hasActiveGateRejection(GateLevel.G1))

        val signOff = HumanSignOff(
            signatoryId = "user_1",
            signatoryRole = "HUMAN_CREATOR",
            signedAtTimestamp = 1700000006000L,
            remarks = "Attempt to approve rejected gate"
        )
        assertTrue("Rejected gate cannot be approved", governor.submitHumanGateSignOff(GateLevel.G1, signOff).isFailure)
        assertTrue("Authority must independently reject direct approval", humanAuthority.authorizeGatePassage(
            GateLevel.G1, "user_1", "HUMAN_CREATOR", "Direct bypass attempt"
        ) { }.isFailure)

        assertTrue(
            governor.openNewReviewCycle(
                PipelineStage.GATE_1_PRE_PRODUCTION,
                "user_1",
                "Evidence package corrected and resubmitted."
            ).isSuccess
        )
        assertFalse(humanAuthority.hasActiveGateRejection(GateLevel.G1))
        assertEquals(GateStatus.PENDING_EVALUATION, governor.governorSnapshot.value.project.gateRecords[GateLevel.G1]?.status)
        assertTrue(governor.governorSnapshot.value.project.gateRecords[GateLevel.G1]?.criteriaResults.orEmpty().isEmpty())
        assertTrue(governor.evaluateGate(GateLevel.G1, passingGateCriteria("g1-review-cycle-2")).isSuccess)
        assertTrue(governor.submitHumanGateSignOff(GateLevel.G1, signOff).isSuccess)
    }

    @Test
    fun test24_gateApprovalRequiresAllExplicitCriteriaToBeSatisfied() = runTest {
        advanceToGate1Stage()
        val criteria = listOf(
            GateCriterionCheck("source-present", "Source evidence present", true, "Source manifest inspected."),
            GateCriterionCheck("provenance-valid", "Provenance verified", false, "Parent provenance is incomplete.")
        )
        assertTrue(governor.evaluateGate(GateLevel.G1, criteria).isSuccess)
        val gateEvidence = evidenceLedger.getRecordsForStage(
            testProjectId, PipelineStage.GATE_1_PRE_PRODUCTION
        ).last { it.eventType == EvidenceEventType.GATE_EVALUATED }
        assertEquals("provenance-valid", gateEvidence.metadata["criteria.0.id"])
        assertEquals("false", gateEvidence.metadata["criteria.0.satisfied"])
        val signOff = HumanSignOff(
            signatoryId = "user_1",
            signatoryRole = "HUMAN_CREATOR",
            signedAtTimestamp = 1700000007000L,
            remarks = "Cannot approve incomplete criteria"
        )
        assertTrue(governor.submitHumanGateSignOff(GateLevel.G1, signOff).isFailure)
        assertEquals(GateStatus.HUMAN_REVIEW_REQUIRED, governor.governorSnapshot.value.project.gateRecords[GateLevel.G1]?.status)
    }

    @Test
    fun test25_emptyGateEvaluationIsRejectedWithoutEvidenceOrStateMutation() = runTest {
        advanceToGate1Stage()
        val beforeSnapshot = governor.governorSnapshot.value
        val beforeEvidenceCount = evidenceLedger.size()
        val result = governor.evaluateGate(GateLevel.G1)
        assertTrue(result.isFailure)
        assertSame(beforeSnapshot, governor.governorSnapshot.value)
        assertEquals(beforeEvidenceCount, evidenceLedger.size())
        assertEquals(GateStatus.LOCKED, governor.governorSnapshot.value.project.gateRecords[GateLevel.G1]?.status)
    }

    @Test
    fun test26_newProjectCannotStartAtAnArbitraryPipelineStage() = runTest {
        val result = runCatching {
            ElyzarethGovernorEngine(
                projectId = testProjectId,
                humanDecisionAuthority = humanAuthority,
                evidenceLedger = evidenceLedger,
                initialStage = PipelineStage.GATE_1_PRE_PRODUCTION
            )
        }
        assertTrue("New projects must start at IDEA; restore must be a separate validated path", result.isFailure)
    }

    @Test
    fun test27_vetoOfPreviouslyCompletedStageBlocksFurtherProgressUntilReapproved() = runTest {
        assertTrue(humanAuthority.approveStageTransition(PipelineStage.IDEA, "user_1", "Idea approved").isSuccess)
        assertTrue(governor.transitionToStage(PipelineStage.LYRIC, "Enter lyric stage").isSuccess)
        assertTrue(humanAuthority.registerHumanVeto(PipelineStage.IDEA, "executive_carol", "Reopen idea decision").isSuccess)

        val blocked = governor.transitionToStage(PipelineStage.EVIDENCE, "Try to continue after retroactive veto")
        assertTrue("A veto against a completed human-decision stage must block progression", blocked.isFailure)
        assertEquals(PipelineStage.LYRIC, governor.governorSnapshot.value.project.currentStage)

        assertTrue(governor.openNewReviewCycle(
            PipelineStage.IDEA, "user_1", "Re-review completed; prior approval withdrawn."
        ).isSuccess)
        assertTrue(humanAuthority.approveStageTransition(PipelineStage.IDEA, "user_1", "Re-approved after review").isSuccess)
        assertTrue(governor.transitionToStage(PipelineStage.EVIDENCE, "Continue after renewed approval").isSuccess)
    }


    @Test
    fun test28_gateRejectionEvidenceFailureDoesNotPublishRejectionState() = runTest {
        var allowWrites = true
        val delegateLedger = InMemoryEvidenceLedger()
        val failingLedger = object : EvidenceLedger by delegateLedger {
            override suspend fun record(evidence: EvidenceRecord): Result<EvidenceRecord> {
                if (!allowWrites && evidence.metadata["action"] == "GATE_REJECTION") {
                    return Result.failure(IllegalStateException("Simulated gate rejection evidence failure"))
                }
                return delegateLedger.record(evidence)
            }
        }
        val auth = ElyzarethHumanDecisionAuthority(
            projectId = testProjectId,
            evidenceLedger = failingLedger,
            registeredActors = defaultTestActors,
            authorizationPolicy = HumanAuthorizationPolicy.proposedBaselinePolicy()
        )
        val gov = ElyzarethGovernorEngine(
            projectId = testProjectId,
            humanDecisionAuthority = auth,
            evidenceLedger = failingLedger
        )
        advanceToGate1Stage(gov, auth)
        assertTrue(gov.evaluateGate(GateLevel.G1, passingGateCriteria("g1-rejection-write-failure")).isSuccess)
        val beforeSnapshot = gov.governorSnapshot.value
        val beforeCount = delegateLedger.size()

        allowWrites = false
        val result = gov.rejectHumanGatePassage(GateLevel.G1, "user_1", "Rejected but evidence cannot be stored")
        assertTrue(result.isFailure)
        assertSame(beforeSnapshot, gov.governorSnapshot.value)
        assertFalse(auth.hasActiveGateRejection(GateLevel.G1))
        assertEquals(beforeCount, delegateLedger.size())
        assertTrue(delegateLedger.verifyChainIntegrity())
    }

    @Test
    fun test29_gateSignOffSatisfiesGateStageTransitionWithoutDuplicateStageApproval_and_blocksStageRejection() = runTest {
        advanceToGate1Stage()
        assertEquals(PipelineStage.GATE_1_PRE_PRODUCTION, governor.governorSnapshot.value.project.currentStage)

        // 1. Evaluate and sign off G1 -> transition to GATE_2 without calling approveStageTransition
        assertTrue(governor.evaluateGate(GateLevel.G1, passingGateCriteria("g1-f01")).isSuccess)
        val signOffCreator = HumanSignOff(
            signatoryId = "user_1",
            signatoryRole = "HUMAN_CREATOR",
            signedAtTimestamp = 1700000010000L,
            remarks = "G1 approved via canonical gate sign-off"
        )
        assertTrue(governor.submitHumanGateSignOff(GateLevel.G1, signOffCreator).isSuccess)
        assertTrue(humanAuthority.isStageApproved(PipelineStage.GATE_1_PRE_PRODUCTION))
        assertTrue(governor.transitionToStage(PipelineStage.GATE_2_CURATION_APPROVAL, "Advance to G2 via G1 sign-off").isSuccess)

        // 2. Evaluate and sign off G2 -> transition to GATE_3 without calling approveStageTransition
        assertTrue(governor.evaluateGate(GateLevel.G2, passingGateCriteria("g2-f01")).isSuccess)
        assertTrue(governor.submitHumanGateSignOff(GateLevel.G2, signOffCreator.copy(signedAtTimestamp = 1700000011000L)).isSuccess)
        assertTrue(humanAuthority.isStageApproved(PipelineStage.GATE_2_CURATION_APPROVAL))
        assertTrue(governor.transitionToStage(PipelineStage.GATE_3_DNA_CONFORMANCE, "Advance to G3 via G2 sign-off").isSuccess)

        // 3. Amendment 1: Stage rejection on a gate stage must block authorizeGatePassage / submitHumanGateSignOff
        //    and must NOT be cleared without openNewReviewCycle
        assertTrue(governor.evaluateGate(GateLevel.G3, passingGateCriteria("g3-f01-rejected-stage")).isSuccess)
        assertTrue(
            humanAuthority.rejectStageTransition(
                PipelineStage.GATE_3_DNA_CONFORMANCE,
                "user_1",
                "Stage-level DNA concern raised before sign-off"
            ).isSuccess
        )
        assertTrue(
            "Amendment 1: submitHumanGateSignOff must fail closed when correspondingStage has an active stage rejection",
            governor.submitHumanGateSignOff(GateLevel.G3, signOffCreator.copy(signedAtTimestamp = 1700000012000L)).isFailure
        )
        assertTrue(
            "Amendment 1: direct authorizeGatePassage must also fail closed and preserve stage rejection",
            humanAuthority.authorizeGatePassage(GateLevel.G3, "user_1", "HUMAN_CREATOR", "Attempt bypass") { }.isFailure
        )

        // Open explicit review cycle for GATE_3_DNA_CONFORMANCE, re-evaluate, and sign off G3 -> transition to GATE_4
        assertTrue(
            governor.openNewReviewCycle(
                PipelineStage.GATE_3_DNA_CONFORMANCE,
                "user_1",
                "Stage-level DNA concern resolved in new review cycle."
            ).isSuccess
        )
        assertTrue(governor.evaluateGate(GateLevel.G3, passingGateCriteria("g3-f01-recheck")).isSuccess)
        assertTrue(governor.submitHumanGateSignOff(GateLevel.G3, signOffCreator.copy(signedAtTimestamp = 1700000013000L)).isSuccess)
        assertTrue(governor.transitionToStage(PipelineStage.GATE_4_FORENSIC_CLEARANCE, "Advance to G4 via G3 sign-off").isSuccess)

        // 4. Retroactive veto on previously completed GATE_1_PRE_PRODUCTION invalidates effective approval
        //    and blocks evaluating/signing off G4 from advancing until GATE_1 is reviewed and re-approved
        assertTrue(governor.evaluateGate(GateLevel.G4, passingGateCriteria("g4-f01")).isSuccess)
        assertTrue(governor.submitHumanGateSignOff(GateLevel.G4, signOffCreator.copy(signedAtTimestamp = 1700000014000L)).isSuccess)
        assertTrue(humanAuthority.isStageApproved(PipelineStage.GATE_4_FORENSIC_CLEARANCE))

        assertTrue(
            humanAuthority.registerHumanVeto(
                PipelineStage.GATE_1_PRE_PRODUCTION,
                "executive_carol",
                "Retroactive veto on completed G1 stage"
            ).isSuccess
        )
        assertFalse(
            "Retroactive veto on GATE_1 must immediately invalidate isStageApproved(GATE_1)",
            humanAuthority.isStageApproved(PipelineStage.GATE_1_PRE_PRODUCTION)
        )
        assertTrue(
            "Opening review cycle on GATE_1 clears veto and requires fresh approval",
            governor.openNewReviewCycle(
                PipelineStage.GATE_1_PRE_PRODUCTION,
                "executive_carol",
                "Reviewed G1 concern"
            ).isSuccess
        )
        assertFalse(humanAuthority.isStageApproved(PipelineStage.GATE_1_PRE_PRODUCTION))
        assertTrue(evidenceLedger.verifyChainIntegrity())
    }

    @Test
    fun test30_openNewReviewCycleReconcilesRejectedGateInGovernorSnapshotAndPreservesUnrelatedStagesAndGates() = runTest {
        var allowReviewCycleWrite = true
        val delegateLedger = InMemoryEvidenceLedger()
        val controllableLedger = object : EvidenceLedger by delegateLedger {
            override suspend fun record(evidence: EvidenceRecord): Result<EvidenceRecord> {
                if (!allowReviewCycleWrite && evidence.metadata["action"] == "OPEN_NEW_REVIEW_CYCLE") {
                    return Result.failure(IllegalStateException("Simulated OPEN_NEW_REVIEW_CYCLE ledger failure"))
                }
                return delegateLedger.record(evidence)
            }
        }
        val auth = ElyzarethHumanDecisionAuthority(
            projectId = testProjectId,
            evidenceLedger = controllableLedger,
            registeredActors = defaultTestActors,
            authorizationPolicy = HumanAuthorizationPolicy.proposedBaselinePolicy()
        )
        val gov = ElyzarethGovernorEngine(
            projectId = testProjectId,
            humanDecisionAuthority = auth,
            evidenceLedger = controllableLedger
        )

        // Advance to GATE_1, evaluate and approve G1, advance to GATE_2
        advanceToGate1Stage(gov, auth)
        assertTrue(gov.evaluateGate(GateLevel.G1, passingGateCriteria("g1-pass")).isSuccess)
        val signOffG1 = HumanSignOff(
            signatoryId = "user_1",
            signatoryRole = "HUMAN_CREATOR",
            signedAtTimestamp = 1700000020000L,
            remarks = "G1 approved"
        )
        assertTrue(gov.submitHumanGateSignOff(GateLevel.G1, signOffG1).isSuccess)
        assertTrue(gov.transitionToStage(PipelineStage.GATE_2_CURATION_APPROVAL, "Enter G2").isSuccess)

        // Evaluate and reject G2
        assertTrue(gov.evaluateGate(GateLevel.G2, passingGateCriteria("g2-initial")).isSuccess)
        assertTrue(gov.rejectHumanGatePassage(GateLevel.G2, "user_1", "G2 provenance mismatch").isSuccess)
        assertEquals(GateStatus.REJECTED, gov.governorSnapshot.value.project.gateRecords[GateLevel.G2]?.status)
        assertTrue(auth.hasActiveGateRejection(GateLevel.G2))

        // Register an unrelated veto on CURATION to verify stage-scoped isolation
        assertTrue(auth.registerHumanVeto(PipelineStage.CURATION, "executive_carol", "Unrelated curation veto").isSuccess)
        assertTrue(auth.hasActiveVeto(PipelineStage.CURATION))

        // 1. If ledger write fails during openNewReviewCycle, neither Authority nor GovernorSnapshot changes
        val snapshotBeforeFailedReview = gov.governorSnapshot.value
        val ledgerSizeBeforeFailedReview = delegateLedger.size()
        allowReviewCycleWrite = false
        val failedReviewCycle = gov.openNewReviewCycle(
            stage = PipelineStage.GATE_2_CURATION_APPROVAL,
            userId = "user_1",
            resolutionNotes = "Attempted review cycle while ledger fails"
        )
        assertTrue(failedReviewCycle.isFailure)
        assertSame(snapshotBeforeFailedReview, gov.governorSnapshot.value)
        assertTrue(auth.hasActiveGateRejection(GateLevel.G2))
        assertEquals(GateStatus.REJECTED, gov.governorSnapshot.value.project.gateRecords[GateLevel.G2]?.status)
        assertEquals(ledgerSizeBeforeFailedReview, delegateLedger.size())

        // 2. Allow ledger write and execute coordinated openNewReviewCycle on GATE_2_CURATION_APPROVAL
        allowReviewCycleWrite = true
        val reviewCycleSuccess = gov.openNewReviewCycle(
            stage = PipelineStage.GATE_2_CURATION_APPROVAL,
            userId = "user_1",
            resolutionNotes = "Provenance discrepancy resolved; opening cycle 2 for G2."
        )
        assertTrue(reviewCycleSuccess.isSuccess)

        // Verify atomic reconciliation inside withDecisionLock and via GovernorSnapshot
        auth.withDecisionLock {
            assertFalse("G2 rejection must be cleared", auth.hasActiveGateRejection(GateLevel.G2))
            assertFalse("GATE_2 stage approval must remain false until re-approved", auth.isStageApproved(PipelineStage.GATE_2_CURATION_APPROVAL))
            assertTrue("Unrelated CURATION veto must remain untouched", auth.hasActiveVeto(PipelineStage.CURATION))
            assertTrue("Unrelated GATE_1 approval must remain untouched", auth.isStageApproved(PipelineStage.GATE_1_PRE_PRODUCTION))
        }

        val reconciledSnapshot = gov.governorSnapshot.value
        val g2Record = reconciledSnapshot.project.gateRecords[GateLevel.G2]!!
        assertEquals(GateStatus.PENDING_EVALUATION, g2Record.status)
        assertEquals(GateStatus.PENDING_EVALUATION, reconciledSnapshot.pipeline.gateStatuses[GateLevel.G2])
        assertTrue("Stale criteriaResults on G2 must be cleared on review cycle reset", g2Record.criteriaResults.isEmpty())
        assertEquals(null, g2Record.humanDecision)

        // Unrelated G1 gate record remains APPROVED
        assertEquals(GateStatus.APPROVED, reconciledSnapshot.project.gateRecords[GateLevel.G1]?.status)
        assertEquals(GateStatus.APPROVED, reconciledSnapshot.pipeline.gateStatuses[GateLevel.G1])

        // Verify HUMAN_REVISED evidence metadata identifies the review cycle, stage, gate, and resolution rationale
        val reviewEvidence = delegateLedger.getRecordsForStage(
            testProjectId, PipelineStage.GATE_2_CURATION_APPROVAL
        ).last { it.eventType == EvidenceEventType.HUMAN_REVISED }
        assertEquals("OPEN_NEW_REVIEW_CYCLE", reviewEvidence.metadata["action"])
        assertEquals("GATE_2_CURATION_APPROVAL", reviewEvidence.metadata["stage"])
        assertEquals("G2", reviewEvidence.metadata["gate"])
        assertEquals("Provenance discrepancy resolved; opening cycle 2 for G2.", reviewEvidence.metadata["resolutionNotes"])
        assertTrue(delegateLedger.verifyChainIntegrity())
    }

    @Test
    fun test31_submitHumanGateSignOffValidatesRegisteredRoleMatchAndPositiveTimestamp() = runTest {
        advanceToGate1Stage()
        assertTrue(governor.evaluateGate(GateLevel.G1, passingGateCriteria("g1-f03")).isSuccess)

        val baselineSnapshot = governor.governorSnapshot.value
        val baselineLedgerSize = evidenceLedger.size()

        // 1. Zero timestamp -> rejected with zero evidence
        val zeroTsSignOff = HumanSignOff(
            signatoryId = "user_1",
            signatoryRole = "HUMAN_CREATOR",
            signedAtTimestamp = 0L,
            remarks = "Zero timestamp"
        )
        assertTrue(governor.submitHumanGateSignOff(GateLevel.G1, zeroTsSignOff).isFailure)
        assertSame(baselineSnapshot, governor.governorSnapshot.value)
        assertEquals(baselineLedgerSize, evidenceLedger.size())

        // 2. Negative timestamp -> rejected with zero evidence
        val negativeTsSignOff = zeroTsSignOff.copy(signedAtTimestamp = -500L, remarks = "Negative timestamp")
        assertTrue(governor.submitHumanGateSignOff(GateLevel.G1, negativeTsSignOff).isFailure)
        assertSame(baselineSnapshot, governor.governorSnapshot.value)
        assertEquals(baselineLedgerSize, evidenceLedger.size())

        // 3. Blank signatoryRole -> rejected with zero evidence
        val blankRoleSignOff = zeroTsSignOff.copy(
            signatoryRole = "   ",
            signedAtTimestamp = 1700000030000L,
            remarks = "Blank role"
        )
        assertTrue(governor.submitHumanGateSignOff(GateLevel.G1, blankRoleSignOff).isFailure)
        assertSame(baselineSnapshot, governor.governorSnapshot.value)
        assertEquals(baselineLedgerSize, evidenceLedger.size())

        // 4. Mismatched signatoryRole ("user_1" is registered as HUMAN_CREATOR, not HUMAN_AUDITOR) -> rejected
        val mismatchedRoleSignOff = zeroTsSignOff.copy(
            signatoryRole = "HUMAN_AUDITOR",
            signedAtTimestamp = 1700000030000L,
            remarks = "Mismatched role claim"
        )
        assertTrue(governor.submitHumanGateSignOff(GateLevel.G1, mismatchedRoleSignOff).isFailure)
        assertSame(baselineSnapshot, governor.governorSnapshot.value)
        assertEquals(baselineLedgerSize, evidenceLedger.size())

        // Direct authorizeGatePassage with mismatched role also rejected
        assertTrue(
            humanAuthority.authorizeGatePassage(
                GateLevel.G1,
                "user_1",
                "HUMAN_AUDITOR",
                "Direct mismatched role claim"
            ) { }.isFailure
        )
        assertEquals(baselineLedgerSize, evidenceLedger.size())

        // 5. Valid sign-off with matching registered role ("HUMAN_CREATOR") and positive timestamp -> succeeds
        val validSignOff = HumanSignOff(
            signatoryId = "user_1",
            signatoryRole = "HUMAN_CREATOR",
            signedAtTimestamp = 1700000035000L,
            remarks = "Authenticated creator sign-off"
        )
        assertTrue(governor.submitHumanGateSignOff(GateLevel.G1, validSignOff).isSuccess)
        val committedDecision = governor.governorSnapshot.value.project.gateRecords[GateLevel.G1]?.humanDecision
        assertNotNull(committedDecision)
        assertEquals("user_1", committedDecision?.signatoryId)
        assertEquals("HUMAN_CREATOR", committedDecision?.signatoryRole)
        assertEquals(1700000035000L, committedDecision?.signedAtTimestamp)

        val approvalEvidence = evidenceLedger.getRecordsForStage(
            testProjectId, PipelineStage.GATE_1_PRE_PRODUCTION
        ).last { it.eventType == EvidenceEventType.HUMAN_APPROVED }
        assertTrue("EvidenceRecord.timestampMillis must be positive ledger commit time", approvalEvidence.timestampMillis > 0L)
        assertEquals("HUMAN_CREATOR", approvalEvidence.metadata["role"])
        assertTrue(evidenceLedger.verifyChainIntegrity())
    }

    @Test
    fun test32_evaluateGateIsSerializedAgainstConcurrentHumanVeto() = runTest {
        val vetoAppendEntered = CompletableDeferred<Unit>()
        val releaseVetoAppend = CompletableDeferred<Unit>()
        var pauseVetoAppend = false

        val delegateLedger = InMemoryEvidenceLedger()
        val controlledLedger = object : EvidenceLedger by delegateLedger {
            override suspend fun record(evidence: EvidenceRecord): Result<EvidenceRecord> {
                if (pauseVetoAppend && evidence.eventType == EvidenceEventType.HUMAN_VETOED) {
                    vetoAppendEntered.complete(Unit)
                    releaseVetoAppend.await()
                }
                return delegateLedger.record(evidence)
            }
        }
        val auth = ElyzarethHumanDecisionAuthority(
            projectId = testProjectId,
            evidenceLedger = controlledLedger,
            registeredActors = defaultTestActors,
            authorizationPolicy = HumanAuthorizationPolicy.proposedBaselinePolicy()
        )
        val gov = ElyzarethGovernorEngine(
            projectId = testProjectId,
            humanDecisionAuthority = auth,
            evidenceLedger = controlledLedger
        )

        advanceToGate1Stage(gov, auth)
        assertEquals(PipelineStage.GATE_1_PRE_PRODUCTION, gov.governorSnapshot.value.project.currentStage)

        pauseVetoAppend = true
        val vetoJob = async {
            auth.registerHumanVeto(
                PipelineStage.GATE_1_PRE_PRODUCTION,
                "executive_carol",
                "Concurrent veto on G1 stage before evaluation completes"
            )
        }
        vetoAppendEntered.await()

        // evaluateGate must wait on humanDecisionAuthority.withDecisionLock held by registerHumanVeto
        val evalJob = async(start = CoroutineStart.UNDISPATCHED) {
            gov.evaluateGate(GateLevel.G1, passingGateCriteria("g1-concurrent-eval"))
        }
        releaseVetoAppend.complete(Unit)

        assertTrue(vetoJob.await().isSuccess)
        val evalResult = evalJob.await()
        assertTrue("Committed veto must cause waiting evaluateGate to fail closed", evalResult.isFailure)
        assertEquals(GateStatus.LOCKED, gov.governorSnapshot.value.project.gateRecords[GateLevel.G1]?.status)
        assertTrue(auth.hasActiveVeto(PipelineStage.GATE_1_PRE_PRODUCTION))
        assertTrue(
            "Zero GATE_EVALUATED records may be recorded when veto wins serialization",
            delegateLedger.getRecordsForStage(testProjectId, PipelineStage.GATE_1_PRE_PRODUCTION)
                .none { it.eventType == EvidenceEventType.GATE_EVALUATED }
        )
        assertTrue(delegateLedger.verifyChainIntegrity())
    }

    @Test
    fun test33_transitionToStageRejectsBlankRationaleWithoutMutationOrEvidence() = runTest {
        assertTrue(humanAuthority.approveStageTransition(PipelineStage.IDEA, "user_1", "Idea approved").isSuccess)
        val beforeSnapshot = governor.governorSnapshot.value
        val beforeLedgerSize = evidenceLedger.size()

        val emptyRationaleResult = governor.transitionToStage(PipelineStage.LYRIC, "")
        assertTrue("Empty transition rationale must be rejected", emptyRationaleResult.isFailure)
        assertSame(beforeSnapshot, governor.governorSnapshot.value)
        assertEquals(beforeLedgerSize, evidenceLedger.size())

        val whitespaceRationaleResult = governor.transitionToStage(PipelineStage.LYRIC, "   \t\n  ")
        assertTrue("Whitespace-only transition rationale must be rejected", whitespaceRationaleResult.isFailure)
        assertSame(beforeSnapshot, governor.governorSnapshot.value)
        assertEquals(beforeLedgerSize, evidenceLedger.size())
    }

}
