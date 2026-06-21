package dev.serhiiyaremych.imla.internal.render.gl

import androidx.compose.ui.unit.IntSize
import dev.serhiiyaremych.imla.internal.render.CoordinateOrigin
import dev.serhiiyaremych.imla.internal.render.Texture
import dev.serhiiyaremych.imla.internal.render.Texture2D
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.Buffer

class SceneSampledTextureTest {
    @Test
    fun contentUvCoversOnlyContentInsideBucketedTexture() {
        val sampledTexture = SceneSampledTexture(
            texture = FakeTexture(size = IntSize(576, 384)),
            contentSize = IntSize(520, 360)
        )

        assertEquals(0f, sampledTexture.contentUv.left)
        assertEquals(0f, sampledTexture.contentUv.top)
        assertEquals(520f / 576f, sampledTexture.contentUv.right)
        assertEquals(360f / 384f, sampledTexture.contentUv.bottom)
    }

    @Test
    fun contentUvClampsToTextureBounds() {
        val sampledTexture = SceneSampledTexture(
            texture = FakeTexture(size = IntSize(64, 64)),
            contentSize = IntSize(80, 96)
        )

        assertEquals(1f, sampledTexture.contentUv.right)
        assertEquals(1f, sampledTexture.contentUv.bottom)
    }

    private class FakeTexture(
        size: IntSize
    ) : Texture2D() {
        override val id: Int = 1
        override val target: Texture.Target = Texture.Target.TEXTURE_2D
        override val width: Int = size.width
        override val height: Int = size.height
        override val coordinateOrigin: CoordinateOrigin = CoordinateOrigin.TOP_LEFT
        override val specification: Texture.Specification = Texture.Specification(
            size = size,
            coordinateOrigin = coordinateOrigin
        )

        override fun bind(slot: Int) = Unit
        override fun setData(data: Buffer) = Unit
        override fun isLoaded(): Boolean = true
        override fun destroy() = Unit
        override fun generateMipMaps() = Unit
    }
}
