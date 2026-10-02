package com.example.elyzareth.core.model

/**
 * Creative DNA represents the inviolable aesthetic identity of the project or artist.
 * Defines sonic motifs, lyrical themes, forbidden patterns, and vocal profiles.
 */
data class CreativeDnaProfile(
    val id: String,
    val artistOrProjectName: String,
    val version: String,
    val coreThematicMotifs: List<String>,
    val forbiddenPhrasesAndTropes: List<String>,
    val sonicAnchors: SonicAnchors,
    val vocalToneCharacteristics: List<String>,
    val philosophicalDirectives: List<String>,
    val lockedByHumanAuthority: Boolean = true
)

data class SonicAnchors(
    val primaryGenres: List<String>,
    val preferredKeySignatures: List<String>,
    val tempoRangeBpm: IntRange,
    val dynamicRangeDb: ClosedFloatingPointRange<Float>,
    val instrumentationRules: List<String>
)
