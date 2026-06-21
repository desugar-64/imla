/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable


@Serializable
@Immutable
data class UserPost(
    val id: String,
    val userNickname: String,
    val userFullName: String,
    val userAvatar: String,
    val text: String,
    val images: List<String>,
    val likes: Int,
    val replyCount: Int,
    val messages: Int,
    val comments: List<UserPost>,
    val createdAt: Long,
    val reply: UserPost?,
    val poll: Poll?
)

