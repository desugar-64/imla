/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.internal.legacy.scene

internal data class RecordedSceneTraceCounter(
    val name: String,
    val value: Long
) {
    val sceneName: String
        get() = name.removePrefix("ImlaScene/")
}

internal class RecordingSceneTraceCounterRecorder : SceneTraceCounterRecorder {
    val records = mutableListOf<RecordedSceneTraceCounter>()

    override fun setCounter(name: String, value: Long) {
        records += RecordedSceneTraceCounter(name = name, value = value)
    }

    fun atlasRecords(): List<RecordedSceneTraceCounter> {
        return records.filter { record ->
            record.sceneName.startsWith("atlas.") ||
                record.sceneName.startsWith("gauge.atlas.")
        }
    }

    fun atlasRecordNames(): List<String> {
        return atlasRecords().map { record -> record.sceneName }
    }

    fun count(sceneName: String): Int {
        return records.count { record -> record.sceneName == sceneName }
    }

    fun values(sceneName: String): List<Long> {
        return records.filter { record -> record.sceneName == sceneName }
            .map { record -> record.value }
    }
}

internal fun <T> withRecordingSceneTraceCounters(
    block: (RecordingSceneTraceCounterRecorder) -> T
): T {
    val recorder = RecordingSceneTraceCounterRecorder()
    val handle = SceneTraceCounters.recordWith(recorder)
    try {
        return block(recorder)
    } finally {
        handle.close()
    }
}
