/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.serhiiyaremych.imla.modifier.blurSource
import dev.serhiiyaremych.imla.ui.BlurBehindView
import dev.serhiiyaremych.imla.ui.theme.ImlaTheme
import dev.serhiiyaremych.imla.uirenderer.rememberUiLayerRenderer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val longText: String = """
        
        0. What is Lorem Ipsum?

        Lorem Ipsum is simply dummy text of the printing and typesetting industry. Lorem Ipsum has been the industry's standard dummy text ever since the 1500s, when an unknown printer took a galley of type and scrambled it to make a type specimen book. It has survived not only five centuries, but also the leap into electronic typesetting, remaining essentially unchanged. It was popularised in the 1960s with the release of Letraset sheets containing Lorem Ipsum passages, and more recently with desktop publishing software like Aldus PageMaker including versions of Lorem Ipsum.
        Why do we use it?

        It is a long established fact that a reader will be distracted by the readable content of a page when looking at its layout. The point of using Lorem Ipsum is that it has a more-or-less normal distribution of letters, as opposed to using 'Content here, content here', making it look like readable English. Many desktop publishing packages and web page editors now use Lorem Ipsum as their default model text, and a search for 'lorem ipsum' will uncover many web sites still in their infancy. Various versions have evolved over the years, sometimes by accident, sometimes on purpose (injected humour and the like).

        1. Where does it come from?

        Contrary to popular belief, Lorem Ipsum is not simply random text. It has roots in a piece of classical Latin literature from 45 BC, making it over 2000 years old. Richard McClintock, a Latin professor at Hampden-Sydney College in Virginia, looked up one of the more obscure Latin words, consectetur, from a Lorem Ipsum passage, and going through the cites of the word in classical literature, discovered the undoubtable source. Lorem Ipsum comes from sections 1.10.32 and 1.10.33 of "de Finibus Bonorum et Malorum" (The Extremes of Good and Evil) by Cicero, written in 45 BC. This book is a treatise on the theory of ethics, very popular during the Renaissance. The first line of Lorem Ipsum, "Lorem ipsum dolor sit amet..", comes from a line in section 1.10.32.

    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.LTGRAY,
                android.graphics.Color.BLACK
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.LTGRAY,
                android.graphics.Color.BLACK
            )
        )
        setContent {
            ImlaTheme {
                val uiRenderer = rememberUiLayerRenderer()
                DisposableEffect(key1 = uiRenderer) {
                    onDispose {
                        uiRenderer.destroy()
                    }
                }
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                ) { _ ->
                    Content(
                        Modifier
                            .fillMaxSize()
                            .blurSource(uiRenderer)
                            .background(Color.LightGray),
                        onScroll = { uiRenderer.onUiLayerUpdated() }
                    )

                    val travelDistance = with(LocalDensity.current) { 500.dp.toPx() }
                    val infiniteAnimation =
                        rememberInfiniteTransition(label = "infiniteAnimation")
                    val offsetAnim = infiniteAnimation.animateFloat(
                        initialValue = 0f,
                        targetValue = travelDistance,
                        animationSpec = infiniteRepeatable(
                            animation = tween(4000),
                            repeatMode = RepeatMode.Reverse
                        ), label = "offsetAnim"
                    )
                    BlurBehindView(modifier = Modifier
                        .fillMaxWidth()
                        .offset {
                            uiRenderer.onUiLayerUpdated()
                            IntOffset(0, offsetAnim.value.toInt())
                        }
                        .height(156.dp)
                        .border(1.dp, Color.DarkGray),
                        uiLayerRenderer = uiRenderer) { offsetUpdater ->
                        LaunchedEffect(key1 = offsetUpdater) {
                            snapshotFlow { offsetAnim.value }
                                .distinctUntilChanged()
                                .collect { offsetUpdater(IntOffset(x = 0, y = it.roundToInt())) }

                        }
                        Box(
                            modifier = Modifier.matchParentSize()
                        ) {
                            Text(
                                modifier = Modifier.align(Alignment.Center),
                                text = "😊 Hello Blur!\nПривіт Blur!😍",
                                fontSize = 34.sp,
                                lineHeight = 40.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Content(modifier: Modifier, onScroll: (Int) -> Unit) {
        val scrollState = rememberScrollState()
        val currentOnScroll = rememberUpdatedState(onScroll).value
        LaunchedEffect(key1 = scrollState, key2 = onScroll) {
            snapshotFlow { scrollState.value }
                .distinctUntilChanged()
                .collect {
                    currentOnScroll(it)
                }
        }

        Column(modifier = modifier.verticalScroll(scrollState)) {
            Text(text = longText)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                repeat(5) {
                    Button(onClick = { /*TODO*/ }) {
                        Text(text = "Button $it")
                    }
                }
            }
            Row {
                Image(
                    imageVector = Icons.Filled.Build,
                    contentDescription = null
                )
                Image(
                    imageVector = Icons.Filled.AccountBox,
                    contentDescription = null
                )
                Image(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null
                )
                Image(
                    imageVector = Icons.Filled.Face,
                    contentDescription = null
                )
            }
            Row {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_background),
                    contentDescription = null
                )
                Column {
                    val infinite = rememberInfiniteTransition("infinite")
                    val width = infinite.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            tween(2000),
                            repeatMode = RepeatMode.Reverse
                        ), label = "rotation"
                    )
                    Box(modifier = Modifier
                        .size(40.dp)
                        .graphicsLayer {
                            scaleX = width.value * 4f
                            transformOrigin = TransformOrigin(0f, 0.5f)
                            onScroll((width.value * 100f).roundToInt())
                        }
                        .background(Color.Green))
                    Box(
                        modifier = Modifier
                            .requiredHeight(68.dp)
                            .fillMaxWidth()
                            .border(1.dp, Color.Blue)
                            .drawWithCache {
                                val gradient = Brush.horizontalGradient(
                                    listOf(
                                        Color.Magenta,
                                        Color.Blue,
                                        Color.Green
                                    )
                                )
                                onDrawBehind {
                                    drawRect(gradient)
                                }
                            }
                    )
                }
            }
            Text(text = longText)
        }
    }
}