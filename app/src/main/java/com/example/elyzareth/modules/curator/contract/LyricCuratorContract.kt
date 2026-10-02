package com.example.elyzareth.modules.curator.contract

import com.example.elyzareth.core.model.CuratedLyricArtifact
import com.example.elyzareth.core.model.LyricArtifact

/**
 * Module Boundary: LYRIC CURATOR
 *
 * Responsibility:
 * Empowers human editors to review, prune, refine, annotate, and officially endorse lyrics.
 * Converts draft LyricArtifacts into validated CuratedLyricArtifacts.
 *
 * Invariant: Cannot be automated; requires active human curator endorsement.
 * Upstream Dependency: Lyric Generator, Evidence Logger
 * Downstream Dependency: Creative DNA Registry, Music Style Guide, Gate 1
 *
 * NOTE: Architecture Phase Only.
 * TODO: Implement Lyric Curator module in Phase 2 (interactive line editor, rhyming assistant, syllable counter, human sign-off workflow).
 */
interface LyricCuratorContract {

    /**
     * Retrieves draft for curation session.
     * TODO: Implement in Phase 2.
     */
    suspend fun loadDraftForCuration(draftId: String): Result<LyricArtifact>

    /**
     * Saves ongoing editorial revisions.
     * TODO: Implement in Phase 2.
     */
    suspend fun saveCuratedDraft(curatedLyric: CuratedLyricArtifact): Result<Unit>

    /**
     * Submits human final endorsement, locking the lyrics for production.
     * TODO: Implement in Phase 2.
     */
    suspend fun endorseLyrics(
        curatedArtifactId: String,
        curatorUserId: String,
        signOffNotes: String
    ): Result<CuratedLyricArtifact>
}
