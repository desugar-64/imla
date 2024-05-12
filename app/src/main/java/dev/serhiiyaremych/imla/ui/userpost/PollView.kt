/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.ui.userpost

import androidx.annotation.FloatRange
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.serhiiyaremych.imla.data.Poll
import dev.serhiiyaremych.imla.data.PollPosition
import dev.serhiiyaremych.imla.ui.theme.ImlaTheme
import kotlin.math.roundToInt

@Composable
fun PollView(poll: Poll, compactMode: Boolean, modifier: Modifier = Modifier) {
    val borderColor = MaterialTheme.colorScheme.primary
    val buttonBorder = remember(borderColor) {
        BorderStroke(1.dp, borderColor)
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (pollPosition in poll.positions) {
            key(pollPosition) {
                if (poll.isCompleted) {
                    val progress = pollPosition.voted / poll.totalVotes.toFloat()
                    PollProgressLine(progress = progress, pollPosition.text, compactMode)
                } else {
                    PollButton(buttonBorder, pollPosition.text, compactMode)
                }
            }
        }

        Text(
            text = "${poll.totalVotes} votes",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun PollProgressLine(
    @FloatRange(from = 0.0, to = 1.0) progress: Float,
    label: String,
    compactMode: Boolean
) {
    var pollProgress by rememberSaveable {
        mutableFloatStateOf(0.0f)
    }
    val animatedProgress by animateFloatAsState(
        targetValue = pollProgress,
        animationSpec = tween(1500),
        label = "animatedProgress"
    )


    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ButtonDefaults.MinHeight + 4.dp)
            .drawBehind {
                val cornerSize = 6.dp.toPx()
                val width = (size.width * animatedProgress).coerceAtLeast(cornerSize)
                drawRoundRect(
                    color = Color.LightGray,
                    size = Size(
                        width = width,
                        height = size.height
                    ),
                    cornerRadius = CornerRadius(cornerSize, cornerSize)
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            val textSize = if (compactMode) 12.sp else 14.sp
            val text = remember(progress.roundToInt()) {
                "${(progress * 100).roundToInt().coerceIn(0, 100)}%"
            }
            Text(
                modifier = Modifier
                    .weight(1.0f)
                    .padding(horizontal = 8.dp),
                text = label,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = textSize
            )
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = textSize
            )
        }
    }

    SideEffect {
        pollProgress = progress
    }
}

@Composable
private fun PollButton(
    buttonBorder: BorderStroke,
    title: String,
    compactMode: Boolean
) {
    OutlinedButton(
        modifier = Modifier
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        border = buttonBorder,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        onClick = { /*TODO*/ }
    ) {
        val textSize = if (compactMode) 12.sp else 14.sp
        Text(
            text = title,
            color = MaterialTheme.colorScheme.primary,
            fontSize = textSize
        )
    }
}

@Preview(name = "Poll not completed", device = Devices.PIXEL)
@Composable
private fun PollNotCompletedPreview() {
    ImlaTheme {
        val poll = Poll(
            isCompleted = false,
            totalVotes = 0,
            positions = listOf(
                PollPosition("Option 1", 0),
                PollPosition("Option 2", 0),
                PollPosition("Option 3", 0),
            )
        )
        PollView(modifier = Modifier.fillMaxWidth(), poll = poll, compactMode = false)
    }
}

@Preview(name = "Poll completed", device = Devices.PIXEL)
@Composable
private fun PollCompletedPreview() {
    ImlaTheme {
        val poll = Poll(
            isCompleted = true,
            totalVotes = 100,
            positions = listOf(
                PollPosition("Option 0", 12),
                PollPosition("Option 1", 18),
                PollPosition("Option 2", 60),
                PollPosition("Option 3\ndddd\nddddf", 20),
            )
        )
        PollView(modifier = Modifier.fillMaxWidth(), poll = poll, compactMode = false)
    }
}