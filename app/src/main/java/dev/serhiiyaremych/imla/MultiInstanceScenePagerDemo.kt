/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import dev.serhiiyaremych.imla.effectLayer
import dev.serhiiyaremych.imla.effectGroup
import dev.serhiiyaremych.imla.ImlaHost
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MultiInstanceScenePagerDemo(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues()
) {
    val pages = remember { scenePagerPages() }
    val pagerState = rememberPagerState(pageCount = { pages.size })

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 0,
            key = { page -> pages[page].id }
        ) { pageIndex ->
            val page = pages[pageIndex]
            val pageOffset = pagerState.pageOffsetFor(pageIndex)
            val isPageVisible = pageOffset < DetachedPageOffset
            if (isPageVisible) {
                SceneRendererPageHost(
                    page = page,
                    active = pageIndex == pagerState.settledPage && !pagerState.isScrollInProgress,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(page.background)
                )
            }
        }

        PageIndicators(
            pageCount = pages.size,
            currentPage = pagerState.currentPage,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp)
        )
    }
}

/**
 * Owns one page-local scene renderer, host surface session, root capture, and blur slot scope.
 *
 * The pager calls this only for visible pages. When a page leaves this branch, Compose forgets the
 * remembered renderer and the host detaches its surface, so hidden pages do not keep issuing scene
 * renders. Child content receives page data and animation state only; the renderer is deliberately
 * not shared with blur slots or neighboring pages.
 */
@Composable
private fun SceneRendererPageHost(
    page: ScenePagerPage,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    ImlaHost(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .effectGroup()
        ) {
            ScenePagerBackground(page = page, active = active)
            ScenePagerGlassCards(page = page)
        }
    }
}

@Composable
private fun ScenePagerBackground(
    page: ScenePagerPage,
    active: Boolean
) {
    val progress = rememberActivePageProgress(active = active, durationMillis = page.durationMillis)
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(page.background)
    ) {
        page.draw(this, progress)
    }
}

@Composable
private fun ScenePagerGlassCards(page: ScenePagerPage) {
    Box(modifier = Modifier.fillMaxSize()) {
        GlassCard(
            title = page.primaryTitle,
            subtitle = page.primarySubtitle,
            tint = page.primaryTint,
            width = 282.dp,
            height = 190.dp,
            zIndex = 4f,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = page.primaryOffsetX, y = page.primaryOffsetY)
        )

        GlassCard(
            title = page.secondaryTitle,
            subtitle = page.secondarySubtitle,
            tint = page.secondaryTint,
            width = 244.dp,
            height = 148.dp,
            zIndex = 8f,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = page.secondaryOffsetX, y = page.secondaryOffsetY)
        )

        if (page.showStackedCard) {
            GlassCard(
                title = "Stacked",
                subtitle = "third slot",
                tint = Color(0xFFFFF4D9),
                width = 206.dp,
                height = 118.dp,
                zIndex = 12f,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = 46.dp, y = 92.dp)
            )
        }
    }
}

@Composable
private fun GlassCard(
    title: String,
    subtitle: String,
    tint: Color,
    width: Dp,
    height: Dp,
    zIndex: Float,
    modifier: Modifier = Modifier
) {
    val shape = remember { RoundedCornerShape(28.dp) }
    Box(
        modifier = modifier
            .zIndex(zIndex)
            .size(width = width, height = height)
            .effectLayer(
                style = DemoEffectStyle.default.copy(
                    sigma = 10f,
                    tint = tint.copy(alpha = 0.38f),
                    noiseAlpha = 0.08f,
                    blurOpacity = 1f
                ),
                clipShape = shape,
                debugName = "pager-card-$title",
                zIndex = zIndex
            )
            .background(Color.White.copy(alpha = 0.08f), shape)
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.55f),
                        Color.White.copy(alpha = 0.14f)
                    )
                ),
                shape = shape
            )
            .padding(22.dp),
        contentAlignment = Alignment.BottomStart
    ) {
        Column {
            Text(
                text = title,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun PageIndicators(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            val selected = index == currentPage
            Box(
                modifier = Modifier
                    .size(width = if (selected) 28.dp else 10.dp, height = 10.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (selected) 0.9f else 0.36f))
            )
        }
    }
}

@Composable
private fun rememberActivePageProgress(
    active: Boolean,
    durationMillis: Int
): Float {
    val animation = remember { Animatable(0f) }
    LaunchedEffect(active, durationMillis) {
        if (!active) return@LaunchedEffect
        animation.snapTo(0f)
        animation.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = durationMillis,
                easing = LinearEasing
            )
        )
    }
    return animation.value
}

private fun PagerState.pageOffsetFor(page: Int): Float {
    return ((currentPage - page) + currentPageOffsetFraction).absoluteValue
}

private fun scenePagerPages(): List<ScenePagerPage> {
    return listOf(
        ScenePagerPage(
            id = "aurora",
            background = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF053C5E),
                    Color(0xFF1F7A8C),
                    Color(0xFFF4D35E)
                )
            ),
            primaryTitle = "Aurora",
            primarySubtitle = "renderer A",
            secondaryTitle = "North",
            secondarySubtitle = "overlapping slot",
            primaryTint = Color(0xFFE9FFFE),
            secondaryTint = Color(0xFFB8FFE0),
            primaryOffsetX = (-36).dp,
            primaryOffsetY = (-36).dp,
            secondaryOffsetX = 62.dp,
            secondaryOffsetY = 44.dp,
            showStackedCard = true,
            durationMillis = 5600,
            draw = DrawScope::drawAuroraPage
        ),
        ScenePagerPage(
            id = "ember",
            background = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF2B1A3D),
                    Color(0xFFC3423F),
                    Color(0xFFFFC857)
                )
            ),
            primaryTitle = "Ember",
            primarySubtitle = "renderer B",
            secondaryTitle = "South",
            secondarySubtitle = "independent slot",
            primaryTint = Color(0xFFFFE1CC),
            secondaryTint = Color(0xFFFFB3C1),
            primaryOffsetX = 28.dp,
            primaryOffsetY = (-52).dp,
            secondaryOffsetX = (-76).dp,
            secondaryOffsetY = 76.dp,
            showStackedCard = false,
            durationMillis = 6800,
            draw = DrawScope::drawEmberPage
        )
    )
}

private data class ScenePagerPage(
    val id: String,
    val background: Brush,
    val primaryTitle: String,
    val primarySubtitle: String,
    val secondaryTitle: String,
    val secondarySubtitle: String,
    val primaryTint: Color,
    val secondaryTint: Color,
    val primaryOffsetX: Dp,
    val primaryOffsetY: Dp,
    val secondaryOffsetX: Dp,
    val secondaryOffsetY: Dp,
    val showStackedCard: Boolean,
    val durationMillis: Int,
    val draw: DrawScope.(Float) -> Unit
)

private fun DrawScope.drawAuroraPage(progress: Float) {
    val w = size.width
    val h = size.height
    val orbit = progress * 2f * PI.toFloat()
    drawCircle(
        color = Color(0xFF8CFFDA).copy(alpha = 0.82f),
        radius = w * 0.24f,
        center = Offset(
            x = lerp(w * 0.14f, w * 0.72f, (sin(orbit) + 1f) / 2f),
            y = h * 0.28f + cos(orbit * 0.7f) * h * 0.08f
        )
    )
    drawCircle(
        color = Color(0xFFFFE66D).copy(alpha = 0.72f),
        radius = w * 0.18f,
        center = Offset(
            x = w * 0.82f + sin(orbit * 0.8f) * w * 0.08f,
            y = lerp(h * 0.55f, h * 0.82f, (cos(orbit) + 1f) / 2f)
        )
    )
    drawCircle(
        color = Color(0xFFEEF5DB).copy(alpha = 0.54f),
        radius = w * 0.13f,
        center = Offset(
            x = w * 0.24f + cos(orbit * 1.2f) * w * 0.1f,
            y = h * 0.76f
        )
    )
}

private fun DrawScope.drawEmberPage(progress: Float) {
    val w = size.width
    val h = size.height
    val orbit = progress * 2f * PI.toFloat()
    drawCircle(
        color = Color(0xFFFF6B6B).copy(alpha = 0.8f),
        radius = w * 0.22f,
        center = Offset(
            x = w * 0.22f + sin(orbit * 0.9f) * w * 0.1f,
            y = h * 0.24f + cos(orbit) * h * 0.12f
        )
    )
    drawCircle(
        color = Color(0xFFFFD166).copy(alpha = 0.74f),
        radius = w * 0.25f,
        center = Offset(
            x = lerp(w * 0.38f, w * 0.88f, (cos(orbit * 0.65f) + 1f) / 2f),
            y = h * 0.68f
        )
    )
    drawCircle(
        color = Color(0xFFB8F2E6).copy(alpha = 0.46f),
        radius = w * 0.15f,
        center = Offset(
            x = w * 0.68f,
            y = h * 0.36f + sin(orbit * 1.3f) * h * 0.1f
        )
    )
}

private const val DetachedPageOffset = 0.98f
