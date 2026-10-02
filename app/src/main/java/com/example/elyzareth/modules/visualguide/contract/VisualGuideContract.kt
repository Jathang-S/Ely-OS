package com.example.elyzareth.modules.visualguide.contract

import com.example.elyzareth.core.model.CuratedLyricArtifact
import com.example.elyzareth.core.model.MusicStyleSpec
import com.example.elyzareth.core.model.VisualGuideSpec

/**
 * Module Boundary: VISUAL GUIDE
 *
 * Responsibility:
 * Dictates aesthetic identity, typography guidelines, moodboards, color schemes,
 * and artwork directives aligned with the musical composition and lyrical tone.
 *
 * Upstream Dependency: Music Style Guide, Lyric Curator, Creative DNA
 * Downstream Dependency: Release Packaging, Gate 3 Conformance
 *
 * NOTE: Architecture Phase Only.
 * TODO: Implement Visual Guide in Phase 2 (moodboard generation, color extraction, prompt craft for cover art, design system checks).
 */
interface VisualGuideContract {

    /**
     * Synthesizes visual direction spec based on audio and lyrical parameters.
     * TODO: Implement in Phase 2.
     */
    suspend fun generateVisualSpec(
        curatedLyric: CuratedLyricArtifact,
        musicSpec: MusicStyleSpec
    ): Result<VisualGuideSpec>

    /**
     * Updates and saves approved visual aesthetic guidelines.
     * TODO: Implement in Phase 2.
     */
    suspend fun approveVisualSpec(spec: VisualGuideSpec, artDirectorId: String): Result<Unit>
}
