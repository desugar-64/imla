package dev.serhiiyaremych.imla.internal.render.gl

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.tracing.trace
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.inverse
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.Float2
import dev.serhiiyaremych.imla.internal.render.RenderCommands
import dev.serhiiyaremych.imla.internal.render.Texture2D
import dev.serhiiyaremych.imla.internal.render.framebuffer.Bind
import dev.serhiiyaremych.imla.internal.render.framebuffer.Framebuffer
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferAttachmentSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferLendingPool
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferSpecification
import dev.serhiiyaremych.imla.internal.render.framebuffer.FramebufferTextureFormat
import dev.serhiiyaremych.imla.internal.render.gl.pipeline.SceneRenderBuffer
import dev.serhiiyaremych.imla.internal.render.shader.Shader
import dev.serhiiyaremych.imla.internal.render.shader.ShaderProgram
import dev.serhiiyaremych.imla.internal.render.processing.QuadBatchRenderer
import dev.serhiiyaremych.imla.internal.render.processing.RenderQuad
import dev.serhiiyaremych.imla.internal.render.processing.draw
import dev.serhiiyaremych.imla.internal.render.composeMatrixToMat4
import dev.serhiiyaremych.imla.internal.layer.model.SceneBackdropOperation
import dev.serhiiyaremych.imla.internal.layer.model.SceneSlotGeometry
import dev.serhiiyaremych.imla.internal.layer.model.SceneSlotId
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

internal data class BackdropInput(
    val texture: Texture2D,
    val size: IntSize,
    val coordinateOrigin: CoordinateOrigin
) {
    companion object {
        fun root(frame: SceneGlRenderFrame): BackdropInput {
            val texture = frame.rootTexture
            return BackdropInput(
                texture = texture,
                size = frame.rootSize,
                coordinateOrigin = texture.coordinateOrigin
            )
        }

        fun accumulatedScene(buffer: SceneRenderBuffer): BackdropInput {
            return BackdropInput(
                texture = buffer.texture,
                size = buffer.size,
                coordinateOrigin = buffer.texture.coordinateOrigin
            )
        }
    }
}

/**
 * Root-space backdrop sampling geometry.
 *
 * [visibleRootRect] is the transformed slot coverage in root pixels. [sampleRootRect] is the
 * expanded root crop prepared for blur. Rotated slots use a stable diagonal envelope instead of
 * tight projected bounds because changing the prepared texture footprint creates visible drift in
 * low-pass and blur bands. The crop is snapped to the downsample grid so a rotating slot does not
 * shift the source phase between neighboring prepared textures.
 */
internal data class BackdropSampleRegion(
    val visibleRootRect: Rect,
    val sampleRootRect: Rect
) {
    val visibleInSample: Rect
        get() {
            val width = sampleRootRect.width.coerceAtLeast(1e-6f)
            val height = sampleRootRect.height.coerceAtLeast(1e-6f)
            return Rect(
                left = (visibleRootRect.left - sampleRootRect.left) / width,
                top = (visibleRootRect.top - sampleRootRect.top) / height,
                right = (visibleRootRect.right - sampleRootRect.left) / width,
                bottom = (visibleRootRect.bottom - sampleRootRect.top) / height
            )
        }

    companion object {
        fun forBlur(
            slot: SceneGlRenderSlot,
            targetSize: IntSize,
            paddingPx: Float,
            downsampleScale: Float
        ): BackdropSampleRegion {
            val visibleRootRect = slot.geometry.rootQuad.axisAlignedBounds
            val padding = paddingPx.coerceAtLeast(0f)
            val sampleGridStepPx = 1f / downsampleScale.coerceAtLeast(1e-6f)
            val envelope = visibleRootRect.diagonalSampleEnvelope(slot.geometry.localSize, padding)
            // Snap-to-grid stops a rotating slot's downsampled texels crawling frame-to-frame.
            // For an axis-aligned slot it instead quantizes the envelope size, so a smooth resize
            // jumps a grid step at a time and the downsampled blur flashes -> snap only if rotated.
            val stabilized = if (slot.geometry.rootQuad.isAxisAligned) {
                envelope
            } else {
                envelope.snapToGrid(sampleGridStepPx)
            }
            return BackdropSampleRegion(
                visibleRootRect = visibleRootRect,
                sampleRootRect = stabilized.intersectOrEmpty(targetSize.toRect())
            )
        }
    }
}

internal data class PreparedBackdrop(
    val input: BackdropInput,
    val output: BorrowedBackdropTexture,
    val sampleRegion: BackdropSampleRegion,
    val downsampleScale: Float,
    val sourceTexelInputSize: Float2
) {
    val contentSize: IntSize get() = output.image.contentSize
    val outputPixels: Long get() = output.image.outputPixels
    val compositeInput: BackdropCompositeInput get() = output.image.compositeInput
}

internal data class ProcessedBackdrop(
    val source: PreparedBackdrop,
    val output: BorrowedBackdropTexture
) {
    val contentSize: IntSize get() = output.image.contentSize
    val outputPixels: Long get() = output.image.outputPixels
    val compositeInput: BackdropCompositeInput get() = output.image.compositeInput
}

internal data class BorrowedBackdropTexture(
    val image: BackdropTexture,
    val framebuffer: Framebuffer
)

internal data class BackdropTexture(
    val texture: Texture2D,
    val contentRect: IntRect
) {
    val contentSize: IntSize
        get() = IntSize(contentRect.width, contentRect.height)

    val outputPixels: Long
        get() = contentRect.width.toLong().coerceAtLeast(0L) *
                contentRect.height.toLong().coerceAtLeast(0L)

    val contentUv: Rect
        get() {
            val width = texture.width.toFloat().coerceAtLeast(1f)
            val height = texture.height.toFloat().coerceAtLeast(1f)
            return Rect(
                left = contentRect.left / width,
                top = 1f - contentRect.bottom / height,
                right = contentRect.right / width,
                bottom = 1f - contentRect.top / height
            )
        }

    val compositeInput: BackdropCompositeInput
        get() = BackdropCompositeInput(
            texture = texture,
            contentUv = contentUv,
            coordinateOrigin = texture.coordinateOrigin
        )
}

internal data class BackdropCompositeInput(
    val texture: Texture2D,
    val contentUv: Rect,
    val coordinateOrigin: CoordinateOrigin
)

/**
 * Progressive blur-strength texture metadata.
 *
 * The texture is slot-local and borrowed from renderer resources. The blur shader maps each
 * expanded sample pixel back into the visible slot and samples this texture for local strength.
 */
internal data class BackdropMaskInput(
    val texture: Texture2D,
    val coordinateOrigin: CoordinateOrigin,
    val visibleInSample: Rect
)

internal data class BackdropBlurInput(
    val source: BackdropCompositeInput,
    val progressiveMask: BackdropMaskInput?,
    val sigmaPx: Float,
    val downsampleScale: Float,
    val filterRadiusPx: Float
)

private enum class BackdropBlurDirection(
    val vector: Float2,
    val traceLabel: String
) {
    Horizontal(
        vector = Float2(1f, 0f),
        traceLabel = "SceneBackdropBlurPass#horizontal"
    ),
    Vertical(
        vector = Float2(0f, 1f),
        traceLabel = "SceneBackdropBlurPass#vertical"
    )
}

internal class SceneBackdropPreparePass(
    private val commandsProvider: () -> RenderCommands,
    private val framebufferPool: FramebufferLendingPool,
    private val quadBatchRenderer: QuadBatchRenderer,
    private val shaderProgram: ShaderProgram
) {
    private val output = RetainedBackdropFramebuffer(framebufferPool)
    private var cachedSampleRegionKey: BackdropSampleRegionKey? = null
    private var cachedSampleRegion: BackdropSampleRegion? = null

    fun prepare(
        input: BackdropInput,
        slot: SceneGlRenderSlot,
        operation: SceneBackdropOperation.Blur,
        targetSize: IntSize
    ): PreparedBackdrop = trace("SceneBackdropPreparePass#prepare") {
        val downsampleScale = downsampleScale(operation)
        val paddingPx = operation.paddingInputPx(downsampleScale)
        val sampleRegion = sampleRegion(
            slot = slot,
            targetSize = targetSize,
            paddingPx = paddingPx,
            downsampleScale = downsampleScale
        )
        val contentSize = sampleRegion.sampleRootRect.size.toIntSize(downsampleScale)
        val output = this.output.acquire(contentSize)
        val prepared = PreparedBackdrop(
            input = input,
            output = output,
            sampleRegion = sampleRegion,
            downsampleScale = downsampleScale,
            sourceTexelInputSize = sourceTexelInputSize(
                contentSize = contentSize,
                sampleRegion = sampleRegion,
                downsampleScale = downsampleScale
            )
        )

        drawPreparedTexture(prepared)
        prepared
    }

    fun release(prepared: PreparedBackdrop) {
        output.release(prepared.output)
    }

    fun releaseCachedFramebuffers() {
        output.releaseCached()
    }

    private fun drawPreparedTexture(prepared: PreparedBackdrop) {
        val commands = commandsProvider()
        val contentSize = prepared.contentSize
        val output = prepared.output
        output.framebuffer.bindForOverwrite(commands, Bind.DRAW)
        commands.setViewPort(
            x = output.image.contentRect.left,
            y = output.image.contentRect.top,
            width = output.image.contentRect.width,
            height = output.image.contentRect.height
        )
        quadBatchRenderer.draw(
            targetSize = contentSize,
            debug = false,
            enableBlending = false,
            shaderProgram = shaderProgram,
            configureShader = { shader, _ ->
                shader.shader.setFloat2(
                    "u_InputSize",
                    Float2(prepared.input.size.width.toFloat(), prepared.input.size.height.toFloat())
                )
                shader.shader.setFloat(
                    "u_InputFlipY",
                    if (prepared.input.coordinateOrigin == CoordinateOrigin.BOTTOM_LEFT) 1f else 0f
                )
                shader.shader.setFloat4(
                    "u_SampleInputRect",
                    Float4(
                        prepared.sampleRegion.sampleRootRect.left,
                        prepared.sampleRegion.sampleRootRect.top,
                        prepared.sampleRegion.sampleRootRect.width,
                        prepared.sampleRegion.sampleRootRect.height
                    )
                )
                shader.shader.setFloat2(
                    "u_SourceTexelInputSize",
                    prepared.sourceTexelInputSize
                )
                shader.shader.setFloat(
                    "u_PrefilterScale",
                    prefilterScale(prepared.downsampleScale)
                )
            },
            traceLabel = "SceneBackdropPreparePass#draw"
        ) {
            val size = Size(contentSize.width.toFloat(), contentSize.height.toFloat())
            val center = Offset(size.width / 2f, size.height / 2f)
            submit(
                RenderQuad(
                    id = "prepared-backdrop",
                    center = center,
                    size = size,
                    texture = prepared.input.texture,
                    maskValue = 0f,
                    alpha = 1f,
                    flipTexture = false,
                    tint = Color.Transparent,
                    transform = translateScale(center, size)
                )
            )
        }
    }

    private fun sampleRegion(
        slot: SceneGlRenderSlot,
        targetSize: IntSize,
        paddingPx: Float,
        downsampleScale: Float
    ): BackdropSampleRegion {
        val key = BackdropSampleRegionKey(
            geometry = slot.geometry,
            targetSize = targetSize,
            paddingPx = paddingPx,
            downsampleScale = downsampleScale
        )
        if (key != cachedSampleRegionKey) {
            cachedSampleRegion = BackdropSampleRegion.forBlur(
                slot = slot,
                targetSize = targetSize,
                paddingPx = paddingPx,
                downsampleScale = downsampleScale
            )
            cachedSampleRegionKey = key
        }
        return requireNotNull(cachedSampleRegion)
    }

    // Pick scale so the kernel always runs at a roughly constant sigma in
    // downsampled space (DOWNSAMPLE_TARGET_SIGMA_DS). Bigger blur -> more
    // downsampling, not more taps; cost stays ~flat with sigma. Snapping to
    // half-octave steps (sqrt(2)) keeps the prepared texture size discrete (FBO
    // reuse) while halving the resolution jump at each tier flip, so animating
    // sigma across a boundary pops less. See doc/scene2-blur-radius-scope.md.
    private fun downsampleScale(operation: SceneBackdropOperation.Blur): Float {
        val sigmaPx = operation.sigmaPx
        if (sigmaPx <= DOWNSAMPLE_TARGET_SIGMA_DS) return 1f
        val octaves = log2(DOWNSAMPLE_TARGET_SIGMA_DS / sigmaPx)
        val snapped = round(octaves * DOWNSAMPLE_STEPS_PER_OCTAVE) / DOWNSAMPLE_STEPS_PER_OCTAVE
        return 2f.pow(snapped).coerceIn(DOWNSAMPLE_SCALE_FLOOR, 1f)
    }

    private data class BackdropSampleRegionKey(
        val geometry: SceneSlotGeometry,
        val targetSize: IntSize,
        val paddingPx: Float,
        val downsampleScale: Float
    )
}

internal class SceneBackdropBlurPass(
    private val commandsProvider: () -> RenderCommands,
    private val framebufferPool: FramebufferLendingPool,
    private val quadBatchRenderer: QuadBatchRenderer,
    private val maskedShaderProgram: ShaderProgram,
    private val noMaskShaderProgram: ShaderProgram
) {
    private val noMaskKernelSamples = BackdropBlurKernelSamples()
    private val horizontalOutput = RetainedBackdropFramebuffer(framebufferPool)
    private val finalOutput = RetainedBackdropFramebuffer(framebufferPool)
    private var cachedBlurInputKey: BackdropBlurInputKey? = null
    private var cachedBlurInput: BackdropBlurInput? = null

    fun process(
        prepared: PreparedBackdrop,
        operation: SceneBackdropOperation.Blur,
        progressiveMaskTexture: Texture2D?
    ): ProcessedBackdrop = trace("SceneBackdropBlurPass#process") {
        val horizontalOutput = this.horizontalOutput.acquire(prepared.contentSize)
        val finalOutput = this.finalOutput.acquire(prepared.contentSize)
        val blurInput = blurInput(prepared, operation, progressiveMaskTexture)
        val processed = ProcessedBackdrop(
            source = prepared,
            output = finalOutput
        )

        try {
            drawBlurStep(
                input = blurInput,
                output = horizontalOutput,
                direction = BackdropBlurDirection.Horizontal
            )
            drawBlurStep(
                input = blurInput.copy(source = horizontalOutput.image.compositeInput),
                output = finalOutput,
                direction = BackdropBlurDirection.Vertical
            )
            this.horizontalOutput.release(horizontalOutput)
            processed
        } catch (throwable: Throwable) {
            this.horizontalOutput.release(horizontalOutput)
            this.finalOutput.release(finalOutput)
            throw throwable
        }
    }

    fun release(processed: ProcessedBackdrop) {
        finalOutput.release(processed.output)
    }

    fun releaseCachedFramebuffers() {
        horizontalOutput.releaseCached()
        finalOutput.releaseCached()
    }

    private fun blurInput(
        prepared: PreparedBackdrop,
        operation: SceneBackdropOperation.Blur,
        progressiveMaskTexture: Texture2D?
    ): BackdropBlurInput {
        val key = BackdropBlurInputKey(
            source = prepared.compositeInput,
            progressiveMaskTexture = progressiveMaskTexture,
            progressiveMaskCoordinateOrigin = progressiveMaskTexture?.coordinateOrigin,
            visibleInSample = prepared.sampleRegion.visibleInSample,
            sigmaPx = operation.sigmaPx,
            downsampleScale = prepared.downsampleScale,
            filterRadiusPx = operation.filterRadiusPx(prepared.downsampleScale)
        )
        if (key != cachedBlurInputKey) {
            cachedBlurInput = BackdropBlurInput(
                source = key.source,
                progressiveMask = key.progressiveMaskTexture?.let { texture ->
                    BackdropMaskInput(
                        texture = texture,
                        coordinateOrigin = requireNotNull(key.progressiveMaskCoordinateOrigin),
                        visibleInSample = key.visibleInSample
                    )
                },
                sigmaPx = key.sigmaPx,
                downsampleScale = key.downsampleScale,
                filterRadiusPx = key.filterRadiusPx
            )
            cachedBlurInputKey = key
        }
        return requireNotNull(cachedBlurInput)
    }

    private data class BackdropBlurInputKey(
        val source: BackdropCompositeInput,
        val progressiveMaskTexture: Texture2D?,
        val progressiveMaskCoordinateOrigin: CoordinateOrigin?,
        val visibleInSample: Rect,
        val sigmaPx: Float,
        val downsampleScale: Float,
        val filterRadiusPx: Float
    )

    private fun drawBlurStep(
        input: BackdropBlurInput,
        output: BorrowedBackdropTexture,
        direction: BackdropBlurDirection
    ) {
        val commands = commandsProvider()
        val contentSize = output.image.contentSize
        val source = input.source
        val progressiveMask = input.progressiveMask
        val sourceUv = if (progressiveMask == null) source.fragmentUv else source.contentUv
        val flipSourceTexture = progressiveMask != null &&
                source.coordinateOrigin == CoordinateOrigin.BOTTOM_LEFT
        output.framebuffer.bindForOverwrite(commands, Bind.DRAW)
        commands.setViewPort(
            x = output.image.contentRect.left,
            y = output.image.contentRect.top,
            width = output.image.contentRect.width,
            height = output.image.contentRect.height
        )
        quadBatchRenderer.draw(
            targetSize = contentSize,
            debug = false,
            enableBlending = false,
            shaderProgram = if (progressiveMask == null) noMaskShaderProgram else maskedShaderProgram,
            reservedTextures = listOfNotNull(source.texture, progressiveMask?.texture),
            configureShader = { shader, reservedTextureSlots ->
                shader.shader.setInt(
                    "u_SourceTexture",
                    reservedTextureSlots[source.texture] ?: error("Source texture slot was not reserved")
                )
                shader.shader.setFloat2("u_BlurDirection", direction.vector)
                shader.shader.setFloat2("u_SourceTexelStep", source.texelStep(contentSize))
                shader.shader.setFloat2(
                    "u_SourceUvMin",
                    Float2(sourceUv.left, sourceUv.top)
                )
                shader.shader.setFloat2(
                    "u_SourceUvMax",
                    Float2(sourceUv.right, sourceUv.bottom)
                )
                if (progressiveMask != null) {
                    shader.shader.configureMaskUniforms(progressiveMask, reservedTextureSlots)
                    shader.shader.setFloat("u_FilterRadiusPx", input.filterRadiusPx)
                    shader.shader.setFloat("u_BlurSigmaPx", input.sigmaPx)
                    shader.shader.setFloat("u_DownsampleScale", input.downsampleScale)
                } else {
                    noMaskKernelSamples.configure(shader.shader, input)
                }
            },
            traceLabel = direction.traceLabel
        ) {
            val size = Size(contentSize.width.toFloat(), contentSize.height.toFloat())
            val center = Offset(size.width / 2f, size.height / 2f)
            submit(
                RenderQuad(
                    id = "blurred-backdrop-${direction.name}",
                    center = center,
                    size = size,
                    uv = sourceUv,
                    texture = null,
                    maskValue = 0f,
                    alpha = 1f,
                    flipTexture = flipSourceTexture,
                    tint = Color.Transparent,
                    transform = translateScale(center, size)
                )
            )
        }
    }

    private fun Shader.configureMaskUniforms(
        progressiveMask: BackdropMaskInput,
        reservedTextureSlots: Map<Texture2D, Int>
    ) {
        setFloat("u_MaskEnabled", 1f)
        setInt(
            "u_MaskTexture",
            reservedTextureSlots[progressiveMask.texture] ?: error("Mask texture slot was not reserved")
        )
        setFloat(
            "u_MaskFlipY",
            if (progressiveMask.coordinateOrigin == CoordinateOrigin.BOTTOM_LEFT) 1f else 0f
        )
        setFloat4("u_VisibleInSample", progressiveMask.visibleInSample.toFloat4())
    }

}

private class RetainedBackdropFramebuffer(
    private val framebufferPool: FramebufferLendingPool
) {
    private var framebuffer: Framebuffer? = null
    private var contentSize: IntSize? = null
    private var inUse = false

    fun acquire(contentSize: IntSize): BorrowedBackdropTexture {
        val framebuffer = acquireFramebuffer(contentSize)
        val contentRect = IntRect(
            left = 0,
            top = 0,
            right = contentSize.width,
            bottom = contentSize.height
        )
        return BorrowedBackdropTexture(
            image = BackdropTexture(
                texture = framebuffer.colorAttachmentTexture,
                contentRect = contentRect
            ),
            framebuffer = framebuffer
        )
    }

    fun release(texture: BorrowedBackdropTexture) {
        if (texture.framebuffer === framebuffer) {
            inUse = false
        } else {
            framebufferPool.release(texture.framebuffer)
        }
    }

    fun releaseCached() {
        val cachedFramebuffer = framebuffer ?: return
        framebuffer = null
        contentSize = null
        inUse = false
        framebufferPool.release(cachedFramebuffer)
    }

    private fun acquireFramebuffer(requestedSize: IntSize): Framebuffer {
        val cachedFramebuffer = framebuffer
        if (cachedFramebuffer != null && !inUse && contentSize == requestedSize) {
            inUse = true
            return cachedFramebuffer
        }
        if (!inUse) {
            releaseCached()
        }
        val acquired = framebufferPool.acquireBucketed(
            FramebufferSpecification(
                size = requestedSize,
                attachmentsSpec = FramebufferAttachmentSpecification.singleColor(
                    format = FramebufferTextureFormat.RGBA8,
                    coordinateOrigin = CoordinateOrigin.BOTTOM_LEFT
                )
            )
        )
        if (inUse) {
            return acquired
        }
        framebuffer = acquired
        contentSize = requestedSize
        inUse = true
        return acquired
    }
}

private class BackdropBlurKernelSamples {
    private val samples = FloatArray(SCENE_BACKDROP_BLUR_MAX_KERNEL_SAMPLE_COUNT * 4)
    private var sampleCount = 0
    private var cachedSigmaPx = Float.NaN
    private var cachedFilterRadiusPx = Float.NaN

    fun configure(shader: Shader, input: BackdropBlurInput) {
        val sigmaPx = input.effectiveSigmaPx
        val filterRadiusPx = input.filterRadiusPx
        if (sigmaPx != cachedSigmaPx || filterRadiusPx != cachedFilterRadiusPx) {
            rebuild(sigmaPx, filterRadiusPx)
            cachedSigmaPx = sigmaPx
            cachedFilterRadiusPx = filterRadiusPx
        }
        shader.setFloat4Array(
            name = "u_KernelSamples",
            values = samples,
            count = SCENE_BACKDROP_BLUR_MAX_KERNEL_SAMPLE_COUNT
        )
    }

    private fun rebuild(sigmaPx: Float, filterRadiusPx: Float) {
        val safeSigmaPx = sigmaPx.coerceAtLeast(1e-6f)
        val radiusPx = filterRadiusPx.toInt()
            .coerceIn(0, SCENE_BACKDROP_BLUR_MAX_RADIUS_PX)
        sampleCount = 0
        addSample(offsetPx = 0f, weight = gaussianWeight(offsetPx = 0f, sigmaPx = safeSigmaPx))
        addSideSamples(side = -1, radiusPx = radiusPx, sigmaPx = safeSigmaPx)
        addSideSamples(side = 1, radiusPx = radiusPx, sigmaPx = safeSigmaPx)
        clearUnusedSamples()
    }

    private fun addSideSamples(side: Int, radiusPx: Int, sigmaPx: Float) {
        var offsetPx = 1
        while (offsetPx <= radiusPx) {
            val firstOffsetPx = side * offsetPx.toFloat()
            val firstWeight = gaussianWeight(firstOffsetPx, sigmaPx)
            if (offsetPx + 1 <= radiusPx) {
                val secondOffsetPx = side * (offsetPx + 1).toFloat()
                val secondWeight = gaussianWeight(secondOffsetPx, sigmaPx)
                val combinedWeight = firstWeight + secondWeight
                val combinedOffset = if (combinedWeight > 0f) {
                    (firstOffsetPx * firstWeight + secondOffsetPx * secondWeight) / combinedWeight
                } else {
                    firstOffsetPx
                }
                addSample(offsetPx = combinedOffset, weight = combinedWeight)
                offsetPx += 2
            } else {
                addSample(offsetPx = firstOffsetPx, weight = firstWeight)
                offsetPx += 1
            }
        }
    }

    private fun addSample(offsetPx: Float, weight: Float) {
        val index = sampleCount * 4
        samples[index] = offsetPx
        samples[index + 1] = weight
        samples[index + 2] = 0f
        samples[index + 3] = 0f
        sampleCount += 1
    }

    private fun gaussianWeight(offsetPx: Float, sigmaPx: Float): Float {
        val normalized = offsetPx / sigmaPx
        return exp(-0.5f * normalized * normalized).toFloat()
    }

    private fun clearUnusedSamples() {
        var index = sampleCount * 4
        while (index < samples.size) {
            samples[index] = 0f
            samples[index + 1] = 0f
            samples[index + 2] = 0f
            samples[index + 3] = 0f
            index += 4
        }
    }
}

internal class SceneBackdropCompositePass(
    private val commandsProvider: () -> RenderCommands,
    private val quadBatchRenderer: QuadBatchRenderer,
    private val shaderProgram: ShaderProgram
) {
    fun draw(
        frame: SceneGlRenderFrame,
        slot: SceneGlRenderSlot,
        processed: ProcessedBackdrop,
        target: SceneRenderBuffer,
        noiseTexture: Texture2D?
    ) = trace("SceneBackdropCompositePass#draw") {
        draw(
            frame = frame,
            slot = slot,
            input = processed.compositeInput,
            sampleRootRect = processed.source.sampleRegion.sampleRootRect,
            target = target,
            noiseTexture = noiseTexture
        )
    }

    private fun draw(
        frame: SceneGlRenderFrame,
        slot: SceneGlRenderSlot,
        input: BackdropCompositeInput,
        sampleRootRect: Rect,
        target: SceneRenderBuffer,
        noiseTexture: Texture2D?
    ) = trace("SceneBackdropCompositePass#drawProcessed") {
        val composite = slot.backdrop?.composite
        val wantsNoise = noiseTexture != null && composite?.hasNoise == true
        val bounds = slot.geometry.rootBounds
        val localSize = slot.geometry.localSize
        val size = Size(
            width = localSize.width.toFloat(),
            height = localSize.height.toFloat()
        )
        target.bindForDraw(commandsProvider())
        quadBatchRenderer.draw(
            targetSize = frame.targetSize,
            debug = false,
            enableBlending = true,
            shaderProgram = shaderProgram,
            reservedTextures = listOfNotNull(noiseTexture?.takeIf { wantsNoise }),
            configureShader = { shader, reservedTextureSlots ->
                val noiseSlot = reservedTextureSlots.slotFor(noiseTexture, wantsNoise)
                shader.shader.setFloat2(
                    "u_TargetSize",
                    Float2(frame.targetSize.width.toFloat(), frame.targetSize.height.toFloat())
                )
                shader.shader.setFloat("u_FragCoordFlipY", 1f)
                shader.shader.setFloat4(
                    "u_SampleRect",
                    Float4(
                        sampleRootRect.left,
                        sampleRootRect.top,
                        sampleRootRect.width,
                        sampleRootRect.height
                    )
                )
                shader.shader.setFloat2(
                    "u_SampleUvMin",
                    Float2(input.contentUv.left, input.contentUv.top)
                )
                shader.shader.setFloat2(
                    "u_SampleUvMax",
                    Float2(input.contentUv.right, input.contentUv.bottom)
                )
                shader.shader.setFloat("u_NoiseEnabled", if (wantsNoise) 1f else 0f)
                shader.shader.setFloat("u_NoiseAlpha", composite?.noiseAlpha ?: 0f)
                shader.shader.setInt("u_NoiseTexIndex", noiseSlot)
                shader.shader.setFloat2(
                    "u_NoiseTextureSize",
                    Float2(frame.targetSize.width.toFloat(), frame.targetSize.height.toFloat())
                )
                shader.shader.setFloat2("u_NoiseOffsetPx", slot.id.noiseOffsetPx())
                shader.shader.setMat4(
                    "u_RootToLocal",
                    inverse(composeMatrixToMat4(slot.geometry.localToRoot.values))
                )
            },
            traceLabel = "SceneBackdropCompositePass#processed"
        ) {
            submit(
                RenderQuad(
                    id = "${slot.id.value}:backdrop",
                    center = Offset(
                        x = bounds.center.x.toFloat(),
                        y = bounds.center.y.toFloat()
                    ),
                    size = size,
                    texture = input.texture,
                    maskValue = 0f,
                    alpha = 1f,
                    flipTexture = input.coordinateOrigin == CoordinateOrigin.BOTTOM_LEFT,
                    tint = slot.backdrop?.composite?.tint ?: Color.Transparent,
                    transform = slot.geometry.renderTransform
                )
            )
        }
    }

    private fun Map<Texture2D, Int>.slotFor(texture: Texture2D?, enabled: Boolean): Int {
        return if (enabled) getValue(requireNotNull(texture)) else 0
    }
}

private fun SceneSlotId.noiseOffsetPx(): Float2 {
    val mixed = value * HASH_MULTIPLIER + HASH_INCREMENT
    val x = ((mixed ushr 16) and HASH_COORD_MASK).toFloat()
    val y = ((mixed ushr 32) and HASH_COORD_MASK).toFloat()
    return Float2(x, y)
}

private const val HASH_MULTIPLIER = 6364136223846793005L
private const val HASH_INCREMENT = 1442695040888963407L
private const val HASH_COORD_MASK = 4095L

private fun Size.toIntSize(scale: Float): IntSize {
    return IntSize(
        width = ceil(width * scale).toInt().coerceAtLeast(1),
        height = ceil(height * scale).toInt().coerceAtLeast(1)
    )
}

private fun sourceTexelInputSize(
    contentSize: IntSize,
    sampleRegion: BackdropSampleRegion,
    downsampleScale: Float
): Float2 {
    val safeScale = downsampleScale.coerceAtLeast(1e-6f)
    val sourceWidth = contentSize.width.toFloat().coerceAtLeast(1f) / safeScale
    val sourceHeight = contentSize.height.toFloat().coerceAtLeast(1f) / safeScale
    return Float2(
        sampleRegion.sampleRootRect.width / sourceWidth,
        sampleRegion.sampleRootRect.height / sourceHeight
    )
}

private fun SceneBackdropOperation.Blur.paddingInputPx(downsampleScale: Float): Float {
    return ceil(filterRadiusPx(downsampleScale) / downsampleScale.coerceAtLeast(1e-6f))
}

// Widen the prepare prefilter footprint for deeper tiers so its low-pass cutoff
// tracks the downsample factor D = 1/scale; 1.0 (unchanged) for D <= the tuned 4x.
private fun prefilterScale(downsampleScale: Float): Float {
    val factor = 1f / downsampleScale.coerceAtLeast(1e-6f)
    return max(1f, factor / PREFILTER_TUNED_DOWNSAMPLE)
}

private fun SceneBackdropOperation.Blur.filterRadiusPx(downsampleScale: Float): Float {
    val sigmaDs = sigmaPx * downsampleScale
    return ceil(GAUSSIAN_RADIUS_SIGMAS * sigmaDs)
        .coerceIn(MIN_BLUR_RADIUS_PX, SCENE_BACKDROP_BLUR_MAX_RADIUS_PX.toFloat())
}

private val BackdropBlurInput.effectiveSigmaPx: Float
    get() = sigmaPx * downsampleScale.coerceAtLeast(1e-6f)

private fun Rect.toFloat4(): Float4 {
    return Float4(left, top, width, height)
}

private val BackdropCompositeInput.fragmentUv: Rect
    get() {
        if (coordinateOrigin != CoordinateOrigin.BOTTOM_LEFT) {
            return contentUv
        }
        return Rect(
            left = contentUv.left,
            top = 1f - contentUv.bottom,
            right = contentUv.right,
            bottom = 1f - contentUv.top
        )
    }

private fun BackdropCompositeInput.texelStep(contentSize: IntSize): Float2 {
    val width = contentSize.width.toFloat().coerceAtLeast(1f)
    val height = contentSize.height.toFloat().coerceAtLeast(1f)
    return Float2(
        contentUv.width / width,
        contentUv.height / height
    )
}

private fun IntSize.toRect(): Rect {
    return Rect(
        left = 0f,
        top = 0f,
        right = width.toFloat(),
        bottom = height.toFloat()
    )
}

private fun Rect.snapToGrid(stepPx: Float): Rect {
    val step = stepPx.coerceAtLeast(1f)
    return Rect(
        left = floor(left / step) * step,
        top = floor(top / step) * step,
        right = ceil(right / step) * step,
        bottom = ceil(bottom / step) * step
    )
}

private fun Rect.intersectOrEmpty(other: Rect): Rect {
    val intersection = Rect(
        left = max(left, other.left),
        top = max(top, other.top),
        right = min(right, other.right),
        bottom = min(bottom, other.bottom)
    )
    return if (intersection.isEmpty) Rect.Zero else intersection
}

private fun Rect.diagonalSampleEnvelope(localSize: IntSize, paddingPx: Float): Rect {
    val stableSide = hypot(
        localSize.width.toFloat(),
        localSize.height.toFloat()
    ) + paddingPx * 2f
    val center = center
    val halfSide = stableSide / 2f
    return Rect(
        left = center.x - halfSide,
        top = center.y - halfSide,
        right = center.x + halfSide,
        bottom = center.y + halfSide
    )
}

private const val MIN_BLUR_RADIUS_PX = 1f
internal const val SCENE_BACKDROP_BLUR_MAX_RADIUS_PX = 12

// Kernel half-width in sigmas (3 sigma captures ~99% of a Gaussian; the old
// 1-sigma truncation made wide blur boxy).
private const val GAUSSIAN_RADIUS_SIGMAS = 3f

// Target sigma in downsampled space: scale is chosen to keep the kernel near
// this size in texels, so radius (3*sigma) lands at the radius cap.
private const val DOWNSAMPLE_TARGET_SIGMA_DS =
    SCENE_BACKDROP_BLUR_MAX_RADIUS_PX / GAUSSIAN_RADIUS_SIGMAS

// 1/16 floor reaches ~72dp at the radius cap. The prepare prefilter is widened
// per-tier (scale-aware) so its low-pass cutoff matches the deeper downsample.
private const val DOWNSAMPLE_SCALE_FLOOR = 0.0625f

// Quantize scale to half-octave (sqrt(2)) steps: 2 keeps texture sizes discrete
// for FBO reuse while halving the per-flip resolution jump vs full octaves.
private const val DOWNSAMPLE_STEPS_PER_OCTAVE = 2f

// Downsample factor the fixed prepare prefilter is tuned for (its ±2.9-texel
// footprint band-limits ~4x). Deeper tiers widen the footprint by D / this.
private const val PREFILTER_TUNED_DOWNSAMPLE = 4f
internal const val SCENE_BACKDROP_BLUR_MAX_KERNEL_SAMPLE_COUNT =
    1 + ((SCENE_BACKDROP_BLUR_MAX_RADIUS_PX + 1) / 2) * 2
