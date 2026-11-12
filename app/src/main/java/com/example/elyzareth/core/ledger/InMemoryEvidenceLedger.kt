package com.example.elyzareth.core.ledger

import com.example.elyzareth.core.model.EvidenceRecord
import com.example.elyzareth.core.model.GENESIS_RECORD_HASH
import com.example.elyzareth.core.model.PipelineStage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.ArrayList
import java.util.Collections
import java.util.HashSet
import java.util.LinkedHashMap

/**
 * In-memory implementation of the Evidence Ledger for Baseline 02 (Phase 0).
 *
 * Explicit Phase 0 Limitations:
 * - In-memory hash chain only; `_recordsFlow.value` is the sole authoritative ledger collection.
 * - Append-oriented ledger with integrity verification during the process lifetime.
 * - Provides no durable persistence guarantee and no crash-recovery guarantee.
 * - Does not protect against replacement of the entire in-memory ledger instance.
 * - Room persistence and crash recovery belong to Phase 1 (KERNEL_1.0).
 */
class InMemoryEvidenceLedger : EvidenceLedger {

    private val mutex = Mutex()

    private val _recordsFlow =
        MutableStateFlow<List<EvidenceRecord>>(Collections.emptyList())

    override val recordsFlow: StateFlow<List<EvidenceRecord>> =
        _recordsFlow.asStateFlow()

    override suspend fun record(
        evidence: EvidenceRecord
    ): Result<EvidenceRecord> {

        // Validate caller-owned fields before entering the commit section.
        val validation = validateEvidence(evidence)
        if (validation.isFailure) {
            return Result.failure(
                validation.exceptionOrNull()
                    ?: IllegalArgumentException("Invalid evidence")
            )
        }

        return mutex.withLock {

            val currentSnapshot = _recordsFlow.value

            // Unique ID check against the authoritative snapshot.
            if (currentSnapshot.any { it.id == evidence.id }) {
                return@withLock Result.failure(
                    IllegalStateException(
                        "Duplicate EvidenceRecord ID: ${evidence.id}"
                    )
                )
            }

            val expectedPreviousHash =
                currentSnapshot.lastOrNull()?.recordHash
                    ?: GENESIS_RECORD_HASH

            // Validate external previousHash BEFORE deriving it.
            if (
                evidence.previousHash.isNotEmpty() &&
                evidence.previousHash != expectedPreviousHash
            ) {
                return@withLock Result.failure(
                    IllegalStateException(
                        "External previousHash mismatch"
                    )
                )
            }

            val frozenMetadata =
                Collections.unmodifiableMap(
                    LinkedHashMap(evidence.metadata)
                )

            val candidate = evidence.copy(
                previousHash = expectedPreviousHash,
                recordHash = "",
                metadata = frozenMetadata
            )

            // Derive canonical record hash.
            val derivedHash = try {
                candidate.computeRecordHash()
            } catch (e: Exception) {
                return@withLock Result.failure(
                    IllegalArgumentException("Failed to compute canonical record hash", e)
                )
            }

            // Validate external recordHash BEFORE committing.
            if (
                evidence.recordHash.isNotEmpty() &&
                evidence.recordHash != derivedHash
            ) {
                return@withLock Result.failure(
                    IllegalStateException(
                        "External recordHash mismatch"
                    )
                )
            }

            val committedRecord = candidate.copy(
                recordHash = derivedHash,
                metadata = Collections.unmodifiableMap(
                    LinkedHashMap(candidate.metadata)
                )
            )

            // PREPARE complete next snapshot BEFORE publication.
            val nextSnapshot =
                Collections.unmodifiableList(
                    ArrayList(currentSnapshot).apply {
                        add(committedRecord)
                    }
                )

            // SINGLE authoritative ledger publication.
            _recordsFlow.value = nextSnapshot

            Result.success(committedRecord)
        }
    }

    override fun verifyChainIntegrity(): Boolean {
        val snapshot = _recordsFlow.value

        val seenIds = HashSet<String>(snapshot.size)
        var expectedPreviousHash = GENESIS_RECORD_HASH

        for (record in snapshot) {

            if (record.id.isBlank()) return false
            if (!seenIds.add(record.id)) return false

            if (record.projectId.isBlank()) return false
            if (record.actorId.isBlank()) return false
            if (record.actorId != record.authorOrActor) return false

            if (record.timestampMillis <= 0L) return false

            if (!SHA256_HEX_REGEX.matches(record.payloadHash)) {
                return false
            }

            if (!SHA256_HEX_REGEX.matches(record.recordHash)) {
                return false
            }

            if (record.previousHash != expectedPreviousHash) {
                return false
            }

            val computedHash = try {
                record.computeRecordHash()
            } catch (_: Exception) {
                return false
            }

            if (record.recordHash != computedHash) {
                return false
            }

            expectedPreviousHash = record.recordHash
        }

        return true
    }

    override fun getLatestRecordHash(): String =
        _recordsFlow.value.lastOrNull()?.recordHash
            ?: GENESIS_RECORD_HASH

    override fun getRecordsForProject(
        projectId: String
    ): List<EvidenceRecord> =
        immutableSnapshot(
            _recordsFlow.value.filter {
                it.projectId == projectId
            }
        )

    override fun getRecordsForStage(
        projectId: String,
        stage: PipelineStage
    ): List<EvidenceRecord> =
        immutableSnapshot(
            _recordsFlow.value.filter {
                it.projectId == projectId &&
                    it.stage == stage
            }
        )

    override fun size(): Int = _recordsFlow.value.size

    private fun immutableSnapshot(
        records: List<EvidenceRecord>
    ): List<EvidenceRecord> =
        Collections.unmodifiableList(ArrayList(records))

    private fun validateEvidence(
        evidence: EvidenceRecord
    ): Result<Unit> {

        if (evidence.id.isBlank()) {
            return Result.failure(
                IllegalArgumentException("Blank evidence ID")
            )
        }

        if (evidence.projectId.isBlank()) {
            return Result.failure(
                IllegalArgumentException("Blank project ID")
            )
        }

        if (evidence.actorId.isBlank() ||
            evidence.authorOrActor.isBlank()
        ) {
            return Result.failure(
                IllegalArgumentException("Blank actor identity")
            )
        }

        if (evidence.actorId != evidence.authorOrActor) {
            return Result.failure(
                IllegalArgumentException("Actor identity mismatch")
            )
        }

        if (evidence.summary.isBlank()) {
            return Result.failure(
                IllegalArgumentException("Blank evidence summary")
            )
        }

        if (evidence.timestampMillis <= 0L) {
            return Result.failure(
                IllegalArgumentException("Invalid timestamp")
            )
        }

        if (evidence.parentEventId != null &&
            evidence.parentEventId.isBlank()
        ) {
            return Result.failure(
                IllegalArgumentException("Blank parent event ID")
            )
        }

        if (!SHA256_HEX_REGEX.matches(evidence.payloadHash)) {
            return Result.failure(
                IllegalArgumentException("Invalid payload SHA-256")
            )
        }

        if (evidence.signature != null &&
            evidence.signature.isBlank()
        ) {
            return Result.failure(
                IllegalArgumentException("Blank signature")
            )
        }

        // Validate strict UTF-8 / UTF-16 well-formedness across all string fields before entering the commit section.
        try {
            evidence.recordEnvelope()
        } catch (e: Exception) {
            return Result.failure(
                IllegalArgumentException("Malformed UTF-16 in evidence record", e)
            )
        }

        return Result.success(Unit)
    }

    companion object {
        private val SHA256_HEX_REGEX =
            Regex("^[0-9a-f]{64}$")
    }
}
