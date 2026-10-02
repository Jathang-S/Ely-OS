package com.example.elyzareth.modules.creativedna.contract

import com.example.elyzareth.core.model.CreativeDnaProfile
import com.example.elyzareth.core.model.CuratedLyricArtifact

/**
 * Module Boundary: CREATIVE DNA REGISTRY
 *
 * Responsibility:
 * Maintains the canonical repository of artist/project creative identities.
 * Evaluates lyrics, arrangements, and prompts against forbidden words, sonic rules,
 * and aesthetic signatures.
 *
 * Upstream Dependency: Human Art Director / Artist Profile
 * Downstream Dependency: Lyric Generator, Music Style Guide, Gate 3 Verification
 *
 * NOTE: Architecture Phase Only.
 * TODO: Implement Creative DNA Registry in Phase 2 (profile management, rule evaluator, brand tone compliance scoring).
 */
interface CreativeDnaContract {

    /**
     * Retrieves the active Creative DNA profile for a project.
     * TODO: Implement in Phase 2.
     */
    suspend fun getProfile(profileId: String): Result<CreativeDnaProfile>

    /**
     * Validates curated lyrics against DNA rules (forbidden tropes, vocabulary, themes).
     * TODO: Implement in Phase 2.
     */
    suspend fun validateLyricCompliance(
        profileId: String,
        curatedLyric: CuratedLyricArtifact
    ): Result<DnaComplianceResult>

    /**
     * Updates and locks the DNA profile by authorized authority.
     * TODO: Implement in Phase 2.
     */
    suspend fun registerProfile(profile: CreativeDnaProfile): Result<Unit>
}

data class DnaComplianceResult(
    val conforms: Boolean,
    val score: Float,
    val matchedMotifs: List<String>,
    val detectedViolations: List<String>
)
