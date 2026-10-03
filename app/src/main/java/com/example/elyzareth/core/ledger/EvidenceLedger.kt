package com.example.elyzareth.core.ledger

import com.example.elyzareth.core.model.EvidenceRecord
import com.example.elyzareth.core.model.PipelineStage
import kotlinx.coroutines.flow.StateFlow

/**
 * Shared contract for the Evidence Ledger.
 * Provides an append-only, verifiable record store capturing provenance,
 * generation events, and human decisions throughout the Elyzareth OS pipeline.
 */
interface EvidenceLedger {

    /**
     * Observable stream of all recorded evidence entries.
     */
    val recordsFlow: StateFlow<List<EvidenceRecord>>

    /**
     * Appends an immutable evidence entry to the ledger.
     * Fails if required fields are missing or invalid.
     */
    suspend fun record(evidence: EvidenceRecord): Result<EvidenceRecord>

    /**
     * Retrieves all evidence records associated with a specific project.
     */
    fun getRecordsForProject(projectId: String): List<EvidenceRecord>

    /**
     * Retrieves evidence records for a specific project and pipeline stage.
     */
    fun getRecordsForStage(projectId: String, stage: PipelineStage): List<EvidenceRecord>

    /**
     * Returns the total count of recorded evidence entries.
     */
    fun size(): Int

    /**
     * Clears in-memory ledger records (used for isolated testing environments).
     */
    fun clear()
}
