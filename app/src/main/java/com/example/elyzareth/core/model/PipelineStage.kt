package com.example.elyzareth.core.model

/**
 * Fundamental pipeline stages of the Elyzareth OS lifecycle.
 *
 * Core flow:
 * IDEA → LYRIC → EVIDENCE → CURATION → CREATIVE DNA → MUSIC STYLE / VISUAL DIRECTION
 * → PRODUCTION → FORENSIC VERIFICATION → G1 → G2 → G3 → G4 → G5 RELEASE LOCK → MASTER PLAYER
 *
 * Invariant: Human remains the final creative decision maker at every transition.
 */
enum class PipelineStage(
    val stageIndex: Int,
    val displayName: String,
    val codeName: String,
    val description: String,
    val requiresHumanDecision: Boolean
) {
    IDEA(
        stageIndex = 0,
        displayName = "Idea Inception",
        codeName = "STAGE_IDEA",
        description = "Initial creative prompt, theme seed, and thematic conceptualization.",
        requiresHumanDecision = true
    ),
    LYRIC(
        stageIndex = 1,
        displayName = "Lyric Generation",
        codeName = "STAGE_LYRIC",
        description = "AI-assisted or authored lyrical drafts with structural cadence.",
        requiresHumanDecision = false
    ),
    EVIDENCE(
        stageIndex = 2,
        displayName = "Evidence Logging",
        codeName = "STAGE_EVIDENCE",
        description = "Audit trail capturing prompt lineage, drafts, timestamps, and seed provenance.",
        requiresHumanDecision = false
    ),
    CURATION(
        stageIndex = 3,
        displayName = "Lyric Curation",
        codeName = "STAGE_CURATION",
        description = "Line-by-line editorial refinement, semantic pruning, and human endorsement.",
        requiresHumanDecision = true
    ),
    CREATIVE_DNA(
        stageIndex = 4,
        displayName = "Creative DNA Registry",
        codeName = "STAGE_CREATIVE_DNA",
        description = "Identity signature conformance, sonic motifs, and artist boundary validation.",
        requiresHumanDecision = true
    ),
    STYLE_AND_VISUAL(
        stageIndex = 5,
        displayName = "Music Style & Visual Direction",
        codeName = "STAGE_STYLE_VISUAL",
        description = "Tempo, key, instrumentation parameters, moodboard, and visual aesthetic guidelines.",
        requiresHumanDecision = true
    ),
    PRODUCTION(
        stageIndex = 6,
        displayName = "Production Ingest",
        codeName = "STAGE_PRODUCTION",
        description = "Audio rendering, multitrack mixdown, and performance rendering.",
        requiresHumanDecision = false
    ),
    FORENSIC_VERIFICATION(
        stageIndex = 7,
        displayName = "Forensic Scanner",
        codeName = "STAGE_FORENSIC",
        description = "Plagiarism scan, audio watermarking, AI artifact analysis, and voiceprint checks.",
        requiresHumanDecision = false
    ),
    GATE_1_PRE_PRODUCTION(
        stageIndex = 8,
        displayName = "Gate 1: Concept & Lyrics",
        codeName = "GATE_G1",
        description = "Verification of idea viability and curated lyric approval.",
        requiresHumanDecision = true
    ),
    GATE_2_CURATION_APPROVAL(
        stageIndex = 9,
        displayName = "Gate 2: Evidence & Provenance",
        codeName = "GATE_G2",
        description = "Audit of complete evidence chain and zero-plagiarism proof.",
        requiresHumanDecision = true
    ),
    GATE_3_DNA_CONFORMANCE(
        stageIndex = 10,
        displayName = "Gate 3: DNA Conformance",
        codeName = "GATE_G3",
        description = "Validation against artist's Creative DNA rules and banned motifs.",
        requiresHumanDecision = true
    ),
    GATE_4_FORENSIC_CLEARANCE(
        stageIndex = 11,
        displayName = "Gate 4: Forensic Clearance",
        codeName = "GATE_G4",
        description = "Passage of spectral watermarking, acoustic integrity, and similarity thresholds.",
        requiresHumanDecision = true
    ),
    GATE_5_RELEASE_LOCK(
        stageIndex = 12,
        displayName = "Gate 5: Release Lock",
        codeName = "GATE_G5_LOCK",
        description = "Final cryptographic lock of master assets; sealing release metadata.",
        requiresHumanDecision = true
    ),
    MASTER_PLAYER(
        stageIndex = 13,
        displayName = "Master Player",
        codeName = "STAGE_MASTER_PLAYER",
        description = "Authenticated playback and distribution vault for release-locked masters.",
        requiresHumanDecision = false
    );

    val isGate: Boolean
        get() = this in setOf(
            GATE_1_PRE_PRODUCTION,
            GATE_2_CURATION_APPROVAL,
            GATE_3_DNA_CONFORMANCE,
            GATE_4_FORENSIC_CLEARANCE,
            GATE_5_RELEASE_LOCK
        )
}
