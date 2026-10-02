package com.example.elyzareth.modules.verification.contract

import com.example.elyzareth.core.model.GateLevel
import com.example.elyzareth.core.model.GateStatus
import com.example.elyzareth.core.model.GateVerificationRecord
import com.example.elyzareth.core.model.HumanSignOff
import com.example.elyzareth.core.model.ProjectAggregate

/**
 * Module Boundary: G1–G5 VERIFICATION ENGINE
 *
 * Responsibility:
 * Enforces stage gates in strict sequential order:
 * G1: Pre-Production Concept & Curated Lyrics
 * G2: Evidence Provenance & Audit Trail
 * G3: Creative DNA Conformance
 * G4: Forensic Clearance (Plagiarism & Audio Integrity)
 * G5: Release Lock (Irreversible Sealing)
 *
 * Invariant: Every gate evaluation requires explicit Human Sign-Off to transition to APPROVED.
 *
 * Upstream Dependency: All preceding pipeline stage artifacts
 * Downstream Dependency: Release Lock, Master Player
 *
 * NOTE: Architecture Phase Only.
 * TODO: Implement Gate Verification rules engine in Phase 2 (automated checklist verifiers, digital signature validation, audit log persistence).
 */
interface VerificationGateContract {

    /**
     * Checks criteria readiness for the specified gate.
     * TODO: Implement in Phase 2.
     */
    suspend fun checkGateReadiness(
        project: ProjectAggregate,
        gate: GateLevel
    ): GateVerificationRecord

    /**
     * Records human sign-off approving or rejecting a gate.
     * TODO: Implement in Phase 2.
     */
    suspend fun recordHumanDecision(
        project: ProjectAggregate,
        gate: GateLevel,
        approved: Boolean,
        signOff: HumanSignOff
    ): Result<GateVerificationRecord>

    /**
     * Confirms all G1 through G4 gates are fully approved before allowing G5 Lock.
     * TODO: Implement in Phase 2.
     */
    fun canProceedToGate5(project: ProjectAggregate): Boolean
}
