package com.example.elyzareth

import com.example.elyzareth.core.dataflow.ElyzarethHumanDecisionAuthority
import com.example.elyzareth.core.governor.ElyzarethGovernorEngine
import com.example.elyzareth.core.ledger.InMemoryEvidenceLedger
import com.example.elyzareth.core.model.EvidenceEventType
import com.example.elyzareth.core.model.GateLevel
import com.example.elyzareth.core.model.HumanSignOff
import com.example.elyzareth.core.model.PipelineStage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Focused unit tests for Elyzareth OS Baseline 02.
 * Validates the core invariants for:
 * 1. Evidence Ledger
 * 2. Human Decision Authority
 * 3. Governor State Transitions
 */
class GovernorEvidenceAuthorityTest {

    private val testProjectId = "test_track_001"
    private lateinit var evidenceLedger: InMemoryEvidenceLedger
    private lateinit var humanAuthority: ElyzarethHumanDecisionAuthority
    private lateinit var governor: ElyzarethGovernorEngine

    @Before
    fun setup() {
        evidenceLedger = InMemoryEvidenceLedger()
        humanAuthority = ElyzarethHumanDecisionAuthority(testProjectId, evidenceLedger)
        governor = ElyzarethGovernorEngine(
            projectId = testProjectId,
            projectTitle = "Baseline 02 Verification Track",
            humanDecisionAuthority = humanAuthority,
            evidenceLedger = evidenceLedger,
            initialStage = PipelineStage.IDEA
        )
    }

    @Test
    fun test1_validStageTransition_succeeds() = runTest {
        // Idea stage requires human approval
        val approvalResult = humanAuthority.approveStageTransition(
            stage = PipelineStage.IDEA,
            userId = "curator_alice",
            notes = "Theme and seed concept approved for drafting."
        )
        assertTrue("Human approval should succeed", approvalResult.isSuccess)

        // Advance from IDEA to LYRIC
        val transitionResult = governor.transitionToStage(
            targetStage = PipelineStage.LYRIC,
            rationale = "Proceeding to initial lyrical drafting."
        )

        assertTrue("Valid transition should succeed", transitionResult.isSuccess)
        assertEquals(PipelineStage.LYRIC, governor.pipelineState.value.currentStage)
        assertTrue(governor.pipelineState.value.completedStages.contains(PipelineStage.IDEA))
    }

    @Test
    fun test2_invalidStageTransition_fails() = runTest {
        // Attempt to skip prerequisite stages (jump from IDEA directly to PRODUCTION)
        val skipResult = governor.transitionToStage(
            targetStage = PipelineStage.PRODUCTION,
            rationale = "Skipping ahead to production."
        )
        assertTrue("Skipping prerequisite stages must fail", skipResult.isFailure)
        assertEquals(PipelineStage.IDEA, governor.pipelineState.value.currentStage)

        // Advance to LYRIC first
        humanAuthority.approveStageTransition(PipelineStage.IDEA, "curator_alice", "OK")
        governor.transitionToStage(PipelineStage.LYRIC, "To lyric")

        // Attempt backward transition to IDEA
        val backwardResult = governor.transitionToStage(
            targetStage = PipelineStage.IDEA,
            rationale = "Attempting backward jump"
        )
        assertTrue("Backward transition must fail", backwardResult.isFailure)
    }

    @Test
    fun test3_humanRequiredTransition_cannotBypassHumanAuthorization() = runTest {
        // Current stage is IDEA, which requires human decision.
        // Do NOT call humanAuthority.approveStageTransition
        val transitionResult = governor.transitionToStage(
            targetStage = PipelineStage.LYRIC,
            rationale = "Bypassing human approval"
        )

        assertTrue("Transition requiring human approval must fail if not authorized", transitionResult.isFailure)
        val error = transitionResult.exceptionOrNull()?.message ?: ""
        assertTrue("Error must state human authorization is required", error.contains("Human authorization required"))
        assertEquals(PipelineStage.IDEA, governor.pipelineState.value.currentStage)
    }

    @Test
    fun test4_humanApproval_allowsAppropriateTransition() = runTest {
        // Human approves IDEA
        val approval = humanAuthority.approveStageTransition(
            stage = PipelineStage.IDEA,
            userId = "creative_lead_bob",
            notes = "Artistic direction validated."
        )
        assertTrue(approval.isSuccess)
        assertTrue(humanAuthority.isStageApproved(PipelineStage.IDEA))

        // Transition from IDEA to LYRIC succeeds
        val transition1 = governor.transitionToStage(PipelineStage.LYRIC, "Starting lyrics")
        assertTrue(transition1.isSuccess)
        assertEquals(PipelineStage.LYRIC, governor.pipelineState.value.currentStage)

        // LYRIC has requiresHumanDecision = false. Transition to EVIDENCE proceeds.
        val transition2 = governor.transitionToStage(PipelineStage.EVIDENCE, "Logging generation metadata")
        assertTrue(transition2.isSuccess)
        assertEquals(PipelineStage.EVIDENCE, governor.pipelineState.value.currentStage)
    }

    @Test
    fun test5_humanVeto_blocksTransition() = runTest {
        // First, human approves IDEA
        humanAuthority.approveStageTransition(
            stage = PipelineStage.IDEA,
            userId = "director_bob",
            notes = "Initial green light"
        )
        assertTrue(humanAuthority.isStageApproved(PipelineStage.IDEA))

        // Executive enters a veto on IDEA
        val vetoResult = humanAuthority.registerHumanVeto(
            stage = PipelineStage.IDEA,
            userId = "executive_carol",
            reason = "Sensitive themes conflict with artist DNA policy."
        )
        assertTrue(vetoResult.isSuccess)
        assertTrue(humanAuthority.hasActiveVeto(PipelineStage.IDEA))
        assertFalse("Veto must invalidate previous approval", humanAuthority.isStageApproved(PipelineStage.IDEA))

        // Attempt transition while veto is active
        val transitionResult = governor.transitionToStage(
            targetStage = PipelineStage.LYRIC,
            rationale = "Attempting to push past veto"
        )
        assertTrue("Active veto must block transition", transitionResult.isFailure)
        val errorMessage = transitionResult.exceptionOrNull()?.message ?: ""
        assertTrue("Error should mention veto", errorMessage.contains("veto"))
        assertEquals(PipelineStage.IDEA, governor.pipelineState.value.currentStage)

        // Automated code cannot override a veto by calling approve
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
        evidenceLedger.clear()

        // 1. Human approval
        humanAuthority.approveStageTransition(PipelineStage.IDEA, "user_1", "Approved")
        // 2. Governor transition
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
        evidenceLedger.clear()

        // Attempt approval with empty actor
        val blankResult = humanAuthority.approveStageTransition(
            stage = PipelineStage.IDEA,
            userId = "",
            notes = "Empty actor"
        )
        assertTrue("Blank actor must fail", blankResult.isFailure)

        // Attempt approval with system actor
        val systemResult = humanAuthority.approveStageTransition(
            stage = PipelineStage.IDEA,
            userId = "SYSTEM",
            notes = "Automated bypass attempt"
        )
        assertTrue("SYSTEM actor must fail", systemResult.isFailure)

        // Attempt approval with bot actor
        val botResult = humanAuthority.approveStageTransition(
            stage = PipelineStage.IDEA,
            userId = "BOT_AUTO_APPROVER",
            notes = "Synthetic approval attempt"
        )
        assertTrue("BOT actor must fail", botResult.isFailure)

        // Verify zero false human approval records were created in the ledger
        val records = evidenceLedger.recordsFlow.value
        assertTrue("No false evidence records may exist", records.isEmpty())
        assertFalse("Stage must not be approved", humanAuthority.isStageApproved(PipelineStage.IDEA))
    }

    @Test
    fun test10_g5ReleaseLock_remainsUnavailable() = runTest {
        // Attempting to execute G5 Release Lock must fail
        val lockResult = governor.executeGate5ReleaseLock("mock_signature")
        assertTrue("Gate 5 release lock must fail", lockResult.isFailure)
        val lockError = lockResult.exceptionOrNull()?.message ?: ""
        assertTrue(lockError.contains("locked and non-operational"))

        // Master Player must be strictly locked
        assertFalse("Master Player must be locked", governor.isMasterPlayerUnlocked())

        // Evaluating G5 must fail
        val evalResult = governor.evaluateGate(GateLevel.G5)
        assertTrue("Evaluating Gate 5 must fail", evalResult.isFailure)

        // Human gate sign-off for G5 must fail
        val signOff = HumanSignOff(
            signatoryId = "chief_officer",
            signatoryRole = "Executive",
            signedAtTimestamp = System.currentTimeMillis(),
            remarks = "Attempting G5 seal"
        )
        val signOffResult = governor.submitHumanGateSignOff(GateLevel.G5, signOff)
        assertTrue("Signing off Gate 5 must fail", signOffResult.isFailure)

        // Transitioning directly to G5 stage must fail
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
    }

    @Test
    fun test12_governor_maintainsCoherentPipelineState() = runTest {
        // Stage 1: IDEA
        humanAuthority.approveStageTransition(PipelineStage.IDEA, "user_1", "OK")
        governor.transitionToStage(PipelineStage.LYRIC, "To Lyric")

        // Stage 2: LYRIC -> EVIDENCE
        governor.transitionToStage(PipelineStage.EVIDENCE, "To Evidence")

        val state = governor.pipelineState.value
        assertEquals(PipelineStage.EVIDENCE, state.currentStage)
        assertEquals(setOf(PipelineStage.IDEA, PipelineStage.LYRIC), state.completedStages)
        assertFalse(state.isReleaseLocked)
        assertFalse(state.humanDecisionRequired) // Evidence logging is automated

        val projectState = governor.currentProjectState.value
        assertNotNull(projectState)
        assertEquals(PipelineStage.EVIDENCE, projectState?.currentStage)
    }
}
