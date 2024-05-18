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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.serhiiyaremych.imla.data.ApiClient
import dev.serhiiyaremych.imla.modifier.blurSource
import dev.serhiiyaremych.imla.ui.BackdropBlurView
import dev.serhiiyaremych.imla.ui.theme.ImlaTheme
import dev.serhiiyaremych.imla.ui.userpost.SimpleImageViewer
import dev.serhiiyaremych.imla.ui.userpost.UserPostView
import dev.serhiiyaremych.imla.uirenderer.Style
import dev.serhiiyaremych.imla.uirenderer.UiLayerRenderer
import dev.serhiiyaremych.imla.uirenderer.rememberUiLayerRenderer
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.distinctUntilChanged

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
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
                val viewingImage = remember {
                    mutableStateOf("")
                }
                Box(modifier = Modifier.fillMaxWidth()) {
                    // Layer 0 above full height content
                    BlurryTopAppBar(uiRenderer)
                    // Full height content
                    Surface(
                        Modifier
                            .fillMaxSize()
                            .blurSource(uiRenderer),
                    ) {
                        Content(modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = TopAppBarDefaults.MediumAppBarExpandedHeight),
                            onImageClick = { viewingImage.value = it },
                            onScroll = { uiRenderer.onUiLayerUpdated() })
                    }
                    // Layer 1 full height content
                    BlurryBottomNavBar(uiRenderer)

                    AnimatedVisibility(
                        modifier = Modifier
                            .zIndex(2f)
                            .matchParentSize(),
                        visible = viewingImage.value.isNotEmpty(),
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        BackdropBlurView(
                            modifier = Modifier.matchParentSize(),
                            uiLayerRenderer = uiRenderer,
                            style = Style(4.dp, Color.Green.copy(alpha = 0.3f), 1.0f)
                        ) {
                            SimpleImageViewer(modifier = Modifier.fillMaxSize(),
                                imageUrl = viewingImage.value,
                                onDismiss = { viewingImage.value = "" })
                        }
                        DisposableEffect(key1 = Unit) {
                            onDispose { uiRenderer.onUiLayerUpdated() }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun BoxScope.BlurryBottomNavBar(uiRenderer: UiLayerRenderer) {
        BackdropBlurView(
            modifier = Modifier
                .zIndex(1f)
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Bottom)
                .align(Alignment.BottomCenter)
                .shadow(8.dp, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .border(
                    Dp.Hairline,
                    Color.DarkGray,
                    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                ),
            uiLayerRenderer = uiRenderer,
            style = Style(4.dp, Color.Cyan.copy(alpha = 0.3f), 0.0f)
        ) {
            NavigationBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                windowInsets = WindowInsets(bottom = 0.dp),
                containerColor = Color.Transparent
            ) {
                NavigationBarItem(selected = false, onClick = { /*TODO*/ }, icon = {
                    Icon(
                        imageVector = Icons.Filled.Home, contentDescription = null
                    )
                })
                NavigationBarItem(selected = false, onClick = { /*TODO*/ }, icon = {
                    Icon(
                        imageVector = Icons.Filled.Search, contentDescription = null
                    )
                })
                NavigationBarItem(selected = false, onClick = { /*TODO*/ }, icon = {
                    Icon(
                        imageVector = Icons.Filled.Notifications, contentDescription = null
                    )
                })
                NavigationBarItem(selected = false, onClick = { /*TODO*/ }, icon = {
                    Icon(
                        imageVector = Icons.Filled.Settings, contentDescription = null
                    )
                })

            }
        }
    }

    @Composable
    @OptIn(ExperimentalMaterial3Api::class)
    private fun BlurryTopAppBar(uiRenderer: UiLayerRenderer) {
        BackdropBlurView(
            modifier = Modifier
                .zIndex(1f)
                .wrapContentHeight(align = Alignment.Top)
                .shadow(2.dp)
                .border(Dp.Hairline, Color.DarkGray),
            uiLayerRenderer = uiRenderer,
            style = Style(4.dp, Color.Red.copy(alpha = 0.3f), 0.0f)
        ) {
            TopAppBar(
                modifier = Modifier.padding(top = 16.dp),
                title = { Text("Blur Demo") },
                windowInsets = WindowInsets(top = 0.dp),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = { /* "Open nav drawer" */ }) {
                        Icon(Icons.Filled.Menu, contentDescription = null)
                    }
                }
            )
        }
    }

    @Composable
    private fun Content(
        modifier: Modifier,
        contentPadding: PaddingValues,
        onImageClick: (String) -> Unit,
        onScroll: (Int) -> Unit
    ) {
        val scrollState = rememberLazyListState()
        val currentOnScroll = rememberUpdatedState(onScroll).value
        LaunchedEffect(key1 = scrollState, key2 = onScroll) {
            snapshotFlow { scrollState.firstVisibleItemScrollOffset }.distinctUntilChanged()
                .collect {
                    currentOnScroll(it)
                }
        }
        val posts =
            ApiClient.getPosts().collectAsStateWithLifecycle(initialValue = persistentListOf())
        LazyColumn(modifier = modifier, state = scrollState, contentPadding = contentPadding) {
            items(posts.value, key = { it.id }) { item ->
                UserPostView(
                    modifier = Modifier.fillMaxWidth(), post = item, onImageClick = onImageClick
                )
            }
        }

    }
}