/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("unused")

package dev.serhiiyaremych.imla.internal.render

import androidx.collection.FloatFloatPair

internal typealias Float2 = FloatFloatPair

internal val FloatFloatPair.x: Float get() = first
internal val FloatFloatPair.y: Float get() = second