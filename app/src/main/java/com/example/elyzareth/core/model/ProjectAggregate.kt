package com.example.elyzareth.core.model

/**
 * Root domain aggregate representing an active track or project progressing
 * through the Elyzareth OS pipeline.
 */
data class ProjectAggregate(
    val id: String,
    val title: String,
    val conceptIdea: String,
    val currentStage: PipelineStage,
    val creativeDnaId: String?,
    val activeLyricDraftId: String?,
    val curatedLyricId: String?,
    val musicStyleSpecId: String?,
    val visualGuideSpecId: String?,
    val productionAssetUri: String?,
    val forensicReportId: String?,
    val gateRecords: Map<GateLevel, GateVerificationRecord>,
    val releasePackageId: String?,
    val createdAtTimestamp: Long,
    val lastModifiedTimestamp: Long
)
