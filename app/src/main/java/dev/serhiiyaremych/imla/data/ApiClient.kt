/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.data

import dev.serhiiyaremych.imla.DemoApp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

object ApiClient {
    fun getPosts(): Flow<ImmutableList<UserPost>> {
        return flow {
            val posts = withContext(Dispatchers.IO) {
                DemoApp.posts.toPersistentList()
            }
            emit(posts)
        }
    }
}