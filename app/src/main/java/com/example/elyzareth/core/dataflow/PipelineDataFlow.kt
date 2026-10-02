package com.example.elyzareth.core.dataflow

import com.example.elyzareth.core.model.CreativeDnaProfile
import com.example.elyzareth.core.model.CuratedLyricArtifact
import com.example.elyzareth.core.model.EvidenceRecord
import com.example.elyzareth.core.model.ForensicScanReport
import com.example.elyzareth.core.model.GateLevel
import com.example.elyzareth.core.model.LyricArtifact
import com.example.elyzareth.core.model.MusicStyleSpec
import com.example.elyzareth.core.model.PipelineStage
import com.example.elyzareth.core.model.ReleasePackage
import com.example.elyzareth.core.model.VisualGuideSpec

/**
 * Architectural specification of Data-Flow relationships between Elyzareth OS modules.
 *
 * Core Data-Flow Pipeline:
 * [1] IDEA
 *     ↓ (Prompt seed, themes)
 * [2] LYRIC GENERATOR
 *     ↓ (LyricArtifact drafts)
 * [3] EVIDENCE LOGGER
 *     ↓ (Immutable EvidenceRecord)
 * [4] LYRIC CURATOR
 *     ↓ (CuratedLyricArtifact with human endorsement)
 * [5] CREATIVE DNA REGISTRY
 *     ↓ (Validates lyrics against CreativeDnaProfile)
 * [6] MUSIC STYLE & VISUAL GUIDE
 *     ↓ (MusicStyleSpec, VisualGuideSpec)
 * [7] PRODUCTION INGEST
 *     ↓ (Master audio multitrack render)
 * [8] FORENSIC SCANNER
 *     ↓ (ForensicScanReport: Plagiarism, Watermarks, AI Artifacts)
 * [9] VERIFICATION GATES G1 → G2 → G3 → G4 → G5 (RELEASE LOCK)
 *     ↓ (ReleasePackage sealed)
 * [10] MASTER PLAYER
 */
sealed interface PipelineDataFlowDescriptor {
    val stage: PipelineStage
    val inputDescription: String
    val outputDescription: String
    val upstreamPrerequisites: List<PipelineStage>
    val downstreamConsumers: List<PipelineStage>
}

object IdeaDataFlow : PipelineDataFlowDescriptor {
    override val stage = PipelineStage.IDEA
    override val inputDescription = "Human artistic seed, prompt, mood, conceptual themes"
    override val outputDescription = "Validated project genesis concept"
    override val upstreamPrerequisites = emptyList<PipelineStage>()
    override val downstreamConsumers = listOf(PipelineStage.LYRIC, PipelineStage.EVIDENCE)
}

object LyricGenerationDataFlow : PipelineDataFlowDescriptor {
    override val stage = PipelineStage.LYRIC
    override val inputDescription = "Idea concept + Creative DNA thematic guidelines"
    override val outputDescription = "LyricArtifact (raw drafts, stanzas, cadence metadata)"
    override val upstreamPrerequisites = listOf(PipelineStage.IDEA)
    override val downstreamConsumers = listOf(PipelineStage.EVIDENCE, PipelineStage.CURATION)
}

object EvidenceDataFlow : PipelineDataFlowDescriptor {
    override val stage = PipelineStage.EVIDENCE
    override val inputDescription = "Prompt hashes, model version, timestamp, raw outputs"
    override val outputDescription = "Immutable EvidenceRecord chain for provenance audit"
    override val upstreamPrerequisites = listOf(PipelineStage.IDEA, PipelineStage.LYRIC)
    override val downstreamConsumers = listOf(PipelineStage.CURATION, PipelineStage.GATE_2_CURATION_APPROVAL)
}

object CurationDataFlow : PipelineDataFlowDescriptor {
    override val stage = PipelineStage.CURATION
    override val inputDescription = "LyricArtifact drafts + Evidence audit trail"
    override val outputDescription = "CuratedLyricArtifact (Human-edited, line-by-line endorsed)"
    override val upstreamPrerequisites = listOf(PipelineStage.LYRIC, PipelineStage.EVIDENCE)
    override val downstreamConsumers = listOf(PipelineStage.CREATIVE_DNA, PipelineStage.GATE_1_PRE_PRODUCTION)
}

object CreativeDnaDataFlow : PipelineDataFlowDescriptor {
    override val stage = PipelineStage.CREATIVE_DNA
    override val inputDescription = "CuratedLyricArtifact + Artist CreativeDnaProfile"
    override val outputDescription = "DNA Conformance Assessment & Sonic Constraints"
    override val upstreamPrerequisites = listOf(PipelineStage.CURATION)
    override val downstreamConsumers = listOf(PipelineStage.STYLE_AND_VISUAL, PipelineStage.GATE_3_DNA_CONFORMANCE)
}

object StyleAndVisualDataFlow : PipelineDataFlowDescriptor {
    override val stage = PipelineStage.STYLE_AND_VISUAL
    override val inputDescription = "CreativeDnaProfile + CuratedLyric themes"
    override val outputDescription = "MusicStyleSpec (tempo, key, arrangement) & VisualGuideSpec (palette, mood)"
    override val upstreamPrerequisites = listOf(PipelineStage.CREATIVE_DNA)
    override val downstreamConsumers = listOf(PipelineStage.PRODUCTION)
}

object ProductionDataFlow : PipelineDataFlowDescriptor {
    override val stage = PipelineStage.PRODUCTION
    override val inputDescription = "MusicStyleSpec + CuratedLyricArtifact + Vocal guide"
    override val outputDescription = "Master Audio Mix & Stems"
    override val upstreamPrerequisites = listOf(PipelineStage.STYLE_AND_VISUAL)
    override val downstreamConsumers = listOf(PipelineStage.FORENSIC_VERIFICATION)
}

object ForensicScannerDataFlow : PipelineDataFlowDescriptor {
    override val stage = PipelineStage.FORENSIC_VERIFICATION
    override val inputDescription = "Production Audio Asset + Curated Lyrics"
    override val outputDescription = "ForensicScanReport (watermark check, similarity %, AI artifact score)"
    override val upstreamPrerequisites = listOf(PipelineStage.PRODUCTION)
    override val downstreamConsumers = listOf(PipelineStage.GATE_4_FORENSIC_CLEARANCE)
}

object GateVerificationDataFlow : PipelineDataFlowDescriptor {
    override val stage = PipelineStage.GATE_1_PRE_PRODUCTION
    override val inputDescription = "Artifacts and evidence from corresponding upstream stages"
    override val outputDescription = "Signed GateVerificationRecord for G1..G5"
    override val upstreamPrerequisites = listOf(PipelineStage.FORENSIC_VERIFICATION)
    override val downstreamConsumers = listOf(PipelineStage.GATE_5_RELEASE_LOCK)
}

object ReleaseLockDataFlow : PipelineDataFlowDescriptor {
    override val stage = PipelineStage.GATE_5_RELEASE_LOCK
    override val inputDescription = "Approved G1..G4 records, Master Audio SHA-256, human sign-off"
    override val outputDescription = "Sealed ReleasePackage; all modifications locked"
    override val upstreamPrerequisites = listOf(
        PipelineStage.GATE_1_PRE_PRODUCTION,
        PipelineStage.GATE_2_CURATION_APPROVAL,
        PipelineStage.GATE_3_DNA_CONFORMANCE,
        PipelineStage.GATE_4_FORENSIC_CLEARANCE
    )
    override val downstreamConsumers = listOf(PipelineStage.MASTER_PLAYER)
}

object MasterPlayerDataFlow : PipelineDataFlowDescriptor {
    override val stage = PipelineStage.MASTER_PLAYER
    override val inputDescription = "Sealed ReleasePackage with verified G5 Lock"
    override val outputDescription = "Controlled high-fidelity playback and verified master export"
    override val upstreamPrerequisites = listOf(PipelineStage.GATE_5_RELEASE_LOCK)
    override val downstreamConsumers = emptyList<PipelineStage>()
}
