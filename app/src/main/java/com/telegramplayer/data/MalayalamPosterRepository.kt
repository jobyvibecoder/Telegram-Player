package com.telegramplayer.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Repository to sync and provide Malayalam movie/series posters from the malayalam-subs GitHub repo.
 */
class MalayalamPosterRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "MalayalamPosterRepo"
        private const val POSTERS_URL = "https://raw.githubusercontent.com/arsvibesystems/malayalam-subs/main/data/subtitles.json"
        private const val CACHE_FILE_NAME = "malayalam_posters_cache.json"
        private val SYNC_INTERVAL_MS = TimeUnit.HOURS.toMillis(8)

        @Volatile
        private var instance: MalayalamPosterRepository? = null

        fun getInstance(context: Context): MalayalamPosterRepository {
            return instance ?: synchronized(this) {
                instance ?: MalayalamPosterRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
    
    // In-memory cache for ultra-fast lookups (populated on first access or after sync)
    private var postersCache: List<PosterEntry>? = null

    /**
     * Searches for a poster locally. Takes less than 1ms because it uses in-memory cache.
     * Returns the poster URL if found, null otherwise.
     */
    suspend fun searchPoster(title: String, year: Int?, isSeries: Boolean): String? = withContext(Dispatchers.Default) {
        if (title.isBlank()) return@withContext null
        
        ensureCacheLoaded()
        val cache = postersCache ?: return@withContext null

        val searchTitleLower = title.lowercase()
        
        // 1. Try to match title AND year
        var match = cache.firstOrNull { 
            it.titleLower.contains(searchTitleLower) && (year == null || it.year == year) 
        }

        // 2. Fallback: match just the title if it's long enough
        if (match == null && searchTitleLower.length >= 3) {
            match = cache.firstOrNull { it.titleLower.contains(searchTitleLower) }
        }

        match?.thumbnailUrl
    }

    /**
     * Syncs the latest posters from GitHub if the cache is older than 8 hours.
     * Runs strictly on IO dispatcher. Does not block UI.
     */
    suspend fun syncIfNeeded() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cacheFile.exists() && (now - cacheFile.lastModified()) < SYNC_INTERVAL_MS) {
            return@withContext // Cache is still fresh
        }

        try {
            Log.d(TAG, "Syncing Malayalam posters from GitHub...")
            val connection = URL(POSTERS_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            if (connection.responseCode == 200) {
                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(responseBody)
                
                // Parse and save only what we need to a smaller local JSON file
                val optimizedArray = JSONArray()
                val newCacheList = mutableListOf<PosterEntry>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val t = obj.optString("title", "")
                    val y = obj.optInt("year", -1)
                    val thumb = obj.optString("thumbnail_url", "")
                    
                    if (t.isNotBlank() && thumb.isNotBlank()) {
                        val optimizedObj = JSONObject().apply {
                            put("t", t)
                            put("y", y)
                            put("u", thumb)
                        }
                        optimizedArray.put(optimizedObj)
                        newCacheList.add(PosterEntry(t, t.lowercase(), if (y > 0) y else null, thumb))
                    }
                }

                // Save to disk
                cacheFile.writeText(optimizedArray.toString())
                
                // Update in-memory cache safely
                synchronized(this@MalayalamPosterRepository) {
                    postersCache = newCacheList
                }
                Log.d(TAG, "Synced ${newCacheList.size} Malayalam posters successfully.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync Malayalam posters", e)
        }
    }

    private fun ensureCacheLoaded() {
        if (postersCache != null) return
        
        synchronized(this) {
            if (postersCache != null) return
            
            if (cacheFile.exists()) {
                try {
                    val jsonArray = JSONArray(cacheFile.readText())
                    val list = mutableListOf<PosterEntry>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val t = obj.optString("t", "")
                        val y = obj.optInt("y", -1)
                        val u = obj.optString("u", "")
                        if (t.isNotBlank() && u.isNotBlank()) {
                            list.add(PosterEntry(t, t.lowercase(), if (y > 0) y else null, u))
                        }
                    }
                    postersCache = list
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read poster cache", e)
                    postersCache = emptyList() // Prevent constant reloading on corrupt file
                }
            } else {
                postersCache = emptyList()
            }
        }
    }

    private data class PosterEntry(
        val title: String,
        val titleLower: String,
        val year: Int?,
        val thumbnailUrl: String
    )
}
