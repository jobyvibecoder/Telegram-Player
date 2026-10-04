package com.telegramplayer.data

/**
 * Represents a media item from Telegram (video, audio).
 * Uses Long for file sizes to support files up to 4GB.
 */
data class MediaItem(
    val id: Long,
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val remoteFileId: String,
    val title: String,
    val fileName: String,
    val mimeType: String,
    val duration: Int,          // in seconds
    val size: Long,             // Long for >2GB support
    val thumbnailPath: String?,
    val chatTitle: String,
    val mediaType: MediaType,
    val date: Long = 0L
)


enum class MediaType {
    VIDEO,
    AUDIO,
    DOCUMENT
}

/**
 * Represents a chat/channel/group in Telegram.
 */
data class ChatInfo(
    val id: Long,
    val title: String,
    val type: ChatType,
    val photoPath: String?,
    val lastMessageDate: Int = 0,
    val order: Long = 0,
    val mediaCount: Int = 0
)

enum class ChatType {
    PRIVATE,
    GROUP,
    CHANNEL
}

data class SeeMoreItem(
    val chatType: ChatType,
    val title: String
)

