/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render.processing.effects

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import dev.romainguy.kotlin.math.Float4
import dev.serhiiyaremych.imla.internal.render.Float2
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.internal.render.x
import dev.serhiiyaremych.imla.internal.render.y

/**
 * FBO wrapper that tracks content size for correct UV calculation.
 * Content is always centered within the (possibly bucketed) FBO.
 *
 * @property fbo The underlying (possibly bucketed) framebuffer
 * @property contentSize The logical content size we requested
 */
internal data class SizedFramebuffer(
    val fbo: Framebuffer,
    val contentSize: IntSize
) {
    /** The actual allocated FBO size (may be bucketed) */
    val allocatedSize: IntSize
        get() = fbo.specification.size

    /** Content offset in pixels relative to the allocated FBO origin (content is centered) */
    val contentOffset: IntOffset
        get() = IntOffset(
            (allocatedSize.width - contentSize.width) / 2,
            (allocatedSize.height - contentSize.height) / 2
        )

    /** UV scale factor for full-content sampling */
    val uvScale: Float2
        get() = Float2(
            contentSize.width.toFloat() / allocatedSize.width.coerceAtLeast(1),
            contentSize.height.toFloat() / allocatedSize.height.coerceAtLeast(1)
        )

    /** Content offset in UV space (content is centered) */
    val uvOffset: Float2
        get() = Float2(
            contentOffset.x.toFloat() / allocatedSize.width.coerceAtLeast(1),
            contentOffset.y.toFloat() / allocatedSize.height.coerceAtLeast(1)
        )

    /** UV rect covering only the content area (centered in allocated FBO) */
    val uvBoundsRect: Float4
        get() = Float4(
            uvOffset.x,
            uvOffset.y,
            uvOffset.x + uvScale.x,
            uvOffset.y + uvScale.y
        )

    /** Get the color attachment texture */
    val texture: Texture2D
        get() = fbo.colorAttachmentTexture as Texture2D

    /**
     * Calculate where content of given size should be centered within this FBO.
     * Returns a pixel Rect representing the centered content area.
     *
     * @param contentSize Size of content to center (will not be scaled)
     * @return Rect in pixel coordinates (relative to allocated FBO origin)
     */
    fun calculateCenteredCrop(contentSize: IntSize): Rect {
        val offsetX = (allocatedSize.width - contentSize.width) / 2f
        val offsetY = (allocatedSize.height - contentSize.height) / 2f
        return Rect(
            offset = Offset(offsetX, offsetY),
            size = contentSize.toSize()
        )
    }

    /**
     * Calculate where scaled content should be centered within this FBO.
     *
     * @param contentSize Original content size
     * @param fitScale Scale factor applied to content
     * @return Rect in pixel coordinates (relative to allocated FBO origin)
     */
    fun calculateCenteredCrop(contentSize: IntSize, fitScale: Float): Rect {
        val scaledContent = contentSize.toSize() * fitScale
        val offsetX = (allocatedSize.width - scaledContent.width) / 2f
        val offsetY = (allocatedSize.height - scaledContent.height) / 2f
        return Rect(
            offset = Offset(offsetX, offsetY),
            size = scaledContent
        )
    }

    /**
     * Convert a pixel Rect to UV coordinates [0,1] for sampling.
     * The input Rect is in pixel coordinates relative to the allocated FBO origin.
     *
     * @param pixelRect Rect in pixel coordinates
     * @return Rect in UV coordinates [0,1]
     */
    fun toUvRect(pixelRect: Rect): Rect {
        return Rect(
            left = pixelRect.left / allocatedSize.width,
            top = pixelRect.top / allocatedSize.height,
            right = pixelRect.right / allocatedSize.width,
            bottom = pixelRect.bottom / allocatedSize.height
        )
    }

    /**
     * Calculate the scale factor needed to fit [inner] size within this FBO.
     * Returns a scale ≤ 1.0 (downscale only, never upscale).
     *
     * @param inner Size that needs to fit
     * @return Scale factor (≤ 1.0)
     */
    fun calculateFitScale(inner: IntSize): Float {
        val widthScale = contentSize.width / inner.width.toFloat()
        val heightScale = contentSize.height / inner.height.toFloat()
        return minOf(widthScale, heightScale).coerceAtMost(1f)
    }
}

/**
 * Extension to wrap a Framebuffer with explicit content size.
 */
internal fun Framebuffer.withSize(contentSize: IntSize): SizedFramebuffer = SizedFramebuffer(
    fbo = this,
    contentSize = contentSize
)