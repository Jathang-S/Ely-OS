package com.example.elyzareth.modules.musicstyle.contract

import com.example.elyzareth.core.model.CreativeDnaProfile
import com.example.elyzareth.core.model.CuratedLyricArtifact
import com.example.elyzareth.core.model.MusicStyleSpec

/**
 * Module Boundary: MUSIC STYLE GUIDE
 *
 * Responsibility:
 * Formulates the musical blueprint (BPM, key, arrangement structure, instrumentation directives,
 * mixing acoustic targets) harmonized with the Creative DNA and curated lyrics.
 *
 * Upstream Dependency: Creative DNA Registry, Lyric Curator
 * Downstream Dependency: Production Ingest, Visual Guide, Gate 3/4
 *
 * NOTE: Architecture Phase Only.
 * TODO: Implement Music Style Guide in Phase 2 (arrangement template engine, harmonic compatibility analyzer, style export).
 */
interface MusicStyleContract {

    /**
     * Synthesizes a MusicStyleSpec matching the curated lyrics and DNA guidelines.
     * TODO: Implement in Phase 2.
     */
    suspend fun generateStyleSpec(
        curatedLyric: CuratedLyricArtifact,
        dnaProfile: CreativeDnaProfile
    ): Result<MusicStyleSpec>

    /**
     * Human musical director reviews and modifies style parameters.
     * TODO: Implement in Phase 2.
     */
    suspend fun updateStyleSpec(spec: MusicStyleSpec): Result<Unit>
}
