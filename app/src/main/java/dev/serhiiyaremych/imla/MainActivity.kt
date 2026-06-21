/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.tracing.trace
import dev.serhiiyaremych.imla.data.ApiClient
import dev.serhiiyaremych.imla.ui.BlurBenchmarkParams
import dev.serhiiyaremych.imla.ui.BlurBenchmarkScene
import dev.serhiiyaremych.imla.ui.FauxCube
import dev.serhiiyaremych.imla.ui.ResizeCardScene
import dev.serhiiyaremych.imla.ui.components.BlurredBottomNav
import dev.serhiiyaremych.imla.ui.components.BlurredFab
import dev.serhiiyaremych.imla.ui.components.BlurredTopBar
import dev.serhiiyaremych.imla.ui.components.DebugStatsDropdown
import dev.serhiiyaremych.imla.ui.theme.ImlaTheme
import dev.serhiiyaremych.imla.ui.userpost.SimpleImageViewer
import dev.serhiiyaremych.imla.ui.userpost.UserPostView
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var idlenessJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        if (BuildConfig.DEBUG) {
            startIdlenessTracking()
        }
        setContent {
            ImlaTheme {
                val selectedSceneIdx = rememberSaveable { mutableIntStateOf(-1) }
                val selectedScene: DemoScene? = DemoScene.entries.getOrNull(selectedSceneIdx.intValue)

                val atlasBenchmarkSceneEnabled = isAtlasBenchmarkSceneEnabled()
                val clipAtlasDiagnosticSceneEnabled = isClipAtlasDiagnosticSceneEnabled()
                val coverageMaskAtlasSceneEnabled = isCoverageMaskAtlasSceneEnabled()
                val maskSemanticsSceneEnabled = isMaskSemanticsSceneEnabled()
                val alphaCompositeSceneEnabled = isAlphaCompositeSceneEnabled()
                val captureImportParitySceneEnabled = isCaptureImportParitySceneEnabled()
                val blurBenchmarkParams = remember { BlurBenchmarkParams.fromIntent(intent) }

                val anyDiagnosticSceneEnabled = atlasBenchmarkSceneEnabled
                    || clipAtlasDiagnosticSceneEnabled
                    || coverageMaskAtlasSceneEnabled
                    || maskSemanticsSceneEnabled
                    || alphaCompositeSceneEnabled
                    || captureImportParitySceneEnabled

                BackHandler(enabled = selectedScene != null) {
                    selectedSceneIdx.intValue = -1
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    if (blurBenchmarkParams != null) {
                        ImlaHost(
                            modifier = Modifier.fillMaxSize(),
                            showMetricsOverlay = false
                        ) {
                            BlurBenchmarkScene(
                                params = blurBenchmarkParams,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else if (anyDiagnosticSceneEnabled) {
                        ImlaHost(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                Surface(
                                    Modifier
                                        .fillMaxSize()
                                        .effectGroup(),
                                ) {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        if (captureImportParitySceneEnabled) {
                                            CaptureImportParityDiagnosticScene(modifier = Modifier.fillMaxSize())
                                        } else if (clipAtlasDiagnosticSceneEnabled) {
                                            ClipAtlasDiagnosticScene(modifier = Modifier.fillMaxSize())
                                        } else if (coverageMaskAtlasSceneEnabled) {
                                            CoverageMaskAtlasDiagnosticScene(modifier = Modifier.fillMaxSize())
                                        } else if (alphaCompositeSceneEnabled) {
                                            AlphaCompositeScene(modifier = Modifier.fillMaxSize())
                                        } else if (maskSemanticsSceneEnabled) {
                                            MaskSemanticsScene(modifier = Modifier.fillMaxSize())
                                        } else if (atlasBenchmarkSceneEnabled) {
                                            AtlasBenchmarkScene(modifier = Modifier.fillMaxSize())
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        when (selectedScene) {
                            null -> HomeScreen(
                                onSceneSelected = { selectedSceneIdx.intValue = it.ordinal },
                                modifier = Modifier.fillMaxSize()
                            )
                            DemoScene.SOCIAL_FEED -> SocialFeedDemoScene(
                                modifier = Modifier.fillMaxSize()
                            )
                            DemoScene.PAGER -> MultiInstanceScenePagerDemo(
                                modifier = Modifier.fillMaxSize()
                            )
                            DemoScene.CUBE -> CubeDemoScene(modifier = Modifier.fillMaxSize())
                            DemoScene.ROTATING_CARDS -> RotatingCardsScene(
                                modifier = Modifier.fillMaxSize()
                            )
                            DemoScene.HAZE_CARDS -> HazeRotatingCardsScene(
                                modifier = Modifier.fillMaxSize()
                            )
                            DemoScene.RESIZE_CARD -> ResizeCardScene(
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                val fullyDrawn = remember { mutableStateOf(false) }
                val isRendererInitialized = true
                LaunchedEffect(isRendererInitialized) {
                    if (isRendererInitialized) {
                        delay(1000)
                        fullyDrawn.value = true
                    }
                }
                ReportDrawnWhen { fullyDrawn.value }
            }
        }
    }

    /**
     * Invisible, debug-only slot that makes atlas-on traces prove the eligible pipeline path.
     *
     * Most visible demo slots intentionally use clip or mask and therefore prove fallback.
     * This slot is only present behind the manual atlas diagnostic log tag, has no visible opacity,
     * and keeps the default app path unchanged.
     */
    @Composable
    private fun AtlasDiagnosticProofSlot(modifier: Modifier = Modifier) {
        if (!isAtlasDiagnosticProofEnabled()) return

        Box(
            modifier = modifier
                .size(96.dp)
                .effectLayer(
                    style = DemoEffectStyle.default.copy(
                        noiseAlpha = 0f,
                        blurOpacity = 0f,
                        tint = Color.Transparent
                    ),
                    debugName = "atlas-proof",
                    zIndex = -100f
                )
        )
    }

    private fun isAtlasDiagnosticProofEnabled(): Boolean {
        return isAtlasDiagnosticBuild() && Log.isLoggable(ATLAS_DIAGNOSTIC_TAG, Log.DEBUG)
    }

    private fun isAtlasBenchmarkSceneEnabled(): Boolean {
        return isAtlasBenchmarkSceneSwitchEnabled(
            diagnosticBuild = isAtlasDiagnosticBuild(),
            manualSwitchEnabled = { Log.isLoggable(ATLAS_BENCHMARK_SCENE_TAG, Log.DEBUG) }
        )
    }

    private fun isClipAtlasDiagnosticSceneEnabled(): Boolean {
        return isAtlasDiagnosticBuild() && Log.isLoggable(CLIP_ATLAS_DIAGNOSTIC_SCENE_TAG, Log.DEBUG)
    }

    private fun isCoverageMaskAtlasSceneEnabled(): Boolean {
        return isAtlasDiagnosticBuild() && Log.isLoggable(COVERAGE_MASK_ATLAS_DIAGNOSTIC_SCENE_TAG, Log.DEBUG)
    }

    private fun isMaskSemanticsSceneEnabled(): Boolean {
        return isAtlasDiagnosticBuild() && Log.isLoggable(MASK_SEMANTICS_SCENE_TAG, Log.DEBUG)
    }

    private fun isAlphaCompositeSceneEnabled(): Boolean {
        return isAtlasDiagnosticBuild() && Log.isLoggable(ALPHA_COMPOSITE_SCENE_TAG, Log.DEBUG)
    }

    private fun isCaptureImportParitySceneEnabled(): Boolean {
        return isAtlasDiagnosticBuild() && Log.isLoggable(CAPTURE_IMPORT_PARITY_SCENE_TAG, Log.DEBUG)
    }

    private fun isAtlasDiagnosticBuild(): Boolean {
        return BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == ATLAS_DIAGNOSTIC_BUILD_TYPE
    }

    @Composable
    private fun Wrapped() {
        AndroidView(
            factory = { context ->
                View(context).apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    background = Color.Blue.copy(alpha = 0.5f).toArgb().toDrawable()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
                .border(1.dp, Color.Magenta)
        )
    }

    @Composable
    private fun PlainRootListScene(modifier: Modifier = Modifier) {
        Box(modifier = modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .effectGroup()
                    .background(Color.White),
                contentPadding = PaddingValues(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                )
            ) {
                items(PLAIN_ROOT_LIST_ITEM_COUNT) { index ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .height(72.dp)
                            .background(
                                color = PLAIN_ROOT_LIST_COLORS[index % PLAIN_ROOT_LIST_COLORS.size],
                                shape = RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = "Plain list item ${index + 1}",
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = Color.White,
                            fontSize = 18.sp
                        )
                    }
                }
            }

            // Backdrop-blur slot: a frosted-glass credit-card over the scrolling list.
            // Used to verify async-root consistency — the blurred backdrop must track
            // the colorful content moving behind it with no lag/tear/misalignment.
            // One large credit-card-sized region with a pure backdrop blur (no
            // tint/noise/clip): isolates the blur pass cost over the scrolling list.
            val centerBlurSlotEnabled = true
            if (centerBlurSlotEnabled) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.85f)
                        .aspectRatio(1.586f)
                        .effectLayer {
                            backdropBlur(sigmaPx = 24f)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "backdrop blur",
                        color = Color.White,
                        fontSize = 22.sp
                    )
                }
            }
        }
    }


    @Composable
    private fun Content(
        modifier: Modifier,
        contentPadding: PaddingValues,
        onImageClick: (String) -> Unit,
        onScroll: ((Int) -> Unit)? = null
    ) = trace("MainActivity#Content") {
        val scrollState = rememberLazyListState()
        val currentOnScroll = rememberUpdatedState(onScroll)
        if (onScroll != null) {
            LaunchedEffect(scrollState) {
                snapshotFlow { scrollState.firstVisibleItemScrollOffset }
                    .distinctUntilChanged()
                    .collect { currentOnScroll.value?.invoke(it) }
            }
        }
        val postsFlow = remember { ApiClient.getPosts() }
        val posts = postsFlow.collectAsStateWithLifecycle(initialValue = persistentListOf())
        LazyColumn(modifier = modifier, state = scrollState, contentPadding = contentPadding) {
            items(posts.value, key = { it.id }) { item ->
                UserPostView(
                    modifier = Modifier.fillMaxWidth(), post = item, onImageClick = onImageClick
                )
            }
        }
    }

    @Composable
    private fun BottomNavigationGlass(
        selectedIndex: Int,
        onItemSelected: (Int) -> Unit,
        modifier: Modifier = Modifier
    ) {
        Box(
            modifier = modifier
                .height(120.dp)
                .fillMaxWidth()
                .effectLayer(
                    style = DemoEffectStyle.default.copy(
                        sigma = 10f,
                        tint = Color.White.copy(alpha = 0.1f),
                        noiseAlpha = 0.1f,
                        blurOpacity = 1f
                    ),
                    blurMask = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.04f to Color.White.copy(alpha = 0.05f),
                            0.09f to Color.White.copy(alpha = 0.15f),
                            0.17f to Color.White.copy(alpha = 0.3f),
                            0.25f to Color.White.copy(alpha = 0.5f),
                            0.34f to Color.White,
                            1.0f to Color.White
                        ),
                    )
                )
                .padding(horizontal = 30.dp, vertical = 16.dp)
                .fillMaxWidth(0.9f)
                .height(72.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BlurredBottomNav(
                    selectedIndex = selectedIndex,
                    onItemSelected = onItemSelected,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(16.dp))

                BlurredFab(
                    onClick = { },
                    size = 72.dp,
                    modifier = Modifier
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun BlurSettingsBottomSheet(
        visible: Boolean,
        onDismissRequest: () -> Unit
    ) {
        if (!visible) return

        val noiseState = remember { mutableFloatStateOf(0.1f) }
        val sigmaState = remember { mutableFloatStateOf(8f) }
        val sheetShape = remember {
            RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        }
        val sheetState = rememberModalBottomSheetState()

        ModalBottomSheet(
            modifier = Modifier
                .statusBarsPadding(),
            sheetState = sheetState,
            shape = sheetShape,
            scrimColor = Color.Transparent,
            onDismissRequest = onDismissRequest,
            containerColor = Color.Transparent,
            contentColor = Color.Unspecified,
            // Suppress the platform drag handle: it is drawn by the sheet's
            // overlay-window chrome, outside our effect layer, so it would move
            // on a different cadence than the captured frosted content. Draw our
            // own handle inside the effectLayer Box so it is captured into the
            // slot and rendered in lockstep with the blur. The sheet stays
            // draggable by its body (anchoredDraggable is on the Surface).
            dragHandle = null
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .onSizeChanged {
                        Log.d(BOTTOM_SHEET_TAG, "sheet size=$it")
                    }
                    .onGloballyPositioned { coordinates ->
                        Log.d(
                            BOTTOM_SHEET_TAG,
                            "sheet position=${coordinates.positionInRoot()} size=${coordinates.size}"
                        )
                    }
                    .effectLayer(
                        style = DemoEffectStyle.default.copy(
                            noiseAlpha = noiseState.floatValue,
                            sigma = sigmaState.floatValue,
                            blurOpacity = 1f,
                            tint = Color(0xFFF6F3EF).copy(alpha = 0.55f)
                        ),
                        clipShape = sheetShape,
                        debugName = "bottom-sheet",
                        zIndex = 120f
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = 72.dp,
                            top = 16.dp,
                            end = 72.dp,
                            bottom = 24.dp
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .padding(bottom = 12.dp)
                            .size(width = 32.dp, height = 4.dp)
                            .background(
                                color = Color(0xFF49454F).copy(alpha = 0.4f),
                                shape = CircleShape
                            )
                    )
                    Text("Blur Settings", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val noiseStr = String.format(
                            locale = Locale.ENGLISH,
                            format = "%.1f", noiseState.floatValue
                        )
                        Text("Noise($noiseStr)")
                        Spacer(Modifier.width(16.dp))
                        Slider(
                            value = noiseState.floatValue,
                            onValueChange = { noiseState.floatValue = it },
                            valueRange = 0.0f..1.0f
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val sigmaStr = String.format(
                            locale = Locale.ENGLISH,
                            format = "%.1f", sigmaState.floatValue
                        )
                        Text("Sigma($sigmaStr)")
                        Spacer(Modifier.width(16.dp))
                        Slider(
                            value = sigmaState.floatValue,
                            onValueChange = { sigmaState.floatValue = it },
                            valueRange = 0.5f..20.0f
                        )
                    }
                }
            }
        }
        LaunchedEffect(sheetState) {
            snapshotFlow { sheetState.requireOffset() }
                .distinctUntilChanged()
                .collect {
                    Log.d(BOTTOM_SHEET_TAG, "sheet offset=$it")
                }
        }
    }

    @Composable
    private fun SceneImageViewerOverlay(
        imageUrl: String,
        blurSigma: Float,
        tintAlpha: Float,
        onDismiss: () -> Unit
    ) {
        AnimatedVisibility(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(20f),
            visible = imageUrl.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val isVisible = imageUrl.isNotEmpty()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .effectLayer(
                        style = DemoEffectStyle.default.copy(
                            sigma = blurSigma,
                            tint = lerp(
                                Color.Transparent,
                                Color.White,
                                tintAlpha
                            ),
                            noiseAlpha = 0.05f
                        ),
                        zIndex = 20f
                    ),
                contentAlignment = Alignment.Center
            ) {
                SimpleImageViewer(
                    modifier = Modifier.fillMaxSize(),
                    imageUrl = imageUrl,
                    onDismiss = onDismiss,
                    isVisible = isVisible
                )
            }
        }
    }

    @Composable
    private fun SocialFeedDemoScene(modifier: Modifier = Modifier) {
        val viewingImage = remember { mutableStateOf("") }
        val showBottomSheet = remember { mutableStateOf(false) }
        val debugMenuOpen = remember { mutableStateOf(false) }
        val blurAnimationValue = remember { Animatable(0f) }
        val tintAnimationValue = remember { Animatable(0f) }

        LaunchedEffect(viewingImage.value.isNotEmpty()) {
            val durationMillis = 400
            if (viewingImage.value.isNotEmpty()) {
                launch {
                    blurAnimationValue.animateTo(
                        targetValue = 14f,
                        animationSpec = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing)
                    )
                }
                launch {
                    tintAnimationValue.animateTo(
                        targetValue = 0.15f,
                        animationSpec = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing)
                    )
                }
            } else {
                launch {
                    blurAnimationValue.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing)
                    )
                }
                launch {
                    tintAnimationValue.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing)
                    )
                }
            }
        }

        ImlaHost(modifier = modifier) {
            Box(modifier = Modifier.fillMaxSize()) {
                Surface(
                    Modifier
                        .fillMaxSize()
                        .effectGroup(),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Content(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                top = 64.dp + WindowInsets.statusBars.asPaddingValues()
                                    .calculateTopPadding(),
                                bottom = 64.dp + WindowInsets.navigationBars.asPaddingValues()
                                    .calculateBottomPadding() + 16.dp
                            ),
                            onImageClick = { viewingImage.value = it },
                        )

                        BottomNavigationGlass(
                            selectedIndex = 0,
                            onItemSelected = {},
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )

                        BlurredTopBar(
                            modifier = Modifier.align(Alignment.TopCenter),
                            onSettingsClick = { showBottomSheet.value = true },
                            debugMenuOpen = debugMenuOpen.value,
                            onDebugMenuToggle = { debugMenuOpen.value = !debugMenuOpen.value }
                        )

                        BlurSettingsBottomSheet(
                            visible = showBottomSheet.value,
                            onDismissRequest = { showBottomSheet.value = false }
                        )

                        SceneImageViewerOverlay(
                            imageUrl = viewingImage.value,
                            blurSigma = blurAnimationValue.value,
                            tintAlpha = tintAnimationValue.value,
                            onDismiss = { viewingImage.value = "" }
                        )

                        AtlasDiagnosticProofSlot(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(x = 24.dp, y = 144.dp)
                        )

                        if (BuildConfig.DEBUG && debugMenuOpen.value) {
                            DebugStatsDropdown(
                                isOpen = debugMenuOpen.value,
                                onDismiss = { debugMenuOpen.value = false }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CubeDemoScene(modifier: Modifier = Modifier) {
        ImlaHost(modifier = modifier) {
            Surface(
                Modifier
                    .fillMaxSize()
                    .effectGroup()
            ) {
                FauxCube(modifier = Modifier.fillMaxSize())
            }
        }
    }

    @Composable
    private fun RotatingCardsScene(modifier: Modifier = Modifier) {
        val vibrantColors = remember {
            listOf(
                Color(0xFFFF6B6B),
                Color(0xFF4ECDC4),
                Color(0xFF45B7D1),
                Color(0xFF96CEB4),
                Color(0xFFFECA57),
                Color(0xFFFF9FF3),
                Color(0xFF54A0FF),
                Color(0xFF48DBFB),
                Color(0xFFFD79A8)
            )
        }
        val transition = rememberInfiniteTransition(label = "blobs")
        val blobProgress = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 8000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "blob-progress"
        )
        ImlaHost(modifier = modifier) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .effectGroup()
            ) {
                androidx.compose.foundation.Canvas(
                    modifier = Modifier.fillMaxSize()
                ) {
                    drawRect(Color(0xFF0D0830))
                    val w = size.width
                    val h = size.height
                    val t = blobProgress.value * 2f * kotlin.math.PI.toFloat()
                    fun blob(color: Color, cx: Float, cy: Float, r: Float) {
                        val off = androidx.compose.ui.geometry.Offset(cx, cy)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(color, Color.Transparent),
                                center = off,
                                radius = r
                            ),
                            radius = r,
                            center = off,
                            blendMode = androidx.compose.ui.graphics.BlendMode.Screen
                        )
                    }
                    // large slow anchors — all multipliers are integers so positions
                    // at t=0 and t=2π are identical, giving seamless loops
                    blob(Color(0xFFFF0033),
                        w * 0.18f + kotlin.math.sin(t) * w * 0.22f,
                        h * 0.20f + kotlin.math.cos(2f * t) * h * 0.14f,
                        w * 0.72f)
                    blob(Color(0xFF00EEDD),
                        w * 0.80f + kotlin.math.cos(t) * w * 0.18f,
                        h * 0.36f + kotlin.math.sin(2f * t) * h * 0.16f,
                        w * 0.66f)
                    blob(Color(0xFFFFCC00),
                        w * 0.50f + kotlin.math.cos(2f * t) * w * 0.28f,
                        h * 0.70f + kotlin.math.sin(t) * h * 0.16f,
                        w * 0.64f)
                    blob(Color(0xFFDD00FF),
                        w * 0.26f + kotlin.math.sin(3f * t) * w * 0.24f,
                        h * 0.80f + kotlin.math.cos(t) * h * 0.14f,
                        w * 0.62f)
                    // smaller fast accents
                    blob(Color(0xFFFF6600),
                        w * 0.88f + kotlin.math.sin(2f * t) * w * 0.10f,
                        h * 0.12f + kotlin.math.cos(3f * t) * h * 0.10f,
                        w * 0.40f)
                    blob(Color(0xFF00AAFF),
                        w * 0.10f + kotlin.math.cos(2f * t) * w * 0.08f,
                        h * 0.55f + kotlin.math.sin(3f * t) * h * 0.12f,
                        w * 0.38f)
                    blob(Color(0xFF00FF66),
                        w * 0.65f + kotlin.math.sin(t) * w * 0.14f,
                        h * 0.90f + kotlin.math.cos(2f * t) * h * 0.08f,
                        w * 0.36f)
                    blob(Color(0xFFFF007F),
                        w * 0.42f + kotlin.math.cos(3f * t) * w * 0.12f,
                        h * 0.45f + kotlin.math.sin(2f * t) * h * 0.10f,
                        w * 0.32f)
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(0.9f)
                        .aspectRatio(1f)
                ) {
                    val tileSize = 110.dp
                    Rotating3dBlurTile(size = tileSize, modifier = Modifier.align(Alignment.TopStart).zIndex(1f), startDelay = 1000, zIndex = 1f, tint = vibrantColors[0])
                    Rotating3dBlurTile(size = tileSize, modifier = Modifier.align(Alignment.TopCenter).zIndex(2f), startDelay = 2000, zIndex = 2f, tint = vibrantColors[1])
                    Rotating3dBlurTile(size = tileSize, modifier = Modifier.align(Alignment.TopEnd).zIndex(3f), startDelay = 3000, zIndex = 3f, tint = vibrantColors[2])
                    Rotating3dBlurTile(size = tileSize, modifier = Modifier.align(Alignment.CenterStart).zIndex(4f), startDelay = 4000, zIndex = 4f, tint = vibrantColors[3])
                    Rotating3dBlurTile(size = tileSize, modifier = Modifier.align(Alignment.Center).zIndex(5f), startDelay = 5000, zIndex = 5f, tint = vibrantColors[4])
                    Rotating3dBlurTile(size = tileSize, modifier = Modifier.align(Alignment.CenterEnd).zIndex(6f), startDelay = 6000, zIndex = 6f, tint = vibrantColors[5])
                    Rotating3dBlurTile(size = tileSize, modifier = Modifier.align(Alignment.BottomStart).zIndex(7f), startDelay = 7000, zIndex = 7f, tint = vibrantColors[6])
                    Rotating3dBlurTile(size = tileSize, modifier = Modifier.align(Alignment.BottomCenter).zIndex(8f), startDelay = 8000, zIndex = 8f, tint = vibrantColors[7])
                    Rotating3dBlurTile(size = tileSize, modifier = Modifier.align(Alignment.BottomEnd).zIndex(9f), startDelay = 9000, zIndex = 9f, tint = vibrantColors[8])
                }
            }
        }
    }

    override fun onDestroy() {
        stopIdlenessTracking()
        super.onDestroy()
    }

    private fun stopIdlenessTracking() {
        idlenessJob?.cancel()
        idlenessJob = null
    }

    private fun startIdlenessTracking() {
        if (idlenessJob != null) return
        val contentView: View = findViewById(android.R.id.content)
        idlenessJob = lifecycleScope.launch {
            while (isActive) {
                if (Recomposer.runningRecomposers.value.any { it.hasPendingWork }) {
                    contentView.contentDescription = "COMPOSE-BUSY"
                } else {
                    contentView.contentDescription = "COMPOSE-IDLE"
                }
                delay(250)
            }
        }
    }

    @Composable
    private fun BouncingDvdBlurTile(
        blurSize: Dp,
        maxWidth: Dp,
        maxHeight: Dp
    ) {
        val density = LocalDensity.current
        val bounds = remember(blurSize, maxWidth, maxHeight, density) {
            val blurSizePx = with(density) { blurSize.toPx() }
            val maxXpx = with(density) { maxWidth.toPx() } - blurSizePx
            val maxYpx = with(density) { maxHeight.toPx() } - blurSizePx
            BouncingBounds(
                minX = 0f,
                minY = 0f,
                maxX = maxXpx.coerceAtLeast(0f),
                maxY = maxYpx.coerceAtLeast(0f),
            )
        }
        val positionPx = remember { mutableStateOf(Offset(bounds.minX, bounds.minY)) }
        LaunchedEffect(bounds) {
            positionPx.value = Offset(
                x = positionPx.value.x.coerceIn(bounds.minX, bounds.maxX),
                y = positionPx.value.y.coerceIn(bounds.minY, bounds.maxY),
            )
            var velocity = Offset(180f, 140f) // px per second
            var lastFrame = 0L
            while (true) {
                withFrameNanos { now ->
                    if (lastFrame == 0L) {
                        lastFrame = now
                        return@withFrameNanos
                    }
                    val dt = (now - lastFrame) / 1_000_000_000f
                    lastFrame = now

                    var newX = positionPx.value.x + velocity.x * dt
                    var newY = positionPx.value.y + velocity.y * dt
                    if (newX < bounds.minX) {
                        newX = bounds.minX
                        velocity = velocity.copy(x = -velocity.x)
                    } else if (newX > bounds.maxX) {
                        newX = bounds.maxX
                        velocity = velocity.copy(x = -velocity.x)
                    }
                    if (newY < bounds.minY) {
                        newY = bounds.minY
                        velocity = velocity.copy(y = -velocity.y)
                    } else if (newY > bounds.maxY) {
                        newY = bounds.maxY
                        velocity = velocity.copy(y = -velocity.y)
                    }

                    positionPx.value = Offset(newX, newY)
                }
            }
        }

        Box(
            modifier = Modifier
                .size(blurSize)
                .graphicsLayer {
                    translationX = positionPx.value.x
                    translationY = positionPx.value.y
                }
                .effectLayer(
                    debugName = "dvd",
                    clipShape = CircleShape,
                    style = DemoEffectStyle.default.copy(tint = Color.Red.copy(alpha = 0.5f)),
                    zIndex = 1f
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "IMLA",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp
            )
        }
    }

    @Composable
    private fun Rotating3dBlurTile(
        size: Dp,
        modifier: Modifier = Modifier,
        startDelay: Int,
        zIndex: Float,
        tint: Color,
    ) {
        val transition = rememberInfiniteTransition(label = "3d-blur-rotation")
        val repeatMode = RepeatMode.Restart

        val rotationX = transition.animateFloat(
            initialValue = -180f,
            targetValue = 180f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 7000,
                    delayMillis = startDelay,
                    easing = LinearEasing
                ),
                repeatMode = repeatMode
            ),
            label = "3d-blur-rotation-x"
        )
        val rotationY = transition.animateFloat(
            initialValue = -180f,
            targetValue = 180f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 11000,
                    delayMillis = startDelay / 2,
                    easing = LinearEasing
                ),
                repeatMode = repeatMode
            ),
            label = "3d-blur-rotation-y"
        )
        val rotationZ = transition.animateFloat(
            initialValue = -180f,
            targetValue = 180f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 17000,
                    delayMillis = startDelay / 3,
                    easing = LinearEasing
                ),
                repeatMode = repeatMode
            ),
            label = "3d-blur-rotation-z"
        )
        val density = LocalDensity.current
        val cameraDistancePx = with(density) { 10.dp.toPx() }
        val style = remember {
            DemoEffectStyle.default.copy(
                sigma = 28f,
                blurOpacity = 1f,
                tint = tint.copy(alpha = 0.05f),
                noiseAlpha = 0.35f
            )
        }
        val shape = remember { RoundedCornerShape(22.dp) }

        Box(
            modifier = modifier
                .size(size)
                .graphicsLayer {
                    this.rotationX = rotationX.value
                    this.rotationY = rotationY.value
                    this.rotationZ = rotationZ.value
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                    cameraDistance = cameraDistancePx
                }
                .effectLayer(
                    debugName = "3d-tilt",
                    style = style,
                    clipShape = shape,
                    zIndex = zIndex
                )
                .border(1.dp, Color.Cyan.copy(alpha = 0.5f), shape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Imla",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = "X/Y/Z rotation",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp
                )
            }
        }
    }

    private data class BouncingBounds(
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float,
    )

    private fun LayoutCoordinates.rootSize(): IntSize {
        var current: LayoutCoordinates = this
        while (true) {
            val parent = current.parentLayoutCoordinates ?: break
            current = parent
        }
        return current.size
    }

    private companion object {
        private const val ATLAS_DIAGNOSTIC_TAG = "ImlaSceneAtlas"
        private const val ATLAS_DIAGNOSTIC_BUILD_TYPE = "benchmark"
        private const val BOTTOM_SHEET_TAG = "BottomSheetDebug"
        private const val PLAIN_ROOT_LIST_ITEM_COUNT = 2_000
        private val PLAIN_ROOT_LIST_COLORS = listOf(
            Color(0xFFE53935), // red
            Color(0xFFFB8C00), // orange
            Color(0xFFFDD835), // yellow
            Color(0xFF43A047), // green
            Color(0xFF1E88E5), // blue
            Color(0xFF8E24AA), // purple
            Color(0xFF00897B), // teal
            Color(0xFFD81B60), // pink
        )
    }
}
