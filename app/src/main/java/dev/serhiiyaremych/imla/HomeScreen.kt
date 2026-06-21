/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class DemoScene { SHOWCASE, SOCIAL_FEED, PAGER, ROTATING_CARDS, HAZE_CARDS, RESIZE_CARD }

private data class DemoEntry(val scene: DemoScene, val title: String, val description: String)

private val DEMO_ENTRIES = listOf(
    DemoEntry(DemoScene.SHOWCASE, "Showcase", "All effects on a calibration grid"),
    DemoEntry(DemoScene.SOCIAL_FEED, "Social Feed", "Scrolling feed with frosted glass UI"),
    DemoEntry(DemoScene.PAGER, "Pager", "Multi-page glass cards"),
    DemoEntry(DemoScene.ROTATING_CARDS, "Rotating Cards", "3D-tilt blur tiles"),
    DemoEntry(DemoScene.HAZE_CARDS, "Haze Cards (ref)", "Same tiles via Haze backdrop blur"),
    DemoEntry(DemoScene.RESIZE_CARD, "Resize Card", "Expand/collapse blur card bounds"),
)

@Composable
fun HomeScreen(
    onSceneSelected: (DemoScene) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(48.dp))
        Text(
            text = "Imla",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Backdrop blur demos",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(32.dp))
        DEMO_ENTRIES.forEach { entry ->
            DemoCard(entry = entry, onClick = { onSceneSelected(entry.scene) })
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DemoCard(entry: DemoEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}
