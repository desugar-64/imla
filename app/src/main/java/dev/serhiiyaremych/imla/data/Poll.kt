/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
@Immutable
data class Poll(
    val isCompleted: Boolean,
    val totalVotes: Int,
    val positions: List<PollPosition>
)

@Serializable
@Immutable
data class PollPosition(
    val text: String,
    val voted: Int
)