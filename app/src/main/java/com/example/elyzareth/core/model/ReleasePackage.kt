package com.example.elyzareth.core.model

/**
 * The immutable release package locked by Gate 5.
 * Only artifacts that have completed G1-G5 release locking can be mounted into Master Player.
 */
data class ReleasePackage(
    val releaseId: String,
    val projectId: String,
    val trackTitle: String,
    val artistName: String,
    val masterAudioUri: String,
    val masterAudioSha256: String,
    val finalCuratedLyricsId: String,
    val visualArtworkUri: String?,
    val forensicReportId: String,
    val gate5LockTimestamp: Long,
    val humanReleaseAuthoritySignature: String,
    val isReleaseLocked: Boolean = true
)
