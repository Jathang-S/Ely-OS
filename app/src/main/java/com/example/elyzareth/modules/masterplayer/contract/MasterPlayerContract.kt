package com.example.elyzareth.modules.masterplayer.contract

import com.example.elyzareth.core.model.ReleasePackage

/**
 * Module Boundary: MASTER PLAYER / RELEASE LOCK
 *
 * Responsibility:
 * Secure audio player and release distribution manager.
 * Invariant: Master Player is strictly LOCKED unless the project has passed Gate 5
 * and possesses an authentic, cryptographically verified ReleasePackage.
 *
 * Upstream Dependency: Gate 5 (Release Lock), Production Master Asset
 * Downstream Dependency: Release Distribution, Archival Vault
 *
 * NOTE: Architecture Phase Only.
 * TODO: Implement Master Player in Phase 2 (secure ExoPlayer integration, waveform visualizer, master stem playback, package export).
 */
interface MasterPlayerContract {

    /**
     * Checks if release lock allows playback of the given package.
     */
    fun isPackagePlayable(releasePackage: ReleasePackage?): Boolean

    /**
     * Mounts verified release package for secure master playback.
     * Throws SecurityException / IllegalStateException if Gate 5 lock is invalid.
     * TODO: Implement in Phase 2.
     */
    suspend fun mountReleasePackage(releasePackage: ReleasePackage): Result<PlaybackSessionInfo>

    /**
     * Initiates playback.
     * TODO: Implement in Phase 2.
     */
    fun play()

    /**
     * Pauses playback.
     * TODO: Implement in Phase 2.
     */
    fun pause()

    /**
     * Releases playback resources.
     * TODO: Implement in Phase 2.
     */
    fun release()
}

data class PlaybackSessionInfo(
    val releaseId: String,
    val title: String,
    val durationMs: Long,
    val isCryptographicallyVerified: Boolean
)
