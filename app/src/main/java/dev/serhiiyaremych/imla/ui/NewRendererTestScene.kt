package dev.serhiiyaremych.imla.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.serhiiyaremych.imla.effectLayer
import kotlin.math.abs

private val CardClipInset = PaddingValues(1.5.dp)
private const val SceneNoiseAlphaForTest = 0.22f

@Composable
fun NewRendererTestScene(modifier: Modifier = Modifier) {
    var screenSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    val circleSizeDp = 300.dp
    val circleSizePx = with(density) { circleSizeDp.toPx() }

    val infiniteTransition = rememberInfiniteTransition(label = "bouncing")
    val progressX by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "progressX"
    )

    val progressY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "progressY"
    )

    val bounceX = (1f - abs(2f * progressX - 1f))
    val bounceY = (1f - abs(2f * progressY - 1f))

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { screenSize = it }
            .drawBehind {
                val cellSize = size.width / 10f
                if (cellSize > 0f) {
                    val cols = (size.width / cellSize).toInt() + 1
                    val rows = (size.height / cellSize).toInt() + 1
                    for (i in 0 until cols) {
                        for (j in 0 until rows) {
                            val color = if ((i + j) % 2 == 0) Color.White else Color.Transparent
                            drawRect(
                                color = color,
                                topLeft = Offset(i * cellSize, j * cellSize),
                                size = Size(cellSize, cellSize)
                            )
                        }
                    }
                }
            }
    ) {
        if (screenSize != IntSize.Zero) {
            val maxX = (screenSize.width - circleSizePx).coerceAtLeast(0f)
            val maxY = (screenSize.height - circleSizePx).coerceAtLeast(0f)

            BackdropOrientationMarkers()

            Box(
                modifier = Modifier
                    .graphicsLayer {
                        translationX = bounceX * maxX
                        translationY = bounceY * maxY
                    }
                    .size(circleSizeDp)
                    .background(Color.Magenta, CircleShape)
            )

            StaticCreditCard()
            CumulativeOverlapProbe()
            TranslationDiagnosticSlot(screenSize = screenSize)
            RotationDiagnosticSlot()
            PrefilterInspectionSlot()
        }
    }
}

@Composable
private fun BoxScope.CumulativeOverlapProbe() {
    val sourceShape = RoundedCornerShape(12.dp)
    val blurShape = RoundedCornerShape(14.dp)
    Surface(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset(x = 42.dp, y = 314.dp)
            .effectLayer {}
            .size(width = 148.dp, height = 92.dp),
        shape = sourceShape,
        color = Color(0xFFFF1744).copy(alpha = 0.72f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.84f)),
        tonalElevation = 4.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
        ) {
            Text(
                text = "BASE",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }

    Surface(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset(x = 108.dp, y = 358.dp)
            .effectLayer {
                backdropBlur(sigmaPx = 22f)
                tint(Color(0xFFB2FF59).copy(alpha = 0.18f))
                noise(alpha = SceneNoiseAlphaForTest)
                clip(blurShape, inset = CardClipInset)
            }
            .size(width = 154.dp, height = 94.dp),
        shape = blurShape,
        color = Color(0xFFB2FF59).copy(alpha = 0.28f),
        border = BorderStroke(1.dp, Color(0xFF1B5E20).copy(alpha = 0.74f)),
        tonalElevation = 6.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
        ) {
            Text(
                text = "STACK",
                color = Color(0xFF1B5E20),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun BoxScope.StaticCreditCard() {
    val cardShape = RoundedCornerShape(16.dp)
    Surface(
        modifier = Modifier
            .align(Alignment.Center)
            .graphicsLayer {
                rotationZ = -14f
            }
            .effectLayer {
                backdropBlur(sigmaPx = 24f)
                tint(Color(0xFFE0F7FA).copy(alpha = 0.26f))
                noise(alpha = SceneNoiseAlphaForTest)
                clip(cardShape, inset = CardClipInset)
            }
            .size(width = 350.dp, height = 220.dp),
        shape = cardShape,
        color = Color.White.copy(alpha = 0.34f),
        border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.58f)),
        tonalElevation = 8.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Text(
                text = "4532 8812 0912 3456",
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                ),
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun BoxScope.TranslationDiagnosticSlot(screenSize: IntSize) {
    val density = LocalDensity.current
    val slotShape = RoundedCornerShape(12.dp)
    val slotWidthDp = 190.dp
    val slotHeightDp = 112.dp
    val slotWidthPx = with(density) { slotWidthDp.toPx() }
    val slotHeightPx = with(density) { slotHeightDp.toPx() }
    val startXPx = with(density) { 40.dp.toPx() }
    val endXPx = with(density) { 104.dp.toPx() }
    val startYPx = with(density) { 118.dp.toPx() }
    val bottomMarginPx = with(density) { 164.dp.toPx() }
    val endYPx = (screenSize.height - slotHeightPx - bottomMarginPx)
        .coerceAtLeast(startYPx)

    val transition = rememberInfiniteTransition(label = "translationDiagnostic")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(7000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "translationDiagnosticProgress"
    )

    Surface(
        modifier = Modifier
            .align(Alignment.TopStart)
            .graphicsLayer {
                val p = progress.value
                translationX = startXPx + (endXPx - startXPx) * p
                translationY = startYPx + (endYPx - startYPx) * p
            }
            .effectLayer {
                backdropBlur(sigmaPx = 18f)
                tint(Color(0xFFFFF176).copy(alpha = 0.18f))
                noise(alpha = SceneNoiseAlphaForTest)
                clip(slotShape, inset = CardClipInset)
            }
            .size(width = slotWidthDp, height = slotHeightDp),
        shape = slotShape,
        color = Color(0xFFFFD54F).copy(alpha = 0.38f),
        border = BorderStroke(1.dp, Color(0xFFB71C1C).copy(alpha = 0.58f)),
        tonalElevation = 6.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(width = 16.dp, height = 72.dp)
                    .background(Color(0xFF1E88E5), RoundedCornerShape(4.dp))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                    .background(Color(0xFFD32F2F), CircleShape)
            )
            Text(
                text = "TX / TY",
                color = Color(0xFFB71C1C),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun BoxScope.RotationDiagnosticSlot() {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 92.dp, end = 32.dp)
            .size(width = 316.dp, height = 210.dp)
    ) {
        FixedRotationDiagnosticCard(
            label = "Z",
            rotationZRange = -34f to 34f,
            debugColor = Color(0xFFFF8A80),
            surfaceColor = Color(0xFF80DEEA),
            modifier = Modifier.align(Alignment.TopStart),
            durationMs = 14_000
        )
        FixedRotationDiagnosticCard(
            label = "X",
            rotationXRange = -38f to 38f,
            debugColor = Color(0xFFFFAB40),
            surfaceColor = Color(0xFFA5D6A7),
            modifier = Modifier.align(Alignment.TopEnd),
            durationMs = 16_000
        )
        FixedRotationDiagnosticCard(
            label = "Y",
            rotationYRange = -38f to 38f,
            debugColor = Color(0xFFB388FF),
            surfaceColor = Color(0xFFFFCC80),
            modifier = Modifier.align(Alignment.BottomStart),
            durationMs = 18_000
        )
        FixedRotationDiagnosticCard(
            label = "XY",
            rotationXRange = -34f to 34f,
            rotationYRange = 34f to -34f,
            debugColor = Color(0xFF64B5F6),
            surfaceColor = Color(0xFFE1BEE7),
            modifier = Modifier.align(Alignment.BottomEnd),
            durationMs = 20_000
        )
    }
}

@Composable
private fun BoxScope.PrefilterInspectionSlot() {
    val slotShape = RoundedCornerShape(12.dp)
    val progressiveMask = remember {
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.38f to Color.White,
            1f to Color.White
        )
    }
    Surface(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 32.dp, bottom = 96.dp)
            .effectLayer {
                backdropBlur(
                    sigmaPx = 64f,
                    progressiveMask = progressiveMask
                )
                tint(Color(0xFF00E5FF).copy(alpha = 0.20f))
                noise(alpha = SceneNoiseAlphaForTest)
                clip(slotShape, inset = CardClipInset)
            }
            .size(width = 246.dp, height = 142.dp),
        shape = slotShape,
        color = Color.Transparent,
        border = BorderStroke(2.dp, Color(0xFF00E5FF).copy(alpha = 0.88f)),
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(width = 26.dp, height = 88.dp)
                    .background(Color(0xFF00E5FF).copy(alpha = 0.64f), RoundedCornerShape(4.dp))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(24.dp)
                    .background(Color(0xFFFF1744).copy(alpha = 0.78f), CircleShape)
            )
            Text(
                text = "PREFILTER",
                color = Color(0xFF00E5FF),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun FixedRotationDiagnosticCard(
    label: String,
    debugColor: Color,
    surfaceColor: Color,
    durationMs: Int,
    modifier: Modifier = Modifier,
    rotationXRange: Pair<Float, Float> = 0f to 0f,
    rotationYRange: Pair<Float, Float> = 0f to 0f,
    rotationZRange: Pair<Float, Float> = 0f to 0f
) {
    val density = LocalDensity.current
    val cardShape = RoundedCornerShape(12.dp)
    val transition = rememberInfiniteTransition(label = "$label rotation")
    val rotationX = transition.animateFloat(
        initialValue = rotationXRange.first,
        targetValue = rotationXRange.second,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "$label rotationX"
    )
    val rotationY = transition.animateFloat(
        initialValue = rotationYRange.first,
        targetValue = rotationYRange.second,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs + 2_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "$label rotationY"
    )
    val rotationZ = transition.animateFloat(
        initialValue = rotationZRange.first,
        targetValue = rotationZRange.second,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs + 4_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "$label rotationZ"
    )

    Surface(
        modifier = modifier
            .graphicsLayer {
                this.rotationX = rotationX.value
                this.rotationY = rotationY.value
                this.rotationZ = rotationZ.value
                cameraDistance = 18f * density.density
            }
            .effectLayer {
                backdropBlur(sigmaPx = 16f)
                tint(debugColor.copy(alpha = 0.18f))
                noise(alpha = SceneNoiseAlphaForTest)
                clip(cardShape, inset = CardClipInset)
            }
            .size(width = 132.dp, height = 78.dp),
        shape = cardShape,
        color = surfaceColor.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, debugColor.copy(alpha = 0.72f)),
        tonalElevation = 6.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(width = 12.dp, height = 50.dp)
                    .background(Color(0xFF6A1B9A), RoundedCornerShape(4.dp))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(16.dp)
                    .background(Color(0xFFE53935), CircleShape)
            )
            Text(
                text = label,
                color = Color(0xFF004D40),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun BoxScope.BackdropOrientationMarkers() {
    Text(
        text = "TL",
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .align(Alignment.Center)
            .offset(x = (-184).dp, y = (-152).dp)
            .background(Color(0xFFE53935), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
    Text(
        text = "BL",
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .align(Alignment.Center)
            .offset(x = (-138).dp, y = 138.dp)
            .background(Color(0xFF43A047), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .offset(x = 154.dp, y = (-96).dp)
            .size(width = 18.dp, height = 92.dp)
            .background(Color(0xFF1E88E5), RoundedCornerShape(4.dp))
    )
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .offset(x = (-48).dp, y = (-136).dp)
            .size(22.dp)
            .background(Color(0xFFFF9800), CircleShape)
    )
}
