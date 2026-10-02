package com.example.elyzareth.shell.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.elyzareth.core.model.PipelineStage
import com.example.elyzareth.shell.navigation.OsDestination
import com.example.elyzareth.shell.ui.components.GateStatusMatrix
import com.example.elyzareth.shell.ui.components.OsHeader
import com.example.elyzareth.shell.ui.components.PipelineStageRail
import com.example.elyzareth.shell.ui.components.StageContractCard
import com.example.elyzareth.shell.viewmodel.GovernorShellViewModel

/**
 * Top-level Governor Shell for Elyzareth OS.
 * Serves as the architectural inspection interface exposing module boundaries,
 * data flow contracts, verification gate matrix, and human decision checkpoints.
 */
@Composable
fun ElyzarethOsShell(
    modifier: Modifier = Modifier,
    viewModel: GovernorShellViewModel = viewModel()
) {
    val pipelineState by viewModel.pipelineState.collectAsState()
    val selectedStage by viewModel.selectedStage.collectAsState()
    var currentTab by remember { mutableStateOf<OsDestination>(OsDestination.GovernorDashboard) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("elyzareth_os_shell"),
        bottomBar = {
            NavigationBar(
                modifier = Modifier.testTag("os_bottom_nav"),
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                NavigationBarItem(
                    selected = currentTab == OsDestination.GovernorDashboard,
                    onClick = { currentTab = OsDestination.GovernorDashboard },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = "Overview") },
                    label = { Text("Overview") },
                    modifier = Modifier.testTag("nav_overview")
                )
                NavigationBarItem(
                    selected = currentTab == OsDestination.PipelineInspector,
                    onClick = { currentTab = OsDestination.PipelineInspector },
                    icon = { Icon(Icons.Default.AccountTree, contentDescription = "Pipeline") },
                    label = { Text("Pipeline") },
                    modifier = Modifier.testTag("nav_pipeline")
                )
                NavigationBarItem(
                    selected = currentTab == OsDestination.GateMatrix,
                    onClick = { currentTab = OsDestination.GateMatrix },
                    icon = { Icon(Icons.Default.VerifiedUser, contentDescription = "G1–G5 Gates") },
                    label = { Text("G1–G5 Gates") },
                    modifier = Modifier.testTag("nav_gates")
                )
                NavigationBarItem(
                    selected = currentTab == OsDestination.ReleaseVault,
                    onClick = { currentTab = OsDestination.ReleaseVault },
                    icon = { Icon(Icons.Default.Lock, contentDescription = "Release Lock") },
                    label = { Text("Release Lock") },
                    modifier = Modifier.testTag("nav_release")
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            OsHeader(
                currentStageTitle = selectedStage.displayName,
                modifier = Modifier.fillMaxWidth()
            )

            when (currentTab) {
                OsDestination.GovernorDashboard -> {
                    PipelineStageRail(
                        selectedStage = selectedStage,
                        onStageSelect = { viewModel.selectStage(it) }
                    )

                    StageContractCard(
                        stage = selectedStage,
                        descriptor = viewModel.stageDescriptors[selectedStage],
                        todoMessage = viewModel.getTodoNotice(selectedStage)
                    )

                    GateStatusMatrix(
                        gateStatuses = pipelineState.gateStatuses,
                        isReleaseLocked = pipelineState.isReleaseLocked
                    )
                }

                OsDestination.PipelineInspector -> {
                    PipelineStageRail(
                        selectedStage = selectedStage,
                        onStageSelect = { viewModel.selectStage(it) }
                    )

                    StageContractCard(
                        stage = selectedStage,
                        descriptor = viewModel.stageDescriptors[selectedStage],
                        todoMessage = viewModel.getTodoNotice(selectedStage)
                    )
                }

                OsDestination.GateMatrix -> {
                    GateStatusMatrix(
                        gateStatuses = pipelineState.gateStatuses,
                        isReleaseLocked = pipelineState.isReleaseLocked
                    )
                }

                OsDestination.ReleaseVault -> {
                    StageContractCard(
                        stage = PipelineStage.GATE_5_RELEASE_LOCK,
                        descriptor = viewModel.stageDescriptors[PipelineStage.GATE_5_RELEASE_LOCK],
                        todoMessage = viewModel.getTodoNotice(PipelineStage.GATE_5_RELEASE_LOCK)
                    )

                    StageContractCard(
                        stage = PipelineStage.MASTER_PLAYER,
                        descriptor = viewModel.stageDescriptors[PipelineStage.MASTER_PLAYER],
                        todoMessage = viewModel.getTodoNotice(PipelineStage.MASTER_PLAYER)
                    )
                }

                else -> Unit
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
