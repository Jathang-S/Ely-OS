package com.example.elyzareth.core.model

/**
 * Technical specification for the musical style, arrangement, and acoustic target
 * derived from Creative DNA and curated lyrics.
 */
data class MusicStyleSpec(
    val id: String,
    val projectId: String,
    val genreTags: List<String>,
    val targetBpm: Int,
    val keySignature: String,
    val timeSignature: String,
    val sonicTextureDescription: String,
    val arrangementStructure: List<String>,
    val productionDirectives: List<String>,
    val humanDirectorApproval: Boolean = false
)
