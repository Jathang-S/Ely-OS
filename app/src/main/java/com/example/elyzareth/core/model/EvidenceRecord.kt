package com.example.elyzareth.core.model

/**
 * Immutable log record capturing provenance, generation prompts, and human decisions
 * during the Elyzareth OS pipeline execution.
 */
data class EvidenceRecord(
    val id: String,
    val projectId: String,
    val stage: PipelineStage,
    val timestampMillis: Long,
    val authorOrActor: String,
    val eventType: EvidenceEventType,
    val payloadHash: String,
    val summary: String,
    val metadata: Map<String, String> = emptyMap()
)

enum class EvidenceEventType {
    PROMPT_ISSUED,
    GENERATION_RECEIVED,
    HUMAN_REVISED,
    HUMAN_APPROVED,
    HUMAN_REJECTED,
    HUMAN_VETOED,
    STAGE_TRANSITIONED,
    SCAN_COMPLETED,
    GATE_EVALUATED,
    RELEASE_SEALED
}
