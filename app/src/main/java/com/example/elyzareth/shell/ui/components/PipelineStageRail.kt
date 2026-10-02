package com.example.elyzareth.shell.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.elyzareth.core.model.PipelineStage

@Composable
fun PipelineStageRail(
    selectedStage: PipelineStage,
    onStageSelect: (PipelineStage) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = "SYSTEM DATA-FLOW PIPELINE",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PipelineStage.entries.forEachIndexed { index, stage ->
                val isSelected = stage == selectedStage

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when {
                        isSelected -> MaterialTheme.colorScheme.primary
                        stage.isGate -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                        stage == PipelineStage.MASTER_PLAYER -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier
                        .clickable { onStageSelect(stage) }
                        .testTag("stage_chip_${stage.name.lowercase()}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${stage.stageIndex + 1}. ${stage.displayName}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = when {
                                isSelected -> MaterialTheme.colorScheme.onPrimary
                                stage.isGate -> MaterialTheme.colorScheme.onErrorContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )

                        if (stage.isGate) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Gate Lock",
                                modifier = Modifier.size(12.dp),
                                tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                if (index < PipelineStage.entries.size - 1) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Next Stage",
                        tint = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
