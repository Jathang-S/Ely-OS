package com.example.elyzareth.core.model

/**
 * Curated Lyric Artifact representing human-reviewed, edited, and approved lyrical text.
 * Requires human sign-off to proceed through to Creative DNA and Gate 1 verification.
 */
data class CuratedLyricArtifact(
    val id: String,
    val sourceArtifactId: String,
    val projectId: String,
    val finalTitle: String,
    val sections: List<CuratedSection>,
    val humanEditorNotes: String,
    val humanCuratorId: String,
    val approvedAtTimestamp: Long,
    val isLockedForProduction: Boolean = false
)

data class CuratedSection(
    val type: LyricSectionType,
    val approvedLines: List<CuratedLine>
)

data class CuratedLine(
    val text: String,
    val originalDraftText: String?,
    val wasHumanModified: Boolean,
    val editorialRationale: String? = null
)
