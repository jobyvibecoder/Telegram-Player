package com.telegramplayer.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Repository for fetching movie posters and metadata from Cinemeta (Stremio addon).
 * Parses filenames aggressively to extract clean movie title + year, then queries Cinemeta search.
 * 
 * Caching strategy:
 * 1. In-memory LRU cache (100 entries) for instant access
 * 2. SharedPreferences disk cache for offline/restart persistence
 * 3. Network call to Cinemeta API as last resort
 */
class CinemetaRepository private constructor(context: Context) {

    companion object {
        private const val TAG = "CinemetaRepository"
        private const val PREFS_NAME = "cinemeta_poster_cache_v4"
        private const val NO_POSTER = "NO_POSTER"
        private const val CACHE_MAX_SIZE = 100
        private const val API_TIMEOUT_MS = 8000

        @Volatile
        private var instance: CinemetaRepository? = null

        fun getInstance(context: Context): CinemetaRepository {
            return instance ?: synchronized(this) {
                instance ?: CinemetaRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val memoryCache = LruCache<String, String>(CACHE_MAX_SIZE)
    private val diskCache: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun looksLikeMovie(fileName: String, durationSeconds: Int = 0, fileSizeBytes: Long = 0): Boolean {
        if (durationSeconds > 0) return durationSeconds >= 1200
        return fileSizeBytes >= 209_715_200
    }

    suspend fun searchMoviePoster(fileName: String): CinemetaResult? = withContext(Dispatchers.IO) {
        try {
            val cacheKey = fileName.lowercase().trim()

            memoryCache.get(cacheKey)?.let { return@withContext parseCachedResult(it) }
            diskCache.getString(cacheKey, null)?.let { 
                memoryCache.put(cacheKey, it)
                return@withContext parseCachedResult(it) 
            }

            val parsed = parseFileName(fileName)
            if (parsed.title.isBlank()) {
                cachePoster(cacheKey, NO_POSTER)
                return@withContext null
            }

            var result: CinemetaResult? = null
            val typesToTry = if (parsed.isSeries) listOf("series", "movie") else listOf("movie", "series")
            
            // 1. Exact match (Title + Year)
            for (type in typesToTry) {
                result = queryCinemeta(parsed.title, parsed.year, type)
                if (result != null) break
            }

            // 2. Fallback: Ignore year
            if (result == null && parsed.year != null) {
                for (type in typesToTry) {
                    result = queryCinemeta(parsed.title, null, type)
                    if (result != null) break
                }
            }

            // 3. Fallback: Short title
            if (result == null) {
                val shortTitle = parsed.title.split(" ").take(3).joinToString(" ")
                if (shortTitle != parsed.title && shortTitle.length >= 3) {
                    for (type in typesToTry) {
                        result = queryCinemeta(shortTitle, parsed.year, type)
                        if (result != null) break
                    }
                }
            }

            val cacheStr = if (result != null) "${result.posterUrl}|${result.imdbRating ?: ""}" else NO_POSTER
            cachePoster(cacheKey, cacheStr)

            result
        } catch (e: Exception) {
            Log.e(TAG, "Error searching poster for: $fileName", e)
            null
        }
    }

    private fun parseCachedResult(cached: String): CinemetaResult? {
        if (cached == NO_POSTER) return null
        val parts = cached.split("|")
        val url = parts.getOrNull(0) ?: return null
        val rating = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        return CinemetaResult(url, rating)
    }

    fun parseFileName(fileName: String): ParsedFileName {
        var name = fileName.substringBeforeLast('.')

        // Aggressive Telegram-specific cleaning
        // Remove @ChannelName, T.me/link, t.me link
        name = name.replace(Regex("(?i)@\\w+"), " ")
        name = name.replace(Regex("(?i)t\\.me/\\w+"), " ")
        name = name.replace(Regex("(?i)t\\.me"), " ")
        name = name.replace(Regex("(?i)telegram"), " ")

        // Remove brackets
        name = name.replace(Regex("\\[.*?\\]"), " ")
        name = name.replace(Regex("\\((?!\\d{4}).*?\\)"), " ") 

        // Year extraction
        val yearRegex = Regex("(?:^|[.\\s_\\-(])((?:19|20)\\d{2})(?:[.\\s_\\-)\\[]|$)")
        val yearMatch = yearRegex.find(name)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

        val titlePart = if (yearMatch != null) {
            name.substring(0, yearMatch.range.first)
        } else {
            val cutPatterns = listOf(
                "1080p", "720p", "480p", "2160p", "4k",
                "bluray", "bdrip", "brrip", "webrip", "web-dl", "webdl", "web",
                "hdtv", "dvdrip", "hdrip", "camrip", "hdcam", "tc", "ts",
                "x264", "x265", "h264", "h265", "hevc", "aac", "dts", "ac3",
                "remux", "proper", "repack", "extended", "unrated", "director's cut",
                "s0", "s1", "s2", "dual audio", "multi", "hindi", "english", "sub", "subs"
            )
            var cutIndex = name.length
            val lowerName = name.lowercase()
            for (pattern in cutPatterns) {
                val regex = Regex("\\b$pattern\\b")
                val match = regex.find(lowerName)
                if (match != null && match.range.first < cutIndex) {
                    cutIndex = match.range.first
                }
            }
            name.substring(0, cutIndex)
        }

        val cleanTitle = titlePart
            .replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        val isSeriesRegex = Regex("(?i)\\b(s\\d+|season\\s*\\d+|e\\d+|episode\\s*\\d+)\\b")
        val isSeries = isSeriesRegex.containsMatchIn(fileName)

        return ParsedFileName(cleanTitle, year, isSeries)
    }

    private fun queryCinemeta(title: String, year: Int?, type: String): CinemetaResult? {
        try {
            val queryStr = if (year != null) "$title $year" else title
            val encodedTitle = URLEncoder.encode(queryStr, "UTF-8")
            val url = "https://v3-cinemeta.strem.io/catalog/$type/top/search=$encodedTitle.json"

            Log.d(TAG, "Cinemeta query: $url")

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = API_TIMEOUT_MS
            connection.readTimeout = API_TIMEOUT_MS

            try {
                if (connection.responseCode != 200) {
                    return null
                }

                val responseBody = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(responseBody)
                val metas = json.optJSONArray("metas")

                if (metas == null || metas.length() == 0) {
                    return null
                }

                var bestMatch: JSONObject? = null
                for (i in 0 until metas.length()) {
                    val meta = metas.getJSONObject(i)
                    if (year != null) {
                        val metaYear = meta.optString("year", "")
                        if (metaYear.startsWith(year.toString())) {
                            bestMatch = meta
                            break
                        }
                    } else {
                        bestMatch = meta
                        break
                    }
                }

                if (bestMatch == null) {
                    bestMatch = metas.getJSONObject(0)
                }

                val posterPath = bestMatch?.optString("poster", "") ?: ""
                if (posterPath.isEmpty() || posterPath == "null") {
                    return null
                }

                // Cinemeta small size is 300x450, medium is 500x750
                val mediumPosterUrl = posterPath.replace("/poster/small/", "/poster/medium/")
                var imdbRating = bestMatch?.optString("imdbRating", null)?.takeIf { it != "null" && it.isNotBlank() }

                // Fetch detailed metadata if rating is missing from the search response
                if (imdbRating == null) {
                    val id = bestMatch?.optString("id", "") ?: ""
                    val type = bestMatch?.optString("type", "movie") ?: "movie"
                    if (id.isNotEmpty()) {
                        try {
                            val metaUrl = "https://v3-cinemeta.strem.io/meta/$type/$id.json"
                            val metaConn = URL(metaUrl).openConnection() as HttpURLConnection
                            metaConn.requestMethod = "GET"
                            metaConn.connectTimeout = API_TIMEOUT_MS
                            metaConn.readTimeout = API_TIMEOUT_MS
                            if (metaConn.responseCode == 200) {
                                val metaBody = metaConn.inputStream.bufferedReader().readText()
                                val metaObj = JSONObject(metaBody).optJSONObject("meta")
                                imdbRating = metaObj?.optString("imdbRating", null)?.takeIf { it != "null" && it.isNotBlank() }
                            }
                            metaConn.disconnect()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to fetch detailed meta for rating: $id", e)
                        }
                    }
                }

                return CinemetaResult(mediumPosterUrl, imdbRating)

            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cinemeta API error for: $title", e)
            return null
        }
    }

    private fun cachePoster(key: String, value: String) {
        memoryCache.put(key, value)
        diskCache.edit().putString(key, value).apply()
    }

    fun clearCache() {
        memoryCache.evictAll()
        diskCache.edit().clear().apply()
    }

    data class ParsedFileName(
        val title: String,
        val year: Int?,
        val isSeries: Boolean
    )
}

data class CinemetaResult(
    val posterUrl: String,
    val imdbRating: String?
)
