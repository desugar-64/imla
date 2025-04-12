/*
 *
 *  * Copyright 2025, Serhii Yaremych
 *  * SPDX-License-Identifier: MIT
 *
 */

package dev.serhiiyaremych.imla.renderer.commands

import android.opengl.GLES30
import androidx.compose.ui.geometry.Rect
import dev.serhiiyaremych.imla.ext.logd
import dev.serhiiyaremych.imla.ext.loge
import dev.serhiiyaremych.imla.ext.logw
import dev.serhiiyaremych.imla.renderer.primitive.FramebufferHandle
import dev.serhiiyaremych.imla.renderer.primitive.ShaderProgramHandle
import dev.serhiiyaremych.imla.renderer.primitive.TextureHandle
import dev.serhiiyaremych.imla.renderer.primitive.UniformHandle
import dev.serhiiyaremych.imla.renderer.primitive.VertexArrayHandle
import dev.serhiiyaremych.imla.uirenderer.GLThread

/**
 * Executes lists of RenderCommands, optimizing execution by caching OpenGL state
 * to avoid redundant API calls. Assumes execution happens on a valid GL thread.
 */
internal class CommandPlayer {
    private val TAG = "CommandPlayer"

    // --- Cached OpenGL State ---
    private var currentFramebufferHandle: FramebufferHandle = FramebufferHandle.Invalid // Start invalid to force first bind
    private var currentShaderProgramHandle: ShaderProgramHandle = ShaderProgramHandle.Invalid
    private var currentVertexArrayHandle: VertexArrayHandle = VertexArrayHandle.Invalid
    // Map<TextureUnitIndex, TextureHandle>
    private val currentTextureHandles = mutableMapOf<Int, TextureHandle>()
    // Simple blend state caching
    private var isBlendEnabled: Boolean = false
    private var currentBlendSrcFactor: Int = -1
    private var currentBlendDstFactor: Int = -1
    private var currentBlendEquation: Int = -1
    // Viewport and Scissor caching
    private var currentViewportRect: Rect = Rect.Zero // Initialize appropriately
    // Add other cacheable states: depth test, stencil func, etc. as needed

    // Optional: Dependency on managers if needed (e.g., for texture target lookup)
    // constructor(private val textureManager: TextureManager? = null) {}

    /**
     * Executes a list of rendering commands sequentially.
     * Applies state caching to minimize redundant OpenGL calls.
     *
     * @param commands The list of RenderCommand objects to execute.
     */
    @GLThread
    fun execute(commands: List<RenderCommand>) {
        // Could potentially iterate using indices for slightly better performance
        // commands.forEach { command -> ... } is generally clear
        for (command in commands) {
            // Use 'when' for exhaustive, type-safe command processing
            when (command) {
                is SetRenderTargetCommand -> processSetRenderTarget(command)
                is SetViewportCommand -> processSetViewport(command)
                is ClearCommand -> processClear(command)
                is SetShaderCommand -> processSetShader(command)
                is SetTextureCommand -> processSetTexture(command)
                is GenerateMipmapCommand -> processGenerateMipmap(command) // Added Mipmap command processing
                is SetUniformFloatCommand -> processSetUniformFloat(command) // Simple uniforms might not need caching if shader changes often
                is SetUniformVec2Command -> processSetUniformVec2(command)
//                is SetUniformVec3Command -> processSetUniformVec3(command)
                is SetUniformVec4Command -> processSetUniformVec4(command)
                is SetUniformIntCommand -> processSetUniformInt(command)
                is SetUniformIntArrayCommand -> processSetUniformIntArray(command)
                // is SetUniformMat4Command -> processSetUniformMat4(command)
                is SetBlendStateCommand -> processSetBlendState(command)
                is DrawCommand -> processDraw(command)
                is BlitFramebufferCommand -> processBlitFramebuffer(command)
                // Add cases for any other defined commands
                is SetUniformMat4Command -> TODO()
            }
            // Optional: checkGlError() after each command processing during debug
        }
        // Optional: Reset bound VAO/Program after execution? Usually not needed.
        // GLES30.glBindVertexArray(0)
        // GLES30.glUseProgram(0)
        // currentVertexArrayHandle = VertexArrayHandle.Invalid
        // currentShaderProgramHandle = ShaderProgramHandle.Invalid
    }

    /** Clears the internal state cache. Call if GL state is externally modified or reset. */
    fun resetStateCache() {
        currentFramebufferHandle = FramebufferHandle.Invalid
        currentShaderProgramHandle = ShaderProgramHandle.Invalid
        currentVertexArrayHandle = VertexArrayHandle.Invalid
        currentTextureHandles.clear()
        isBlendEnabled = false // Match default GL state (disabled)
        currentBlendSrcFactor = -1 // Force update on next enable
        currentBlendDstFactor = -1
        currentBlendEquation = -1
        currentViewportRect = Rect.Zero // Or whatever initial state is guaranteed
        logd(TAG, "State cache reset.")
        // Reset other cached states...
    }

    // --- Private Command Processing Methods with State Caching ---

    private fun processSetRenderTarget(command: SetRenderTargetCommand) {
        if (command.framebufferHandle != currentFramebufferHandle) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, command.framebufferHandle.id)
            currentFramebufferHandle = command.framebufferHandle
            // logd(TAG, "SetRenderTarget: ${command.framebufferHandle.id}")
        }
    }

    private fun processSetViewport(command: SetViewportCommand) {
        // Rect comparison might be sensitive to float precision, use tolerance or compare ints
        val changed = currentViewportRect.left.toInt() != command.viewportRect.left.toInt() ||
                currentViewportRect.top.toInt() != command.viewportRect.top.toInt() ||
                currentViewportRect.width.toInt() != command.viewportRect.width.toInt() ||
                currentViewportRect.height.toInt() != command.viewportRect.height.toInt()

        if (changed) {
            GLES30.glViewport(
                command.viewportRect.left.toInt(),
                command.viewportRect.top.toInt(),
                command.viewportRect.width.toInt(),
                command.viewportRect.height.toInt()
            )
            currentViewportRect = command.viewportRect // Cache the new value
            // logd(TAG, "SetViewport: ${command.viewportRect}")
        }
    }

    private fun processClear(command: ClearCommand) {
        var mask = 0
        // Only set clear color if needed for this clear command
        command.color.let {
            GLES30.glClearColor(it.red, it.green, it.blue, it.alpha)
            mask = mask or GLES30.GL_COLOR_BUFFER_BIT
        }
        command.depth?.let {
            GLES30.glClearDepthf(it) // Ensure depth mask is enabled if using depth buffer
            mask = mask or GLES30.GL_DEPTH_BUFFER_BIT
        }
        command.stencil?.let {
            GLES30.glClearStencil(it) // Ensure stencil mask is enabled if using stencil buffer
            mask = mask or GLES30.GL_STENCIL_BUFFER_BIT
        }

        GLES30.glClear(mask)
    }

    private fun processSetShader(command: SetShaderCommand) {
        if (command.shaderProgramHandle != currentShaderProgramHandle) {
            if (command.shaderProgramHandle == ShaderProgramHandle.Invalid) {
                loge(TAG, "Attempting to bind invalid shader program handle!")
                // Potentially skip binding or throw error
                return
            }
            GLES30.glUseProgram(command.shaderProgramHandle.id)
            currentShaderProgramHandle = command.shaderProgramHandle
            // logd(TAG, "SetShader: ${command.shaderProgramHandle.id}")
            // Note: Uniform value caches are generally invalidated when the shader changes.
            // Simple uniform commands below don't cache per-uniform value, assuming they
            // are always set after the corresponding SetShaderCommand if needed.
            // More complex caching could track values per uniform *per shader*.
        }
    }

    private fun processSetTexture(command: SetTextureCommand) {
        // Check if the texture in the specified unit is already the desired one
        val currentHandleInUnit = currentTextureHandles.getOrElse(command.textureUnit, { TextureHandle.Invalid })
        if (command.textureHandle != currentHandleInUnit) {
            if (command.textureHandle == TextureHandle.Invalid) {
                // logw(TAG, "Attempting to bind invalid texture handle to unit ${command.textureUnit}")
                // Decide if you want to bind 0 or skip
            }

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + command.textureUnit)
            // This might need to come from a TextureManager or be part of the command.
            val glTarget = command.target
            GLES30.glBindTexture(glTarget, command.textureHandle.id)
            currentTextureHandles[command.textureUnit] = command.textureHandle // Update cache
            // logd(TAG, "SetTexture: unit=${command.textureUnit}, handle=${command.textureHandle.id}, target=$glTarget")
        }
    }

    private fun processGenerateMipmap(command: GenerateMipmapCommand) {
        // Mipmap generation applies to the texture currently bound to the specified unit
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + command.textureUnit)
        // TODO: Need the GL Target here as well
        val glTarget = GLES30.GL_TEXTURE_2D // Placeholder - Needs actual target
        GLES30.glGenerateMipmap(glTarget)
        // logd(TAG, "GenerateMipmap: unit=${command.textureUnit}, target=$glTarget")
    }

    // --- Uniform Processing ---
    // Simple implementation: Always set the uniform if the command exists.
    // Assumes uniforms are set after the correct shader is bound.
    // More complex caching could be added if profiling shows benefit.
    private fun processSetUniformFloat(command: SetUniformFloatCommand) {
        if (currentShaderProgramHandle == ShaderProgramHandle.Invalid || command.location == UniformHandle.Invalid) return
        GLES30.glUniform1f(command.location.location, command.value)
    }
    private fun processSetUniformVec2(command: SetUniformVec2Command) {
        if (currentShaderProgramHandle == ShaderProgramHandle.Invalid || command.location == UniformHandle.Invalid) return
        GLES30.glUniform2f(command.location.location, command.value.x, command.value.y)
    }
//    private fun processSetUniformVec3(command: SetUniformVec3Command) {
//        if (currentShaderProgramHandle == ShaderProgramHandle.Invalid || command.location == UniformHandle.Invalid) return
//        GLES30.glUniform3f(command.location.location, command.x, command.y, command.z)
//    }
    private fun processSetUniformVec4(command: SetUniformVec4Command) {
        if (currentShaderProgramHandle == ShaderProgramHandle.Invalid || command.location == UniformHandle.Invalid) return
        GLES30.glUniform4f(command.location.location, command.value.red, command.value.green, command.value.blue, command.value.alpha)
    }
    private fun processSetUniformInt(command: SetUniformIntCommand) {
        if (currentShaderProgramHandle == ShaderProgramHandle.Invalid || command.location == UniformHandle.Invalid) return
        GLES30.glUniform1i(command.location.location, command.value)
    }
    private fun processSetUniformIntArray(command: SetUniformIntArrayCommand) {
        if (currentShaderProgramHandle == ShaderProgramHandle.Invalid || command.location == UniformHandle.Invalid) return
        GLES30.glUniform1iv(command.location.location, command.values.size, command.values, 0)
    }
    // private fun processSetUniformMat4(command: SetUniformMat4Command) { ... }


    private fun processSetBlendState(command: SetBlendStateCommand) {
        if (isBlendEnabled != command.enabled) {
            if (command.enabled) {
                GLES30.glEnable(GLES30.GL_BLEND)
            } else {
                GLES30.glDisable(GLES30.GL_BLEND)
            }
            isBlendEnabled = command.enabled
            // logd(TAG,"SetBlendEnabled: ${command.enabled}")
            // Force blend func/eq update when enabling
            if(command.enabled) {
                currentBlendSrcFactor = -1
                currentBlendDstFactor = -1
                currentBlendEquation = -1
            }
        }

        // Only set func/eq if blending is actually enabled
        if (command.enabled) {
            if (command.srcFactor != currentBlendSrcFactor || command.dstFactor != currentBlendDstFactor) {
                GLES30.glBlendFunc(command.srcFactor, command.dstFactor)
                currentBlendSrcFactor = command.srcFactor
                currentBlendDstFactor = command.dstFactor
                // logd(TAG,"SetBlendFunc: src=${command.srcFactor}, dst=${command.dstFactor}")
            }
            if (command.equation != currentBlendEquation) {
                GLES30.glBlendEquation(command.equation)
                currentBlendEquation = command.equation
                // logd(TAG,"SetBlendEquation: eq=${command.equation}")
            }
        }
    }

    private fun processDraw(command: DrawCommand) {
        if (currentShaderProgramHandle == ShaderProgramHandle.Invalid) {
            loge(TAG, "Draw call skipped: No shader program bound.")
            return
        }
        if (command.vertexArrayHandle == VertexArrayHandle.Invalid) {
            loge(TAG, "Draw call skipped: Invalid VAO handle.")
            return
        }

        // Bind VAO if different
        if (command.vertexArrayHandle != currentVertexArrayHandle) {
            GLES30.glBindVertexArray(command.vertexArrayHandle.id)
            currentVertexArrayHandle = command.vertexArrayHandle
            // logd(TAG,"BindVAO: ${command.vertexArrayHandle.id}")
        }

        // Assuming textures and uniforms are set correctly by preceding commands

        if (command.instanceCount > 1) {
            // TODO: Implement instanced drawing if needed
            // GLES30.glDrawElementsInstanced(...)
            logw(TAG,"Instanced drawing not fully implemented in this example.")
        } else {
            GLES30.glDrawElements(
                command.primitiveType,
                command.indexCount,
                GLES30.GL_UNSIGNED_INT, // Assuming 32-bit indices based on Imla [cite: 254]
                command.indexOffset * 4 // Offset needs to be in bytes for buffer offset
            )
            // logd(TAG,"DrawElements: count=${command.indexCount}, type=${command.primitiveType}")
        }
        // TODO: Increment draw call statistics if needed
    }

    private fun processBlitFramebuffer(command: BlitFramebufferCommand) {
        // Blit doesn't typically use shader/vao state, but needs FBOs bound correctly
        if (command.srcFramebuffer == FramebufferHandle.Invalid || command.dstFramebuffer == FramebufferHandle.Invalid) {
            loge(TAG, "Blit call skipped: Invalid source or destination FBO handle.")
            return
        }

        // Bind read and draw framebuffers
        // Note: This overrides currentFramebufferHandle cache without going through processSetRenderTarget
        // We might need to update the cache or be aware that blit changes GL state directly.
        // Alternatively, SetRenderTarget could take Read/Draw targets.
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, command.srcFramebuffer.id)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, command.dstFramebuffer.id)
        // logd(TAG,"Bind FBOs for Blit: READ=${command.srcFramebuffer.id}, DRAW=${command.dstFramebuffer.id}")

        // Perform the blit
        GLES30.glBlitFramebuffer(
            command.srcRect.left.toInt(), command.srcRect.top.toInt(), // Check Y-coord convention
            command.srcRect.right.toInt(), command.srcRect.bottom.toInt(),
            command.dstRect.left.toInt(), command.dstRect.top.toInt(), // Check Y-coord convention
            command.dstRect.right.toInt(), command.dstRect.bottom.toInt(),
            command.mask,
            command.filter
        )
        // logd(TAG,"BlitFramebuffer: ${command.srcRect} -> ${command.dstRect}")


        // Restore the DRAW framebuffer binding to what the cache *thinks* it should be?
        // Or assume the next SetRenderTargetCommand will fix it.
        // For now, let's restore based on cache to keep it consistent.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, currentFramebufferHandle.id)
        // Make sure READ is unbound too if needed, often binding GL_FRAMEBUFFER sets both R/D targets
        // GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0) // Or back to currentFramebufferHandle? Usually 0 or same as DRAW.
    }
}