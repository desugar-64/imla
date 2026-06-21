/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.render

/**
 * Describes the coordinate system origin of texture data.
 *
 * OpenGL ES and Android Canvas use different Y-axis conventions:
 * - **OpenGL**: Origin at bottom-left, Y increases upward
 * - **Android Canvas/Screen**: Origin at top-left, Y increases downward
 *
 * This enum makes the texture's native coordinate system explicit,
 * eliminating ad-hoc flip decisions scattered throughout the rendering pipeline.
 *
 * ## Coordinate System Comparison
 *
 * ```
 * TOP_LEFT (Android Canvas)      BOTTOM_LEFT (OpenGL)
 *   (0,0) ────────→ x              x ←──────── (width,height)
 *     ↓                                           ↑
 *     ↓                                           ↓
 *     y                                           y
 *   (0,height)                      (0,0)
 * ```
 *
 * ## Usage Examples
 *
 * ### Declaring Texture Origins
 *
 * ```kotlin
 * // GraphicsLayer capture from Android Canvas
 * val rootLayerTexture = Texture2D.create(
 *     specification = Texture.Specification(
 *         size = IntSize(1080, 2400),
 *         coordinateOrigin = CoordinateOrigin.TOP_LEFT  // Android origin
 *     )
 * )
 *
 * // OpenGL Framebuffer (default)
 * val fboTexture = Texture2D.create(
 *     specification = Texture.Specification(
 *         size = IntSize(1080, 2400),
 *         coordinateOrigin = CoordinateOrigin.BOTTOM_LEFT  // OpenGL origin
 *     )
 * )
 * ```
 *
 * ### In Shaders
 *
 * ```glsl
 * // Fragment shader that needs to handle both coordinate systems
 * layout(location = 0) in vec2 texCoord;  // [0..1] in OpenGL space
 * uniform float u_TextureFlip;             // 1.0 if TOP_LEFT, 0.0 if BOTTOM_LEFT\n *
 * void main() {
 *     // Apply flip if texture origin doesn't match OpenGL convention
 *     vec2 finalUv = mix(
 *         texCoord,                                    // No flip
 *         vec2(texCoord.x, 1.0 - texCoord.y),         // Flip Y
 *         u_TextureFlip
 *     );
 *     gl_FragColor = texture(u_Texture, finalUv);
 * }
 * ```
 *
 * ### Framebuffer Creation
 *
 * ```kotlin
 * // FBO for blur output (always OpenGL convention)
 * val blurFbo = Framebuffer.create(
 *     FramebufferSpecification(
 *         size = IntSize(1080, 2400),
 *         attachmentsSpec = FramebufferAttachmentSpecification.singleColor(
 *             format = FramebufferTextureFormat.RGBA8,
 *             coordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
 *         )
 *     )
 * )
 *
 * // Mask texture captured from Canvas (pre-flipped)
 * val maskFbo = Framebuffer.create(
 *     FramebufferSpecification(
 *         size = IntSize(256, 256),
 *         attachmentsSpec = FramebufferAttachmentSpecification.singleColor(
 *             format = FramebufferTextureFormat.R8,
 *             coordinateOrigin = CoordinateOrigin.BOTTOM_LEFT  // Canvas content is pre-flipped
 *         )
 *     )
 * )
 * ```
 */
internal enum class CoordinateOrigin {
    /**
     * Origin at top-left, Y increases downward.
     *
     * Used by:
     * - Android Canvas and bitmap formats
     * - Android Screen coordinates
     * - Most image file formats (PNG, JPEG, WebP)
     * - GraphicsLayer captures
     *
     * Example: In a 1080×2400 viewport, pixel (540, 1200) is center-screen.
     */
    TOP_LEFT,

    /**
     * Origin at bottom-left, Y increases upward.
     *
     * Used by:
     * - OpenGL ES framebuffers and textures
     * - Normalized device coordinates (NDC)
     * - GPU fragment shader coordinate space
     *
     * Example: In a 1080×2400 viewport, pixel (540, 1200) is also center-screen
     * but represented as (540, 1200) from the bottom.
     */
    BOTTOM_LEFT;

    /**
     * Returns true if UV Y-coordinate needs to be flipped when sampling this
     * texture in a shader that expects BOTTOM_LEFT origin (OpenGL convention).
     *
     * OpenGL fragment shaders typically work in BOTTOM_LEFT coordinate space.
     * If the texture data is stored in TOP_LEFT convention (Android bitmap/Canvas),
     * sampling requires Y-flip: `uv.y = 1.0 - uv.y`
     *
     * @return `true` if `this == TOP_LEFT`, `false` otherwise
     *
     * ## Example
     * ```kotlin
     * val texture = graphicsLayerTexture  // Captured from Canvas (TOP_LEFT)
     * shader.setFloat("u_NeedsFlip", if (texture.coordinateOrigin.needsFlipForOpenGL()) 1f else 0f)
     * ```
     */
    fun needsFlipForOpenGL(): Boolean = this == TOP_LEFT

    companion object {
        /**
         * Compute whether UV flip is needed when sampling [source] texture
         * in a rendering context using [target] coordinate system.
         *
         * @param source The coordinate origin of the source texture data
         * @param target The coordinate origin expected by the rendering context (usually BOTTOM_LEFT)
         * @return `true` if source and target differ, `false` if they match
         *
         * ## Example
         * ```kotlin
         * val needsFlip = CoordinateOrigin.needsFlip(
         *     source = texture.coordinateOrigin,
         *     target = CoordinateOrigin.BOTTOM_LEFT  // OpenGL shader expects this
         * )
         * ```
         */
        fun needsFlip(source: CoordinateOrigin, target: CoordinateOrigin): Boolean {
            return source != target
        }
    }
}
