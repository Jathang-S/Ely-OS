package com.example.elyzareth.core.model

/**
 * Gate definitions for the G1 through G5 verification progression in Elyzareth OS.
 * Every gate strictly requires human review and sign-off before unlocking the next tier.
 */
enum class GateLevel(
    val code: String,
    val title: String,
    val purpose: String
) {
    G1("G1", "Concept & Curated Lyrics", "Verifies idea genesis, prompt log, and curated lyrics approval."),
    G2("G2", "Provenance & Evidence Chain", "Verifies complete audit trail and zero unauthorized content."),
    G3("G3", "Creative DNA Conformance", "Verifies compliance with sonic, lyrical, and brand identity rules."),
    G4("G4", "Forensic Clearance", "Verifies watermark validation and spectral/plagiarism safety."),
    G5("G5", "Release Lock", "Final sealing of production master; locks all artifacts irreversibly.")
}

enum class GateStatus {
    LOCKED,
    PENDING_EVALUATION,
    HUMAN_REVIEW_REQUIRED,
    APPROVED,
    REJECTED
}

data class GateVerificationRecord(
    val gate: GateLevel,
    val status: GateStatus,
    val criteriaResults: List<GateCriterionCheck>,
    val humanDecision: HumanSignOff? = null,
    val evaluatedTimestamp: Long? = null
)

data class GateCriterionCheck(
    val id: String,
    val label: String,
    val satisfied: Boolean,
    val explanation: String
)
