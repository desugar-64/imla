/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.renderer.camera

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Mat4
import dev.romainguy.kotlin.math.inverse
import dev.romainguy.kotlin.math.ortho
import dev.romainguy.kotlin.math.rotation
import dev.romainguy.kotlin.math.translation

internal class OrthographicCamera(
    left: Float, right: Float, bottom: Float, top: Float
) {
    var position: Float3 = Float3(0.0f)
        set(value) {
            field = value
            recalculateViewMatrix()
        }

    var rotation: Float = 0.0f
        set(value) {
            field = value
            recalculateViewMatrix()
        }

    var projectionMatrix: Mat4 = Mat4.identity()
        private set
    var viewMatrix: Mat4 = Mat4.identity()
        private set
    var viewProjectionMatrix: Mat4 = Mat4.identity()
        private set

    init {
        projectionMatrix = ortho(left, right, bottom, top, -1.0f, 1.0f)
        viewProjectionMatrix = projectionMatrix * viewMatrix
    }

    fun setProjection(left: Float, right: Float, bottom: Float, top: Float) {
        projectionMatrix = ortho(left, right, bottom, top, -1.0f, 1.0f)
        viewProjectionMatrix = projectionMatrix * viewMatrix
    }

    private fun recalculateViewMatrix() {
        val transform = translation(position) * rotation(Float3(0.0f, 0.0f, 1.0f), rotation)
        viewMatrix = inverse(transform)
        viewProjectionMatrix = projectionMatrix * viewMatrix
    }
}