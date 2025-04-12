/*
 *
 *  * Copyright 2025, Serhii Yaremych
 *  * SPDX-License-Identifier: MIT
 *
 */

package dev.serhiiyaremych.imla.renderer.commands

import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import dev.romainguy.kotlin.math.Mat4
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.translation
import dev.romainguy.kotlin.math.scale
import dev.serhiiyaremych.imla.ext.logd
import dev.serhiiyaremych.imla.ext.loge
import dev.serhiiyaremych.imla.ext.logw
import dev.serhiiyaremych.imla.renderer.commands.*
import dev.serhiiyaremych.imla.renderer.primitive.*
import dev.serhiiyaremych.imla.renderer.camera.OrthographicCameraController
import dev.serhiiyaremych.imla.renderer.shader.ShaderManager
import dev.serhiiyaremych.imla.renderer.VertexDataManager
import dev.serhiiyaremych.imla.renderer.BufferLayout
import dev.serhiiyaremych.imla.renderer.BufferElement
import dev.serhiiyaremych.imla.renderer.ShaderDataType
import dev.serhiiyaremych.imla.renderer.addElement


internal class CommandBufferRenderer(
    private val commandEncoder: CommandEncoder,
    private val shaderManager: ShaderManager,
    private val vertexDataManager: VertexDataManager,
    // private val textureManager: TextureManager,
) {
    private val TAG = "CommandBufferRenderer"
    
    private var unitQuadVaoHandle: VertexArrayHandle = VertexArrayHandle.Invalid
    private var defaultQuadShaderHandle: ShaderProgramHandle = ShaderProgramHandle.Invalid
    private var uViewProjectionHandle: UniformHandle = UniformHandle.Invalid
    private var uModelMatrixHandle: UniformHandle = UniformHandle.Invalid
    private var uColorTintHandle: UniformHandle = UniformHandle.Invalid

    private data class QuadDrawInfo(
        val modelMatrix: Mat4,
        val textureHandle: TextureHandle,
        val colorTint: Color,
        val shaderHandle: ShaderProgramHandle,
        val zIndex: Int,
        val isTransparent: Boolean
    )

    private val opaqueDraws = mutableListOf<QuadDrawInfo>()
    private val transparentDraws = mutableListOf<QuadDrawInfo>()

    private var currentViewProjectionMatrix: Mat4 = Mat4.identity()

    init {
        initializeResources()
    }
    
    private fun initializeResources() {
        logd(TAG, "Initializing common rendering resources...")
        val unitQuadVertices = floatArrayOf(
            // Position (x, y)    Texture Coordinates (u, v)
            -0.5f, -0.5f,         0.0f, 0.0f, // Bottom Left
            0.5f, -0.5f,         1.0f, 0.0f, // Bottom Right
            0.5f,  0.5f,         1.0f, 1.0f, // Top Right
            -0.5f,  0.5f,         0.0f, 1.0f  // Top Left
        )
        val unitQuadIndices = intArrayOf(
            0, 1, 2, // First triangle
            2, 3, 0  // Second triangle
        )
        val unitQuadLayout = BufferLayout {
            addElement("a_Position", ShaderDataType.Float2)
            addElement("a_TexCoord", ShaderDataType.Float2)
        }

        unitQuadVaoHandle = vertexDataManager.createStaticMesh(
            vertices = unitQuadVertices,
            indices = unitQuadIndices,
            layout = unitQuadLayout
        )
        if (unitQuadVaoHandle == VertexArrayHandle.Invalid) {
            error("Failed to create unit quad VAO!")
        }
        logd(TAG, "Created unit quad VAO with handle: $unitQuadVaoHandle")


        defaultQuadShaderHandle = shaderManager.loadShaderFromAssets("default_quad", "default_quad")
        if (defaultQuadShaderHandle == ShaderProgramHandle.Invalid) {
            error("Failed to load default quad shader!")
        }

        uViewProjectionHandle = shaderManager.getUniformHandle(defaultQuadShaderHandle, "u_ViewProjection")
        uModelMatrixHandle = shaderManager.getUniformHandle(defaultQuadShaderHandle, "u_ModelMatrix")
        uColorTintHandle = shaderManager.getUniformHandle(defaultQuadShaderHandle, "u_ColorTint")

        if (uViewProjectionHandle == UniformHandle.Invalid || uModelMatrixHandle == UniformHandle.Invalid) {
            loge(TAG, "Failed to get required uniform locations (u_ViewProjection or u_ModelMatrix) for shader $defaultQuadShaderHandle")
        }
        logd(TAG, "Successfully initialized common resources.")
    }

    fun beginFrame(cameraController: OrthographicCameraController) {
        opaqueDraws.clear()
        transparentDraws.clear()

        currentViewProjectionMatrix = cameraController.camera.viewProjectionMatrix

        // Record command to set the common View-Projection Matrix for this frame.
        // commandEncoder.setUniform(uViewProjectionHandle, currentViewProjectionMatrix, transpose = false)
    }

    fun drawQuad(
        position: Offset,
        size: Size,
        textureHandle: TextureHandle,
        shaderHandle: ShaderProgramHandle = defaultQuadShaderHandle,
        colorTint: Color = Color.White,
        zIndex: Int = 0,
        isTransparent: Boolean = true
    ) {
        if (textureHandle == TextureHandle.Invalid || shaderHandle == ShaderProgramHandle.Invalid) {
            logw(TAG, "Skipping drawQuad due to invalid texture or shader handle.")
            return
        }
        // Calculate Model Matrix: Scale then Translate
        val modelMatrix = translation(Float3(position.x, position.y, 0f)) * scale(Float3(size.width, size.height, 1f))

        val drawInfo = QuadDrawInfo(modelMatrix, textureHandle, colorTint, shaderHandle, zIndex, isTransparent)

        if (isTransparent) {
            transparentDraws.add(drawInfo)
        } else {
            opaqueDraws.add(drawInfo)
        }
    }

    fun endFrame() {
        // --- Perform Sorting (Approach A) ---
        // transparentDraws.sortBy { it.zIndex } // Back-to-front
        // opaqueDraws.sortBy { it.zIndex } // Optional: Front-to-back?

        // Sort transparent draws by zIndex (ascending for typical back-to-front)
        transparentDraws.sortBy { it.zIndex }

        commandEncoder.setBlendState(enabled = false)
        recordDrawInfos(opaqueDraws)

        commandEncoder.setBlendState(enabled = true)
        recordDrawInfos(transparentDraws)
    }

    private fun recordDrawInfos(drawInfos: List<QuadDrawInfo>) {
        // Batch by shader, then texture
        // todo: measure
        drawInfos.groupBy { it.shaderHandle }.forEach { (shaderHandle, shaderGroup) ->
            commandEncoder.setShader(shaderHandle)

            commandEncoder.setUniform(uViewProjectionHandle, currentViewProjectionMatrix)

            shaderGroup.groupBy { it.textureHandle }.forEach { (textureHandle, textureGroup) ->
                commandEncoder.setTexture(0, textureHandle) // todo: provide correct texture unit

                textureGroup.forEach { drawInfo ->
                    commandEncoder.setUniform(uModelMatrixHandle, drawInfo.modelMatrix)
                    if (uColorTintHandle != UniformHandle.Invalid && drawInfo.colorTint != Color.White) {
                        commandEncoder.setUniform(uColorTintHandle, drawInfo.colorTint)
                    }
                    commandEncoder.draw(unitQuadVaoHandle, 6) // Index count is 6 for 2 triangles
                }
            }
        }
    }

    // todo: Add drawFullscreenEffect or other specialized methods
    // fun drawFullscreenEffect(...) { ... }
}