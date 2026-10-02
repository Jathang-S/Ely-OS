package com.example.elyzareth.core.model

/**
 * Visual guide specification detailing aesthetic branding, typography, color harmony,
 * and moodboard anchors for the release.
 */
data class VisualGuideSpec(
    val id: String,
    val projectId: String,
    val primaryColorPaletteHex: List<String>,
    val moodKeywords: List<String>,
    val coverArtStyleDirectives: String,
    val typographyDirectives: String,
    val lightingAndAtmosphere: String,
    val humanArtDirectorApproval: Boolean = false
)
