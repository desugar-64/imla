/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import dev.serhiiyaremych.imla.data.UserPost
import kotlinx.serialization.json.Json

class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = this
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        private lateinit var ctx: Context

        val posts: List<UserPost> by lazy {
            val tweetsJson = ctx.assets.open("loremipsum.json").bufferedReader().readText()
            Json.decodeFromString<List<UserPost>>(tweetsJson)
        }
    }
}