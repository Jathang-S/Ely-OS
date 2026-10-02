package com.example.elyzareth.shell.navigation

import com.example.elyzareth.core.model.GateLevel
import com.example.elyzareth.core.model.PipelineStage

/**
 * Navigation routes and destinations for the Elyzareth OS Shell.
 * Provides architectural routing across pipeline stages and gate inspectors.
 */
sealed class OsDestination(val route: String, val label: String) {
    data object GovernorDashboard : OsDestination("governor/overview", "Governor Overview")
    data object PipelineInspector : OsDestination("governor/pipeline", "Pipeline Architecture")
    data object StageDetail : OsDestination("governor/stage/{stageName}", "Stage Contract") {
        fun createRoute(stage: PipelineStage): String = "governor/stage/${stage.name}"
    }
    data object GateMatrix : OsDestination("governor/gates", "G1–G5 Gates")
    data object ReleaseVault : OsDestination("governor/vault", "Release Lock Vault")

    companion object {
        val rootDestinations = listOf(
            GovernorDashboard,
            PipelineInspector,
            GateMatrix,
            ReleaseVault
        )
    }
}
