package com.example.elyzareth.modules.lyricgenerator.contract

import com.example.elyzareth.core.model.CreativeDnaProfile
import com.example.elyzareth.core.model.LyricArtifact

/**
 * Module Boundary: LYRIC GENERATOR
 *
 * Responsibility:
 * Ingests thematic prompt concepts and Creative DNA constraints to generate
 * lyrical drafts with rhyme schemes, stanzas, and cadence annotations.
 *
 * Upstream Dependency: Idea Inception, Creative DNA Registry
 * Downstream Dependency: Evidence Logger, Lyric Curator
 *
 * NOTE: Architecture Phase Only.
 * TODO: Implement Lyric Generator engine in Phase 2 (LLM/Gemini integration, prompt templates, multi-draft generation).
 */
interface LyricGeneratorContract {

    /**
     * Generates a lyrical draft artifact based on prompt and optional DNA profile.
     * TODO: Implement generation pipeline in Phase 2.
     */
    suspend fun generateLyrics(
        projectId: String,
        conceptPrompt: String,
        dnaProfile: CreativeDnaProfile?
    ): Result<LyricArtifact>

    /**
     * Lists generated drafts for a specific project.
     * TODO: Implement draft history retrieval in Phase 2.
     */
    suspend fun getDraftsForProject(projectId: String): List<LyricArtifact>
}
