package com.example.elyzareth.core.model

/**
 * Lyric artifact generated during the Lyric Generation phase.
 * Represents draft iterations prior to human curation.
 */
data class LyricArtifact(
    val id: String,
    val projectId: String,
    val draftNumber: Int,
    val titleSuggestion: String,
    val sections: List<LyricSection>,
    val thematicKeywords: List<String>,
    val cadenceSummary: String,
    val generatedTimestamp: Long,
    val generatorModelRef: String
)

data class LyricSection(
    val type: LyricSectionType,
    val lines: List<String>,
    val rhymeScheme: String? = null,
    val emotionalIntensity: Float = 0.5f
)

enum class LyricSectionType {
    INTRO,
    VERSE,
    PRE_CHORUS,
    CHORUS,
    BRIDGE,
    OUTRO,
    SPOKEN_WORD
}
