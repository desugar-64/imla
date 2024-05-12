/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.ui.userpost

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.serhiiyaremych.imla.ui.theme.ImlaTheme

const val LOW_IMAGE_QUALITY = 0.3f
const val HIGH_IMAGE_QUALITY = 0.6f

private val imageCorners = RoundedCornerShape(4.dp)
private val startCorners = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)
private val endCorners = RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
private val topStartCorner = RoundedCornerShape(topStart = 4.dp)
private val topEndCorner = RoundedCornerShape(topEnd = 4.dp)
private val bottomStartCorner = RoundedCornerShape(bottomStart = 4.dp)
private val bottomEndCorner = RoundedCornerShape(bottomEnd = 4.dp)

/*
Mono image layout:
--------
|      |
|  A   |
|      |
--------
*/
@Composable
fun MonoImage(
    imageUrl: String,
    imageHeight: Dp,
    compactMode: Boolean,
    onImageClick: (String) -> Unit
) {
    SinglePostImage(
        modifier = Modifier
            .height(imageHeight)
            .fillMaxWidth()
            .clickable { onImageClick(imageUrl) },
        clipShape = imageCorners,
        imgUrl = imageUrl,
        compactMode = compactMode
    )
}

/*
Duo image layout:
--------
|   |   |
| A | B |
|   |   |
--------
*/
@Composable
fun DuoImage(
    imgA: String,
    imgB: String,
    imageHeight: Dp,
    compactMode: Boolean,
    onImageClick: (String) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        // Image A
        SinglePostImage(
            modifier = Modifier
                .height(imageHeight)
                .weight(1.0f)
                .clickable { onImageClick(imgA) },
            clipShape = startCorners,
            imgUrl = imgA,
            compactMode = compactMode
        )
        Spacer(modifier = Modifier.width(4.dp))
        // Image B
        SinglePostImage(
            modifier = Modifier
                .height(imageHeight)
                .weight(1.0f)
                .clickable { onImageClick(imgB) },
            clipShape = endCorners,
            imgUrl = imgB,
            compactMode = compactMode
        )
    }
}

/*
Triple image layout:
--------
|   | B |
| A |-- |
|   | C |
--------
*/
@Composable
fun TripleImage(
    imageHeight: Dp,
    imgA: String,
    imgB: String,
    imgC: String,
    compactMode: Boolean,
    onImageClick: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        // Image A
        SinglePostImage(
            modifier = Modifier
                .height(imageHeight)
                .weight(1.0f)
                .clickable { onImageClick(imgA) },
            clipShape = startCorners,
            imgUrl = imgA,
            compactMode = compactMode
        )
        Spacer(modifier = Modifier.width(4.dp))
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1.0f)
        ) {
            var imgBCSize by remember { mutableStateOf(IntSize.Zero) }
            // Image B
            SinglePostImage(
                modifier = Modifier
                    .height((imageHeight / 2) - 2.dp)
                    .fillMaxWidth()
                    .onPlaced { imgBCSize = it.size }
                    .clickable { onImageClick(imgB) },
                clipShape = topEndCorner,
                imgUrl = imgB,
                compactMode = compactMode
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Image C
            SinglePostImage(
                modifier = Modifier
                    .height((imageHeight / 2) - 2.dp)
                    .fillMaxWidth()
                    .clickable { onImageClick(imgC) },
                clipShape = bottomEndCorner,
                imgUrl = imgC,
                compactMode = compactMode
            )
        }

    }
}

/*
Quad image layout:
--------
| A | B |
| --|-- |
| C | D |
--------
*/
@Composable
fun QuadImage(
    imageHeight: Dp,
    imgA: String,
    imgB: String,
    imgC: String,
    imgD: String,
    compactMode: Boolean,
    onImageClick: (String) -> Unit
) {
    // static 2x2 grid
    val cols = 2
    Layout(
        modifier = Modifier
            .height(imageHeight)
            .fillMaxWidth(),
        content = {
            SinglePostImage(
                modifier = Modifier
                    .padding(end = 2.dp, bottom = 2.dp)
                    .clickable { onImageClick(imgA) },
                clipShape = topStartCorner,
                imgUrl = imgA,
                compactMode = compactMode
            )
            SinglePostImage(
                modifier = Modifier
                    .padding(start = 2.dp, bottom = 2.dp)
                    .clickable { onImageClick(imgB) },
                clipShape = topEndCorner,
                imgUrl = imgB,
                compactMode = compactMode
            )
            SinglePostImage(
                modifier = Modifier
                    .padding(top = 2.dp, end = 2.dp)
                    .clickable { onImageClick(imgC) },
                clipShape = bottomStartCorner,
                imgUrl = imgC,
                compactMode = compactMode
            )
            SinglePostImage(
                modifier = Modifier
                    .padding(top = 2.dp, start = 2.dp)
                    .clickable { onImageClick(imgD) },
                clipShape = bottomEndCorner,
                imgUrl = imgD,
                compactMode = compactMode
            )
        }
    ) { measurables, constraints ->
        val rows = (measurables.size / cols) + measurables.size % cols
        val childWidth = constraints.maxWidth / cols
        val childHeight = constraints.maxHeight / rows

        val children = measurables.map { child ->
            child.measure(
                constraints.copy(
                    minWidth = childWidth,
                    minHeight = childHeight,
                    maxWidth = childWidth,
                    maxHeight = childHeight
                )
            )
        }

        layout(constraints.maxWidth, constraints.maxHeight) {
            children.forEachIndexed { idx, child ->
                val row = idx / cols
                val col = idx - (row * cols)

                val offset = IntOffset(
                    x = col * child.measuredWidth,
                    y = row * child.measuredHeight
                )
                child.placeRelative(offset)
            }
        }
    }
}


@Composable
fun SinglePostImage(
    modifier: Modifier,
    clipShape: Shape,
    imgUrl: String,
    compactMode: Boolean
) {
    val imageLayoutSize = remember {
        mutableStateOf(IntSize.Zero)
    }
    val context = LocalContext.current
    val imageSize = imageLayoutSize.value
    val request = remember(imageSize, imgUrl, context, compactMode) {
        ImageRequest.Builder(context)
            .data(imgUrl)
            .setImageSize(
                width = imageSize.width,
                height = imageSize.height,
                compactMode = compactMode
            )
            .crossfade(false)
            .build()
    }
    AsyncImage(
        modifier = modifier
            .clip(clipShape)
            .onPlaced { imageLayoutSize.value = it.size }
            .let { if (LocalInspectionMode.current) it.background(Color.Magenta) else it },
        model = request,
        contentScale = ContentScale.Crop,
        contentDescription = null
    )
}

/*
Image row layout:
-------------------
| A | B | C | ... |
-------------------
*/
@Composable
fun ImageRow(
    imageHeight: Dp,
    images: List<String>,
    compactMode: Boolean,
    onImageClick: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(state = rememberScrollState())
    ) {
        images.forEachIndexed { idx, image ->
            key(image) {
                val clipper = when (idx) {
                    0 -> startCorners
                    images.lastIndex -> endCorners
                    else -> RectangleShape
                }
                SinglePostImage(
                    modifier = Modifier
                        .size(imageHeight)
                        .clickable { onImageClick(image) },
                    clipShape = clipper,
                    imgUrl = image,
                    compactMode = compactMode
                )
                if (idx < images.lastIndex) {
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }
        }
    }
}


private fun ImageRequest.Builder.setImageSize(
    width: Int,
    height: Int,
    compactMode: Boolean
): ImageRequest.Builder {
    val quality = if (compactMode) LOW_IMAGE_QUALITY else HIGH_IMAGE_QUALITY
    return if (width > 0 && height > 0) size(
        width = (width * quality).toInt(),
        height = (height * quality).toInt()
    ) else this
}

@Preview
@Composable
private fun MonoPreview() {
    ImlaTheme {
        MonoImage(imageUrl = "", imageHeight = 150.dp, compactMode = false) {}
    }
}

@Preview
@Composable
private fun DuoPreview() {
    ImlaTheme {
        DuoImage(imgA = "", imgB = "", imageHeight = 150.dp, compactMode = false) {}
    }
}

@Preview
@Composable
private fun TriplePreview() {
    ImlaTheme {
        TripleImage(imgA = "", imgB = "", imgC = "", imageHeight = 150.dp, compactMode = false) {}
    }
}







