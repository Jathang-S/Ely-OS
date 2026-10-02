package com.example.elyzareth.modules.forensic.contract

import com.example.elyzareth.core.model.CuratedLyricArtifact
import com.example.elyzareth.core.model.ForensicScanReport

/**
 * Module Boundary: FORENSIC SCANNER
 *
 * Responsibility:
 * Runs rigorous acoustic, spectral, watermarking, plagiarism, and AI-artifact checks
 * on the production audio mix and lyrics prior to gate clearance.
 *
 * Upstream Dependency: Production Ingest, Curated Lyrics
 * Downstream Dependency: Gate 4 (Forensic Clearance), Gate 5 (Release Lock)
 *
 * NOTE: Architecture Phase Only.
 * TODO: Implement Forensic Scanner in Phase 2 (watermark embedding/detection, audio fingerprinting, copyright database match, acoustic integrity tests).
 */
interface ForensicScannerContract {

    /**
     * Executes deep forensic audit on the production audio asset and lyrical text.
     * TODO: Implement in Phase 2.
     */
    suspend fun executeScan(
        projectId: String,
        audioFileUri: String,
        curatedLyric: CuratedLyricArtifact
    ): Result<ForensicScanReport>

    /**
     * Verifies cryptographic watermark authenticity.
     * TODO: Implement in Phase 2.
     */
    suspend fun verifyWatermark(audioFileUri: String): Result<Boolean>
}
