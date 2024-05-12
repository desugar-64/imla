/*
 * Copyright 2024, Serhii Yaremych
 * SPDX-License-Identifier: MIT
 */

package dev.serhiiyaremych.imla.ui.userpost

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Message
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.serhiiyaremych.imla.data.UserPost
import java.time.Instant
import java.util.concurrent.TimeUnit

private const val SHOW_ICONS = true

private val AvatarSizeLarge = 44.dp
private val AvatarSizeSmall = 22.dp

@Composable
fun UserPostView(
    post: UserPost,
    modifier: Modifier = Modifier,
    compactMode: Boolean = false,
    monochromeBottomIcons: Boolean = false,
    onImageClick: (imageUrl: String) -> Unit = {},
    onItemClick: (id: String) -> Unit = {}
) {
    val contentPadding = if (compactMode) 8.dp else 16.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onItemClick.invoke(post.id) }
            )
            .padding(contentPadding)
    ) {
        UserLine(post, compactMode)
        Column(
            modifier = Modifier
                .padding(start = if (compactMode) 0.dp else contentPadding)
                .padding(
                    start = if (compactMode) 0.dp else AvatarSizeLarge,
                    top = if (compactMode) AvatarSizeSmall else AvatarSizeLarge / 2
                )
                .fillMaxWidth()
        ) {

            Spacer(modifier = Modifier.height(8.dp))

            val textSize = if (compactMode) 12.sp else 14.sp
            Text(
                text = post.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = textSize)
            )

            if (post.images.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                ImageList(
                    images = post.images,
                    compactMode = compactMode,
                    onImageClick = onImageClick
                )
            }
            if (post.reply != null) {
                Spacer(modifier = Modifier.height(8.dp))
                ReplyView(post.reply)
            }

            if (post.poll != null) {
                Spacer(modifier = Modifier.height(8.dp))
                PollView(poll = post.poll, compactMode = compactMode)
            }

            if (compactMode.not()) {
                TweetFooter(post, monochromeBottomIcons)
            }
        }
    }
}

@Composable
private fun ReplyView(tweet: UserPost) {
    UserPostView(
        modifier = Modifier
            .height(IntrinsicSize.Min)
            .border(1.dp, Color.LightGray, RoundedCornerShape(6.dp))
            .padding(2.dp),
        post = tweet,
        compactMode = true
    )
}

@Composable
private fun UserLine(tweet: UserPost, compactMode: Boolean) {
    val nameColor = MaterialTheme.colorScheme.onBackground
    val userString =
        remember(tweet.userFullName, tweet.userNickname, tweet.createdAt, compactMode, nameColor) {
            buildUserString(
                fullName = tweet.userFullName,
                nickName = tweet.userNickname,
                createdAt = tweet.createdAt,
                compactMode = compactMode,
                nameColor = nameColor
            )
        }
    Row {
        Avatar(userAvatar = tweet.userAvatar, compactMode = compactMode)
        Spacer(modifier = Modifier.width(if (compactMode) 8.dp else 16.dp))
        Text(text = userString, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TweetFooter(tweet: UserPost, monochromeBottomIcons: Boolean) {
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        horizontalArrangement = Arrangement.SpaceAround,
        modifier = Modifier.fillMaxWidth()
    ) {
        IconWithText(
            icon = Icons.Outlined.Message,
            text = "${tweet.messages}",
            iconTint = if (monochromeBottomIcons) Color.LightGray else MaterialTheme.colorScheme.primary
        )
        IconWithText(
            icon = Icons.Outlined.Refresh,
            text = "${tweet.replyCount}",
            iconTint = if (monochromeBottomIcons) Color.LightGray else MaterialTheme.colorScheme.secondary
        )
        IconWithText(
            icon = Icons.Outlined.Favorite,
            text = "${tweet.likes}",
            iconTint = if (monochromeBottomIcons) Color.LightGray else MaterialTheme.colorScheme.tertiary
        )
        IconWithText(
            icon = Icons.Outlined.Share,
            text = "",
            iconTint = if (monochromeBottomIcons) Color.LightGray else Color.LightGray
        )
    }
}

private fun buildUserString(
    fullName: String,
    nickName: String,
    createdAt: Long,
    compactMode: Boolean,
    nameColor: Color
) = buildAnnotatedString {
    withStyle(
        SpanStyle(
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            color = nameColor
        )
    ) {
        append(fullName)
    }
    append(" ")
    withStyle(
        SpanStyle(
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            color = Color.DarkGray
        )
    ) {
        append(nickName)
        append(" • ")
        append(formattedTime(createdAt, compactMode))
    }
}

private fun formattedTime(createdAt: Long, compactMode: Boolean): String {
    val hours = if (compactMode) "h" else " hours"
    val minutes = if (compactMode) "min" else " minutes"
    val timeAgo = buildString {
        val now = Instant.now()
        val diff = now.minusMillis(createdAt).toEpochMilli()
        val hrs = TimeUnit.MILLISECONDS.toHours(diff)
        val minutesTime = if (hrs > 0) 0L else TimeUnit.MILLISECONDS.toMinutes(diff)
        if (hrs > 0) {
            when (hrs) {
                1L -> if (compactMode) append("1h ago") else append("an hour ago")
                else -> append("$hrs$hours ago")
            }
            append(" ")
        }
        if (minutesTime > 0) {
            when (minutesTime) {
                1L -> append("now")
                else -> append("$minutesTime$minutes ago")
            }
        }

        if (this.isBlank()) {
            append("now")
        }
    }
    return timeAgo
}

@Composable
fun Avatar(userAvatar: String, compactMode: Boolean) {
    val imgLayoutSizeDp = if (compactMode) AvatarSizeSmall else AvatarSizeLarge
    SinglePostImage(
        modifier = Modifier.size(imgLayoutSizeDp),
        clipShape = CircleShape,
        imgUrl = userAvatar,
        compactMode = compactMode
    )
}

@Composable
fun ImageList(
    images: List<String>,
    compactMode: Boolean,
    imageRowHeight: Dp = if (compactMode) 125.dp else 150.dp,
    onImageClick: (imageUrl: String) -> Unit
) {
    when (images.size) {
        1 -> MonoImage(images[0], imageRowHeight, compactMode, onImageClick)
        2 -> DuoImage(images[0], images[1], imageRowHeight, compactMode, onImageClick)
        3 -> TripleImage(
            imageHeight = imageRowHeight,
            imgA = images[0],
            imgB = images[1],
            imgC = images[2],
            compactMode = compactMode,
            onImageClick = onImageClick
        )

        4 -> QuadImage(
            imageHeight = imageRowHeight + 56.dp,
            imgA = images[0],
            imgB = images[1],
            imgC = images[2],
            imgD = images[3],
            compactMode = compactMode,
            onImageClick = onImageClick
        )

        else -> ImageRow(imageRowHeight, images, compactMode, onImageClick)
    }

}

@Composable
private fun IconWithText(icon: ImageVector, text: String, iconTint: Color) {

    CompositionLocalProvider(
        (LocalContentColor provides iconTint)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (SHOW_ICONS) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (text.isNotEmpty()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp)
                )
            }
        }
    }
}