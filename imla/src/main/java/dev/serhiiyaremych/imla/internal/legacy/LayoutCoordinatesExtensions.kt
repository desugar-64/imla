/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy

import androidx.compose.ui.layout.LayoutCoordinates

internal fun LayoutCoordinates.rootCoordinates(): LayoutCoordinates {
    var current: LayoutCoordinates = this
    while (true) {
        current.parentLayoutCoordinates?.let { parent ->
            current = parent
        } ?: break
    }
    return current
}

internal fun LayoutCoordinates.treeDepth(): Int {
    var depth = 0
    var current: LayoutCoordinates? = parentLayoutCoordinates
    while (current != null) {
        depth++
        current = current.parentLayoutCoordinates
    }
    return depth
}
