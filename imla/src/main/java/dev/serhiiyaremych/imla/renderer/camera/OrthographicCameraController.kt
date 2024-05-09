/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.renderer.camera

internal class OrthographicCameraController {
    var camera: OrthographicCamera
        private set
    var aspectRatio: Float = 1.0f
        private set

    var zoomLevel: Float = 1.0f

    private var viewPortWidth: Int = 0
    private var viewPortHeight: Int = 0
    private var pixelCoordinates: Boolean = false
    val orthographicSize: Float
        get() = viewPortHeight / 2f / zoomLevel

    private constructor(aspectRatio: Float, height: Int) {
        this.aspectRatio = aspectRatio
        this.viewPortHeight = height

        camera = OrthographicCamera(
            left = -aspectRatio * orthographicSize,
            right = aspectRatio * orthographicSize,
            bottom = -orthographicSize,
            top = orthographicSize
        )
        onVisibleBoundsResize(width = 0, height = height)
    }

    private constructor(width: Int, height: Int) {
        pixelCoordinates = true
        camera = OrthographicCamera(
            left = 0f,
            right = width.toFloat(),
            bottom = 0f,
            top = height.toFloat()
        )
        onVisibleBoundsResize(width, height)
    }

    fun onVisibleBoundsResize(width: Int, height: Int) {
        if (width != viewPortWidth || height != viewPortHeight) {
            viewPortWidth = width
            viewPortHeight = height
            if (width != 0 && height != 0) {
                aspectRatio = width / height.toFloat()
            }
            updateCameraProjection()
        }
    }

    fun updateCameraProjection() {
        if (pixelCoordinates) {
            camera.setProjection(
                left = 0f,
                right = viewPortWidth.toFloat(),
                bottom = 0f,
                top = viewPortHeight.toFloat()
            )
        } else {
            camera.setProjection(
                left = -aspectRatio * orthographicSize,
                right = aspectRatio * orthographicSize,
                bottom = -orthographicSize,
                top = orthographicSize
            )
        }
    }

    companion object {
        fun createPixelUnitsController(
            viewportWidth: Int,
            viewportHeight: Int
        ) = OrthographicCameraController(viewportWidth, viewportHeight)

        fun createWorldUnitsController(
            viewportWidth: Int,
            viewportHeight: Int
        ) = OrthographicCameraController(viewportWidth / viewportHeight.toFloat(), viewportHeight)
    }

}
