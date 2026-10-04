package com.telegramplayer.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Lightweight startup cache that persists the last 50 media items and chat list
 * as a small JSON file in internal storage. On subsequent launches, the UI reads
 * from this file (< 5ms) while TDLib initializes in the background.
 *
 * This bypasses the slow SQLite database read that causes startup lag on Fire TV.
 */
class StartupCache(context: Context) {
    companion object {
        private const val TAG = "StartupCache"
        private const val CACHE_FILE = "startup_cache.json"
        private const val KEY_CHATS = "chats"
        private const val KEY_MEDIA = "media"
        private const val MAX_MEDIA_ITEMS = 50
    }

    private val cacheFile = File(context.filesDir, CACHE_FILE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // In-memory cache (loaded once from disk, then served from RAM)
    private var cachedJson: JSONObject? = null

    /**
     * Save chat list to cache. Fire-and-forget on IO thread.
     */
    fun saveChats(chats: List<ChatInfo>) {
        scope.launch {
            try {
                val json = getOrLoadJson()
                val chatsArray = JSONArray()
                chats.forEach { chat ->
                    chatsArray.put(JSONObject().apply {
                        put("id", chat.id)
                        put("title", chat.title)
                        put("type", chat.type.name)
                        put("photoPath", chat.photoPath ?: "")
                        put("lastMessageDate", chat.lastMessageDate)
                    })
                }
                json.put(KEY_CHATS, chatsArray)
                writeJson(json)
                Log.d(TAG, "Saved ${chats.size} chats to startup cache")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving chats to cache", e)
            }
        }
    }

    /**
     * Save media items to cache. Fire-and-forget on IO thread.
     * Only persists the most recent [MAX_MEDIA_ITEMS] items.
     */
    fun saveMediaItems(items: List<MediaItem>) {
        scope.launch {
            try {
                val json = getOrLoadJson()
                val mediaArray = JSONArray()
                items.take(MAX_MEDIA_ITEMS).forEach { item ->
                    mediaArray.put(JSONObject().apply {
                        put("id", item.id)
                        put("chatId", item.chatId)
                        put("messageId", item.messageId)
                        put("fileId", item.fileId)
                        put("remoteFileId", item.remoteFileId)
                        put("title", item.title)
                        put("fileName", item.fileName)
                        put("mimeType", item.mimeType)
                        put("duration", item.duration)
                        put("size", item.size)
                        put("thumbnailPath", item.thumbnailPath ?: "")
                        put("chatTitle", item.chatTitle)
                        put("mediaType", item.mediaType.name)
                    })
                }
                json.put(KEY_MEDIA, mediaArray)
                writeJson(json)
                Log.d(TAG, "Saved ${items.take(MAX_MEDIA_ITEMS).size} media items to startup cache")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving media to cache", e)
            }
        }
    }

    /**
     * Load cached chats synchronously. Returns null if no cache exists.
     * First call reads from disk (~2-5ms), subsequent calls serve from RAM.
     */
    fun loadChats(): List<ChatInfo>? {
        return try {
            val json = getOrLoadJson()
            val chatsArray = json.optJSONArray(KEY_CHATS) ?: return null
            if (chatsArray.length() == 0) return null

            val chats = mutableListOf<ChatInfo>()
            for (i in 0 until chatsArray.length()) {
                val obj = chatsArray.getJSONObject(i)
                val photoPath = obj.optString("photoPath", "").ifEmpty { null }
                chats.add(ChatInfo(
                    id = obj.getLong("id"),
                    title = obj.getString("title"),
                    type = ChatType.valueOf(obj.getString("type")),
                    photoPath = photoPath,
                    lastMessageDate = obj.optInt("lastMessageDate", 0)
                ))
            }
            Log.d(TAG, "Loaded ${chats.size} chats from startup cache")
            chats
        } catch (e: Exception) {
            Log.e(TAG, "Error loading chats from cache", e)
            null
        }
    }

    /**
     * Load cached media items synchronously. Returns null if no cache exists.
     */
    fun loadMediaItems(): List<MediaItem>? {
        return try {
            val json = getOrLoadJson()
            val mediaArray = json.optJSONArray(KEY_MEDIA) ?: return null
            if (mediaArray.length() == 0) return null

            val items = mutableListOf<MediaItem>()
            for (i in 0 until mediaArray.length()) {
                val obj = mediaArray.getJSONObject(i)
                val thumbnailPath = obj.optString("thumbnailPath", "").ifEmpty { null }
                items.add(MediaItem(
                    id = obj.getLong("id"),
                    chatId = obj.getLong("chatId"),
                    messageId = obj.getLong("messageId"),
                    fileId = obj.getInt("fileId"),
                    remoteFileId = obj.getString("remoteFileId"),
                    title = obj.getString("title"),
                    fileName = obj.getString("fileName"),
                    mimeType = obj.getString("mimeType"),
                    duration = obj.optInt("duration", 0),
                    size = obj.getLong("size"),
                    thumbnailPath = thumbnailPath,
                    chatTitle = obj.getString("chatTitle"),
                    mediaType = MediaType.valueOf(obj.getString("mediaType"))
                ))
            }
            Log.d(TAG, "Loaded ${items.size} media items from startup cache")
            items
        } catch (e: Exception) {
            Log.e(TAG, "Error loading media from cache", e)
            null
        }
    }

    @Synchronized
    private fun getOrLoadJson(): JSONObject {
        cachedJson?.let { return it }
        val json = if (cacheFile.exists()) {
            try {
                JSONObject(cacheFile.readText())
            } catch (e: Exception) {
                JSONObject()
            }
        } else {
            JSONObject()
        }
        cachedJson = json
        return json
    }

    @Synchronized
    private fun writeJson(json: JSONObject) {
        cachedJson = json
        cacheFile.writeText(json.toString())
    }

    /**
     * Clear all cached data (both in-memory and on disk).
     * Called on logout to prevent stale data from a previous account.
     */
    @Synchronized
    fun clear() {
        cachedJson = null
        try {
            if (cacheFile.exists()) {
                cacheFile.delete()
                Log.d(TAG, "Startup cache cleared")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing startup cache", e)
        }
    }
}

