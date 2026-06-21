/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.imla.DemoEffectStyle
import dev.serhiiyaremych.imla.effectLayer

private val NavContainerHeight = 72.dp
private const val NavContainerWidthPercent = 0.85f

data class NavItem(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val badgeCount: Int = 0
)

val DefaultNavItems = listOf(
    NavItem("Home", Icons.Filled.Home, Icons.Outlined.Home),
    NavItem(
        "DMs",
        Icons.Filled.Email,
        Icons.Outlined.Email,
        badgeCount = 3
    ),
    NavItem(
        "Activity",
        Icons.Filled.Notifications,
        Icons.Outlined.Notifications,
        badgeCount = 5
    ),
    NavItem("More", Icons.Filled.MoreHoriz, Icons.Outlined.MoreHoriz)
)

@Composable
fun BlurredBottomNav(
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    items: List<NavItem> = DefaultNavItems
) {

    val glassyRimGradient = remember {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color.White.copy(alpha = 0.3f),
                1.0f to Color.White.copy(alpha = 0.1f)
            )
        )
    }

    val blurStyle = remember {
        DemoEffectStyle.default.copy(
            sigma = 10f,
            noiseAlpha = 0.1f,
            blurOpacity = 1.0f
        )
    }

    Box(
        modifier = modifier
            .height(NavContainerHeight)
            .bottomNavBackdropBlur(
                style = blurStyle,
                clipShape = CircleShape,
                zIndex = 10f
            )
            .border(
                width = 0.5.dp,
                brush = glassyRimGradient,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selectedIndex

                NavItem(
                    item = item,
                    isSelected = isSelected,
                    onClick = { onItemSelected(index) }
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    item: NavItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val activeBackgroundColor = Color(0xFFE3F2FD).copy(alpha = 0.6f) // Light blue for active state
    val darkNavyColor = Color(0xFF1A2332) // Dark navy for icons

    Box(
        modifier = Modifier
            .size(48.dp)
            .bottomNavBackdropBlur(
                style = DemoEffectStyle.default.copy(
                    sigma = if (isSelected) 10f else 0.5f,
                    tint = if (isSelected) activeBackgroundColor else Color.Transparent
                ),
                clipShape = CircleShape,
                zIndex = 100f
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
            contentDescription = item.label,
            tint = darkNavyColor,
            modifier = Modifier.size(24.dp)
        )
    }
}

private fun Modifier.bottomNavBackdropBlur(
    style: DemoEffectStyle,
    clipShape: androidx.compose.ui.graphics.Shape,
    zIndex: Float
): Modifier = effectLayer(
    style = style,
    clipShape = clipShape,
    zIndex = zIndex
)
