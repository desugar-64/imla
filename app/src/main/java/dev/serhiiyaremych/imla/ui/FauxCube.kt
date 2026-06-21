package dev.serhiiyaremych.imla.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.serhiiyaremych.imla.EffectLayerBoundsProvider
import dev.serhiiyaremych.imla.effectLayer

@Composable
fun FauxCube(
    modifier: Modifier = Modifier
) {
    val faceSize = 200.dp
    val density = LocalDensity.current
    val faceSizePx = with(density) { faceSize.toPx() }
    val offset = faceSizePx / 2f

    // Rotation controls
    var rotationX by remember { mutableFloatStateOf(0f) }
    var rotationY by remember { mutableFloatStateOf(0f) }
    var rotationZ by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
    ) {
        // Cube Container
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            // Parent Container - Static Holder (No Camera Projection Here to avoid flattening)
            Box(
                modifier = Modifier.size(faceSize)
            ) {
                // Front Face
                CubeFace(
                    globalRotX = rotationX, globalRotY = rotationY, globalRotZ = rotationZ,
                    translationZ = offset
                )

                // Back Face
                CubeFace(
                    globalRotX = rotationX, globalRotY = rotationY, globalRotZ = rotationZ,
                    localRotY = 180f, translationZ = offset
                )

                // Left Face
                CubeFace(
                    globalRotX = rotationX, globalRotY = rotationY, globalRotZ = rotationZ,
                    localRotY = -90f, translationZ = offset
                )

                // Right Face
                CubeFace(
                    globalRotX = rotationX, globalRotY = rotationY, globalRotZ = rotationZ,
                    localRotY = 90f, translationZ = offset
                )

                // Top Face
                CubeFace(
                    globalRotX = rotationX, globalRotY = rotationY, globalRotZ = rotationZ,
                    localRotX = -90f, translationZ = offset
                )

                // Bottom Face
                CubeFace(
                    globalRotX = rotationX, globalRotY = rotationY, globalRotZ = rotationZ,
                    localRotX = 90f, translationZ = offset
                )
            }
        }

        // Sliders
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            RotationSlider(
                label = "Rotation X",
                value = rotationX,
                onValueChange = { rotationX = it }
            )
            RotationSlider(
                label = "Rotation Y",
                value = rotationY,
                onValueChange = { rotationY = it }
            )
            RotationSlider(
                label = "Rotation Z",
                value = rotationZ,
                onValueChange = { rotationZ = it }
            )
        }
    }
}

@Composable
private fun CubeFace(
    modifier: Modifier = Modifier,
    globalRotX: Float,
    globalRotY: Float,
    globalRotZ: Float,
    localRotX: Float = 0f,
    localRotY: Float = 0f,
    translationZ: Float = 0f
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .cameraTransform(
                globalRotX = globalRotX,
                globalRotY = globalRotY,
                globalRotZ = globalRotZ,
                localRotX = localRotX,
                localRotY = localRotY,
                translationZ = translationZ
            )
            .effectLayer()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFCC0000).copy(alpha = 0.5f))
                .border(width = 2.dp, color = Color(0xFFFF4500))
        )
    }
}

@Composable
private fun RotationSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Column {
        Text(
            text = "$label: ${value.toInt()}°",
            color = Color.White
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..360f
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

private fun Modifier.cameraTransform(
    globalRotX: Float,
    globalRotY: Float,
    globalRotZ: Float,
    localRotX: Float,
    localRotY: Float,
    translationZ: Float
) = this.drawWithContent {
    val camera = android.graphics.Camera()
    val matrix = android.graphics.Matrix()

    camera.save()

    // Increase Camera Distance (default is usually -8 inches approx in Android Camera coords)
    // Moving it further back reduces FOV/Structure distortion.
    camera.setLocation(0f, 0f, -30f) // Adjusted to -30 based on 'more camera distance' request

    // Single Matrix Strategy:
    // 1. Apply Global Rotation (Rotate the 'World').
    // 2. Apply Local Construction (Rotate face to angle, then Translate out).
    // Note on Order: camera.rotate then camera.translate in sequence creates R * T matrix.

    // Global Rotation
    camera.rotateX(globalRotX)
    camera.rotateY(globalRotY)
    camera.rotateZ(-globalRotZ)

    // Local Construction
    camera.rotateX(localRotX)
    camera.rotateY(localRotY)
    camera.translate(0f, 0f, translationZ)

    camera.getMatrix(matrix)
    camera.restore()

    // Pivot logic
    matrix.preTranslate(-size.width / 2f, -size.height / 2f)
    matrix.postTranslate(size.width / 2f, size.height / 2f)

    drawContext.canvas.nativeCanvas.concat(matrix)

    drawContent()
}
