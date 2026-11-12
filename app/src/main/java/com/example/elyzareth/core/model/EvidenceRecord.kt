package com.example.elyzareth.core.model

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * Single genesis record hash constant (64 lowercase hexadecimal zeros) anchoring the head
 * of an EvidenceLedger chain.
 */
const val GENESIS_RECORD_HASH: String =
    "0000000000000000000000000000000000000000000000000000000000000000"

/**
 * Constitutional actor classification (Master Blueprint Section 7 & Section 10).
 */
enum class ActorType {
    HUMAN,
    SYSTEM,
    AI
}

/**
 * Declared actor role classification (Master Blueprint Section 10).
 */
enum class ActorRole {
    HUMAN_CREATOR,
    HUMAN_CURATOR,
    HUMAN_AUDITOR,
    AI_GENERATOR,
    AI_ANALYZER,
    PRODUCTION_ADAPTER,
    GOVERNOR_ENGINE,
    MASTER_PLAYER
}

/**
 * Four constitutional evidence classes (Master Blueprint Section 7).
 */
enum class EvidenceClass {
    ORIGINAL,
    DERIVED,
    ANALYTICAL,
    AUTHORITY
}

/**
 * Immutable log record capturing provenance, generation prompts, and human decisions
 * during the Elyzareth OS pipeline execution.
 *
 * Phase 0 Note:
 * - Provides deterministic, length-prefixed UTF-8 canonical serialization and SHA-256 record hashing.
 * - Preserves exact Unicode sequences supplied by the caller without silent NFC/NFD normalization,
 *   and rejects malformed UTF-16 (including unpaired surrogates) via strict UTF-8 encoding.
 */
data class EvidenceRecord(
    val id: String,
    val projectId: String,
    val stage: PipelineStage,
    val timestampMillis: Long,
    val authorOrActor: String,
    val eventType: EvidenceEventType,
    val payloadHash: String,
    val summary: String,
    val metadata: Map<String, String> = emptyMap(),
    val artifactId: String? = null,
    val actorId: String = authorOrActor,
    val actorType: ActorType = ActorType.SYSTEM,
    val evidenceClass: EvidenceClass = EvidenceClass.ANALYTICAL,
    val parentEventId: String? = null,
    val previousHash: String = "",
    val signature: String? = null,
    val recordHash: String = ""
) {

    private fun strictUtf8Bytes(value: String): ByteArray {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(
                CodingErrorAction.REPORT
            )
            .onUnmappableCharacter(
                CodingErrorAction.REPORT
            )

        val buffer = encoder.encode(
            CharBuffer.wrap(value)
        )

        return ByteArray(buffer.remaining()).also {
            buffer.get(it)
        }
    }

    private fun encodeRequiredField(
        name: String,
        value: String
    ): String {
        val length = strictUtf8Bytes(value).size

        return "$name=VAL:$length:$value;"
    }

    private fun encodeNullableField(
        name: String,
        value: String?
    ): String {
        if (value == null) {
            return "$name=NULL;"
        }

        return encodeRequiredField(name, value)
    }

    private fun encodeMetadataMap(
        metadata: Map<String, String>
    ): String {
        val sortedEntries = metadata.entries.sortedBy {
            it.key
        }

        return buildString {
            append("metadata=MAP:${sortedEntries.size}:{")

            for ((key, value) in sortedEntries) {
                val keyLength = strictUtf8Bytes(key).size
                val valueLength = strictUtf8Bytes(value).size

                append("$keyLength:$key=$valueLength:$value;")
            }

            append("};")
        }
    }

    fun signableEnvelope(): String = buildString {
        append("ELYZARETH_EVIDENCE_V1;")

        append(encodeRequiredField("id", id))
        append(encodeRequiredField("projectId", projectId))
        append(encodeRequiredField("stage", stage.name))

        append(encodeNullableField("artifactId", artifactId))

        append(encodeRequiredField("actorId", actorId))
        append(encodeRequiredField("actorType", actorType.name))
        append(encodeRequiredField("evidenceClass", evidenceClass.name))
        append(encodeRequiredField("eventType", eventType.name))

        append(
            encodeRequiredField(
                "timestampMillis",
                timestampMillis.toString()
            )
        )

        append(encodeNullableField("parentEventId", parentEventId))

        append(encodeRequiredField("payloadHash", payloadHash))
        append(encodeRequiredField("previousHash", previousHash))
        append(encodeRequiredField("summary", summary))

        append(encodeMetadataMap(metadata))
    }

    fun recordEnvelope(): String =
        signableEnvelope() +
            encodeNullableField("signature", signature)

    fun computeRecordHash(): String {
        val bytes = strictUtf8Bytes(recordEnvelope())

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)

        val hex = "0123456789abcdef"

        return buildString(digest.size * 2) {
            for (byte in digest) {
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }
}

enum class EvidenceEventType {
    PROMPT_ISSUED,
    GENERATION_RECEIVED,
    HUMAN_REVISED,
    HUMAN_APPROVED,
    HUMAN_REJECTED,
    HUMAN_VETOED,
    STAGE_TRANSITIONED,
    SCAN_COMPLETED,
    GATE_EVALUATED,
    RELEASE_SEALED
}
