/*
 * Copyright 2026, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla

import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Capture-fidelity probe for issue #13. The captured backdrop holds three bands;
 * an Imla [effectLayer] covers the RIGHT half of all three. Compare left (raw view
 * tree, composited by SurfaceFlinger) against right (Imla's captured + blurred buffer):
 *
 *  A  plain Compose checkerboard          → control
 *  B  same checkerboard + Modifier.blur() → RenderEffect; is it replayed into capture?
 *  C  a live SurfaceView                  → is its separate surface captured, or a hole?
 */
@Composable
internal fun CaptureFidelityScene(modifier: Modifier = Modifier) {
    ImlaHost(modifier = modifier, showMetricsOverlay = false) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .effectGroup()
        ) {
            Column(Modifier.fillMaxSize()) {
                Band("A: plain compose", Modifier.weight(1f)) {
                    Checkerboard(Modifier.fillMaxSize(), Color(0xFFFF5252), Color(0xFF4CAF50))
                }
                Band("B: Modifier.blur (RenderEffect)", Modifier.weight(1f)) {
                    Checkerboard(Modifier.fillMaxSize().blur(28.dp), Color(0xFFFFC107), Color(0xFF3F51B5))
                }
                Band("C: SurfaceView", Modifier.weight(1f)) {
                    AndroidView(
                        factory = { ctx -> ColorSurfaceView(ctx) },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { it.stop() }
                    )
                }
            }

            // Imla effect layer over the right half: it blurs whatever it CAPTURED.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.5f)
                    .align(Alignment.CenterEnd)
                    .effectLayer { backdropBlur(sigmaPx = 18f) }
                    .drawBehind {
                        drawRect(Color.White, Offset.Zero, Size(3f, size.height))
                    }
            )
        }
    }
}

@Composable
private fun Band(label: String, modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth()) {
        content()
        BasicText(
            text = label,
            modifier = Modifier.padding(8.dp),
            style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        )
    }
}

@Composable
private fun Checkerboard(modifier: Modifier, colorA: Color, colorB: Color) {
    Box(
        modifier.drawBehind {
            val cell = 28.dp.toPx()
            var y = 0f
            var row = 0
            while (y < size.height) {
                var x = 0f
                var col = 0
                while (x < size.width) {
                    val on = (row + col) % 2 == 0
                    drawRect(
                        color = if (on) colorA else colorB,
                        topLeft = Offset(x, y),
                        size = Size(cell, cell)
                    )
                    x += cell; col++
                }
                y += cell; row++
            }
        }
    )
}

/** A SurfaceView that paints an animated solid color on its own render thread. */
private class ColorSurfaceView(context: android.content.Context) : SurfaceView(context) {
    @Volatile private var running = false
    private var thread: Thread? = null

    init {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = start()
            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = stop()
        })
    }

    private fun start() {
        if (running) return
        running = true
        thread = Thread {
            val paint = Paint()
            var t = 0
            while (running) {
                val canvas: Canvas = holder.lockCanvas() ?: break
                try {
                    canvas.drawColor(AndroidColor.BLACK)
                    val stripe = 36f
                    paint.color = AndroidColor.HSVToColor(floatArrayOf(300f * ((t % 100) / 100f), 0.9f, 1f))
                    var x = 0f
                    while (x < canvas.width) {
                        canvas.drawRect(x, 0f, x + stripe, canvas.height.toFloat(), paint)
                        x += stripe * 2
                    }
                    paint.color = AndroidColor.WHITE
                    paint.textSize = 48f
                    canvas.drawText("LIVE SURFACEVIEW", 24f, canvas.height / 2f, paint)
                } finally {
                    holder.unlockCanvasAndPost(canvas)
                }
                t++
                Thread.sleep(33)
            }
        }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(200)
        thread = null
    }
}
