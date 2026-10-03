package com.example.elyzareth.core.ledger

import com.example.elyzareth.core.model.EvidenceRecord
import com.example.elyzareth.core.model.PipelineStage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * In-memory implementation of the Evidence Ledger for Baseline 02.
 * Maintains an append-only, thread-safe sequence of EvidenceRecords
 * without committing to a permanent persistence technology.
 */
class InMemoryEvidenceLedger : EvidenceLedger {

    private val mutex = Mutex()
    private val recordsList = mutableListOf<EvidenceRecord>()

    private val _recordsFlow = MutableStateFlow<List<EvidenceRecord>>(emptyList())
    override val recordsFlow: StateFlow<List<EvidenceRecord>> = _recordsFlow.asStateFlow()

    override suspend fun record(evidence: EvidenceRecord): Result<EvidenceRecord> {
        if (evidence.projectId.isBlank()) {
            return Result.failure(IllegalArgumentException("Evidence record must have a non-blank projectId."))
        }
        if (evidence.authorOrActor.isBlank()) {
            return Result.failure(IllegalArgumentException("Evidence record must have a non-blank authorOrActor."))
        }
        if (evidence.summary.isBlank()) {
            return Result.failure(IllegalArgumentException("Evidence record must have a non-blank summary."))
        }

        val finalizedRecord = evidence.copy(
            id = if (evidence.id.isBlank()) UUID.randomUUID().toString() else evidence.id,
            timestampMillis = if (evidence.timestampMillis <= 0L) System.currentTimeMillis() else evidence.timestampMillis
        )

        mutex.withLock {
            recordsList.add(finalizedRecord)
            _recordsFlow.value = recordsList.toList()
        }

        return Result.success(finalizedRecord)
    }

    override fun getRecordsForProject(projectId: String): List<EvidenceRecord> {
        return _recordsFlow.value.filter { it.projectId == projectId }
    }

    override fun getRecordsForStage(projectId: String, stage: PipelineStage): List<EvidenceRecord> {
        return _recordsFlow.value.filter { it.projectId == projectId && it.stage == stage }
    }

    override fun size(): Int {
        return _recordsFlow.value.size
    }

    override fun clear() {
        recordsList.clear()
        _recordsFlow.value = emptyList()
    }
}
