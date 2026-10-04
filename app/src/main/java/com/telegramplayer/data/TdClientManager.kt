package com.telegramplayer.data

import android.content.Context
import android.util.Log
import io.github.up9cloud.td.JsonClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * TDLib client manager using JSON interface.
 * Supports files up to 4GB using Long offsets with ReadFilePart.
 */
class TdClientManager(private val context: Context) {
    companion object {
        private const val TAG = "TdClientManager"
        private const val CHUNK_SIZE = 2L * 1024 * 1024 // 2MB chunks to reduce peak memory usage
        private const val MAX_CACHE_SIZE = 200L * 1024 * 1024 // 200MB max cache for FireTV
        private const val MAX_FILE_AGE_SECONDS = 1 * 24 * 60 * 60 // 1 day — aggressive for low-storage devices
    }

    private var clientId: Int = JsonClient.td_create_client_id()
    private val requestId = AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isInitialized = false
    private var isShuttingDown = false
    private var isLoggingOut = false

    // Cache to prevent latest media from disappearing when TDLib temporarily invalidates local messages during gap fetches
    private val discoveredMediaCache = java.util.concurrent.ConcurrentHashMap<Int, MediaItem>()

    // Cache of fileId -> local file path to avoid repeated TDLib lookups
    private val localPathCache = ConcurrentHashMap<Int, String>()

    // Rate-limit for optimizeStorage: at most once every 1 minute for aggressive cache management
    private var lastOptimizeTime = 0L
    private val OPTIMIZE_INTERVAL_MS = 60 * 1000L

    // Authentication state flow
    private val _authState = MutableStateFlow<AuthState>(AuthState.Initial)
    val authState: StateFlow<AuthState> = _authState

    // QR code link for authentication
    private val _qrCodeLink = MutableStateFlow<String?>(null)
    val qrCodeLink: StateFlow<String?> = _qrCodeLink

    // Connection state tracking for watchdog
    private val _connectionState = MutableStateFlow("connectionStateWaitingForNetwork")
    private var connectionWatchdogJob: Job? = null

    // Logout completion signal
    private val _logoutComplete = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // Flow of incoming updates
    private val _updateFlow = MutableSharedFlow<JSONObject>(extraBufferCapacity = 128)
    val updateFlow: SharedFlow<JSONObject> = _updateFlow

    // API credentials (to be set by user)
    var apiId: Int = 0
    var apiHash: String = ""

    init {
        Log.d(TAG, "TdClientManager created. ClientId: $clientId")
    }

    /**
     * Initialize TDLib and start receiving updates.
     * Must be called AFTER setting apiId and apiHash.
     */
    fun initialize() {
        if (isInitialized) {
            Log.d(TAG, "Already initialized, skipping")
            return
        }
        if (apiId == 0 || apiHash.isEmpty()) {
            Log.e(TAG, "Cannot initialize: apiId or apiHash not set")
            return
        }
        isInitialized = true
        Log.d(TAG, "Initializing TDLib with apiId: $apiId")
        startReceiver()
        // Send a dummy request to trigger the first updateAuthorizationState
        triggerAuthorizationStateUpdate()
    }

    private fun triggerAuthorizationStateUpdate() {
        // TDLib needs a request to start sending updates
        // getOption is a simple request that will trigger the auth flow
        val request = JSONObject().apply {
            put("@type", "getOption")
            put("name", "version")
            put("@extra", getNextRequestId())
        }
        send(request.toString())
    }

    private fun startReceiver() {
        Log.d(TAG, "Starting receiver loop")
        scope.launch {
            while (isActive) {
                try {
                    val result = withContext(Dispatchers.IO) {
                        JsonClient.td_receive(1.0)
                    }
                    if (result != null) {
                        Log.e(TAG, "Received raw json: $result") // Verbose logging
                        handleResponse(result)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in receiver loop", e)
                }
            }
        }
    }

    private fun handleResponse(jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            val extra = json.optString("@extra", "")
            val type = json.optString("@type", "")


            // Handle pending request responses
            if (extra.isNotEmpty()) {
                val deferred = pendingRequests.remove(extra)
                if (deferred != null) {
                    deferred.complete(json)
                }
            }

            // Handle updates
            if (type.startsWith("update")) {
                _updateFlow.tryEmit(json)
            }

            // Handle updates
            when (type) {
                "updateAuthorizationState" -> handleAuthorizationState(json)
                "updateConnectionState" -> handleConnectionState(json)
                "updateFile" -> handleFileUpdate(json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing response", e)
        }
    }

    private fun handleAuthorizationState(json: JSONObject) {
        val state = json.getJSONObject("authorization_state")
        val stateType = state.getString("@type")

        Log.d(TAG, "Auth state: $stateType")

        when (stateType) {
            "authorizationStateWaitTdlibParameters" -> {
                _authState.value = AuthState.WaitingForParameters
                setTdlibParameters()

                // Safety check: if TDLib takes more than 5 seconds to transition out of this state
                scope.launch {
                    delay(5000)
                    if (_authState.value == AuthState.WaitingForParameters) {
                        Log.e(TAG, "TDLib stuck in WaitingForParameters for > 5s. Forcing reset to bypass corrupted binlog.")
                        
                        // Close blocked instance
                        try {
                            val request = JSONObject().apply {
                                put("@type", "close")
                            }
                            send(request.toString())
                        } catch (e: Exception) {
                            Log.e(TAG, "Error closing stuck TDLib", e)
                        }
                        
                        // Clear cache directory
                        val dbDir = java.io.File(context.filesDir, "tdlib")
                        if (dbDir.exists()) {
                            dbDir.deleteRecursively()
                        }
                        
                        // Force re-initialize
                        clientId = io.github.up9cloud.td.JsonClient.td_create_client_id()
                        pendingRequests.forEach { (_, deferred) -> deferred.cancel() }
                        pendingRequests.clear()
                        localPathCache.clear()
                        
                        triggerAuthorizationStateUpdate()
                    }
                }
            }
            "authorizationStateWaitEncryptionKey" -> {
                _authState.value = AuthState.WaitingForEncryptionKey
                // Run on IO dispatcher so key verification doesn't block network thread
                scope.launch(Dispatchers.IO) {
                    val request = JSONObject().apply {
                        put("@type", "checkDatabaseEncryptionKey")
                        put("encryption_key", "")
                        put("@extra", getNextRequestId())
                    }
                    send(request.toString())
                }
            }
            "authorizationStateWaitPhoneNumber" -> {
                _authState.value = AuthState.WaitingForPhoneNumber
            }
            "authorizationStateWaitOtherDeviceConfirmation" -> {
                val link = state.getString("link")
                _qrCodeLink.value = link
                _authState.value = AuthState.WaitingForQrScan
            }
            "authorizationStateWaitCode" -> {
                _authState.value = AuthState.WaitingForCode
            }
            "authorizationStateWaitPassword" -> {
                _authState.value = AuthState.WaitingForPassword
            }
            "authorizationStateReady" -> {
                _authState.value = AuthState.Authorized
                // Release warmup WakeLock since handshake is complete
                TelegramBackgroundService.releaseWakeLock()

                // Start connection watchdog to detect ghost-session stalls
                startConnectionWatchdog()

                // Defer write-heavy storage cleanup to let TDLib's read path settle first
                // This avoids disk I/O contention on Fire TV's slow flash during startup
                scope.launch {
                    delay(10_000)
                    optimizeStorage()
                }
                // Force sync chats on startup
                loadChats()
            }
            "authorizationStateClosed" -> {
                _authState.value = AuthState.Closed
                
                // Stop connection watchdog
                connectionWatchdogJob?.cancel()
                connectionWatchdogJob = null
                
                if (isLoggingOut) {
                    // Logout completed — signal waiting callers and clear cache
                    Log.d(TAG, "Logout completed. Session terminated on server.")
                    StartupCache(context).clear()
                    _logoutComplete.tryEmit(Unit)
                }
                
                if (!isShuttingDown) {
                    // Clear the corrupted database directory to ensure a fresh session
                    val dbDir = java.io.File(context.filesDir, "tdlib")
                    if (dbDir.exists()) {
                        dbDir.deleteRecursively()
                    }
                }
                
                // TDLib client is dead after Closed state. We must recreate it.
                clientId = io.github.up9cloud.td.JsonClient.td_create_client_id()
                // Clear pending requests as they belong to the old clientId
                pendingRequests.forEach { (_, deferred) -> deferred.cancel() }
                pendingRequests.clear()
                localPathCache.clear()
                
                if (!isShuttingDown) {
                    isLoggingOut = false
                    triggerAuthorizationStateUpdate()
                }
            }
        }
    }

    private fun setOptionBoolean(name: String, value: Boolean) {
        val request = JSONObject().apply {
            put("@type", "setOption")
            put("name", name)
            put("value", JSONObject().apply {
                put("@type", "optionValueBoolean")
                put("value", value)
            })
            put("@extra", getNextRequestId())
        }
        send(request.toString())
    }

    private fun setOptionInteger(name: String, value: Int) {
        val request = JSONObject().apply {
            put("@type", "setOption")
            put("name", name)
            put("value", JSONObject().apply {
                put("@type", "optionValueInteger")
                put("value", value)
            })
            put("@extra", getNextRequestId())
        }
        send(request.toString())
    }

    /**
     * Clean WAL/SHM files before TDLib opens the database.
     * WAL recovery on Fire TV's slow flash storage is expensive and causes startup lag.
     * Safe because: (1) close() checkpoints WAL, (2) data is recoverable from Telegram servers,
     * (3) Fire TV often kills background apps without calling onDestroy.
     */
    private fun cleanWalFiles(dbDir: File) {
        dbDir.listFiles()?.filter {
            it.name.endsWith(".wal") || it.name.endsWith(".shm")
        }?.forEach { file ->
            val deleted = file.delete()
            Log.d(TAG, "WAL cleanup: ${file.name} deleted=$deleted")
        }
    }

    private fun setTdlibParameters() {
        val dbDir = File(context.filesDir, "tdlib")
        dbDir.mkdirs()

        // Disable stats and time sync for faster startup
        setOptionBoolean("disable_persistent_network_statistics", true)
        setOptionBoolean("disable_time_adjustment_protection", true)
        // Force IPv4 protocol routing for TDLib requests
        setOptionBoolean("prefer_ipv6", false)
        // Disable top-chat computation (saves CPU/IO on startup)
        setOptionBoolean("disable_top_chats", true)
        // Reduce notification groups TDLib processes on launch
        setOptionInteger("notification_group_count_max", 0)
        // Skip animated emoji processing
        setOptionBoolean("disable_animated_emoji", true)

        val params = JSONObject().apply {
            put("@type", "setTdlibParameters")
            put("database_directory", dbDir.absolutePath)
            put("use_message_database", true)
            put("use_file_database", false) // Reduces disk I/O — file lookups still work via getFile API
            put("use_secret_chats", false)
            put("api_id", apiId)
            put("api_hash", apiHash)
            put("system_language_code", "en")
            put("device_model", "Fire TV")
            put("application_version", "1.0.0")
            put("enable_storage_optimizer", true)
            put("ignore_file_names", true)
            put("@extra", getNextRequestId())
        }
        send(params.toString())
    }

    fun requestQrCodeAuth() {
        val request = JSONObject().apply {
            put("@type", "requestQrCodeAuthentication")
            put("other_user_ids", org.json.JSONArray())
            put("@extra", getNextRequestId())
        }
        send(request.toString())
    }

    fun sendPhoneNumber(phoneNumber: String) {
        val request = JSONObject().apply {
            put("@type", "setAuthenticationPhoneNumber")
            put("phone_number", phoneNumber)
            put("@extra", getNextRequestId())
        }
        send(request.toString())
    }

    fun sendCode(code: String) {
        val request = JSONObject().apply {
            put("@type", "checkAuthenticationCode")
            put("code", code)
            put("@extra", getNextRequestId())
        }
        send(request.toString())
    }

    fun sendPassword(password: String) {
        val request = JSONObject().apply {
            put("@type", "checkAuthenticationPassword")
            put("password", password)
            put("@extra", getNextRequestId())
        }
        send(request.toString())
    }

    fun loadChats(limit: Int = 100) {
        val request = JSONObject().apply {
            put("@type", "loadChats")
            put("chat_list", JSONObject().put("@type", "chatListMain"))
            put("limit", limit)
            put("@extra", getNextRequestId())
        }
        send(request.toString())
    }

    /**
     * Get list of chats (private, groups, channels)
     */
    suspend fun getChats(limit: Int = 100): List<ChatInfo> {
        val request = JSONObject().apply {
            put("@type", "getChats")
            put("chat_list", JSONObject().put("@type", "chatListMain"))
            put("limit", limit)
        }
        val response = sendAsync(request)

        val chatInfos = mutableListOf<ChatInfo>()
        val chatIds = response.getJSONArray("chat_ids")

        for (i in 0 until chatIds.length()) {
            val chatId = chatIds.getLong(i)
            val chatInfo = getChat(chatId)
            if (chatInfo != null) {
                chatInfos.add(chatInfo)
            }
        }
        return chatInfos
    }

    private suspend fun getChat(chatId: Long): ChatInfo? {
        val request = JSONObject().apply {
            put("@type", "getChat")
            put("chat_id", chatId)
        }
        return try {
            val response = sendAsync(request)
            val type = response.getJSONObject("type")
            val chatType = when (type.getString("@type")) {
                "chatTypePrivate" -> ChatType.PRIVATE
                "chatTypeSupergroup" -> if (type.optBoolean("is_channel")) ChatType.CHANNEL else ChatType.GROUP
                "chatTypeBasicGroup" -> ChatType.GROUP
                else -> ChatType.PRIVATE
            }

            // Parse photo
            var photoPath: String? = null
            val photo = response.optJSONObject("photo")
            if (photo != null) {
                val small = photo.getJSONObject("small")
                val local = small.getJSONObject("local")
                val path = local.optString("path", "")
                if (path.isNotEmpty() && File(path).exists()) {
                    photoPath = path
                } else {
                    // Trigger download if not available
                    downloadThumbnail(small.getInt("id"))
                }
            }

            // Parse last message date (for sorting)
            val lastMessage = response.optJSONObject("last_message")
            val lastMessageDate = lastMessage?.optInt("date", 0) ?: 0
            
            ChatInfo(
                id = response.getLong("id"),
                title = response.getString("title"),
                type = chatType,
                photoPath = photoPath,
                lastMessageDate = lastMessageDate
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting chat $chatId", e)
            null
        }
    }

    private fun downloadThumbnail(fileId: Int) {
        val request = JSONObject().apply {
            put("@type", "downloadFile")
            put("file_id", fileId)
            put("priority", 1)
            put("synchronous", false)
        }
        send(request.toString())
    }

    /**
     * Search for media messages in a chat.
     * Searches for both Videos and Documents (filtered for video types) to support MKV, AVI, etc.
     */
    suspend fun searchChatMedia(chatId: Long, limit: Int = 100, sync: Boolean = true): List<MediaItem> {
        return try {
            if (sync) {
                // Step 1: Open chat so TDLib actively subscribes to new messages for this chat.
                // This is required for searchChatMessages to find recently added files.
                try {
                    val openChatReq = JSONObject().apply {
                        put("@type", "openChat")
                        put("chat_id", chatId)
                    }
                    sendAsync(openChatReq)
                    Log.d(TAG, "openChat sent for $chatId")
                } catch (e: Exception) {
                    Log.w(TAG, "openChat failed for $chatId, continuing anyway", e)
                }

                // Step 2: Pull last 100 messages from Telegram servers into local DB.
                // Without this, searchChatMessages only sees stale local data.
                try {
                    val historyReq = JSONObject().apply {
                        put("@type", "getChatHistory")
                        put("chat_id", chatId)
                        put("from_message_id", 0)
                        put("offset", 0)
                        put("limit", 100)
                    }
                    sendAsync(historyReq)
                    Log.d(TAG, "Chat history synced (100 msgs) for $chatId")
                } catch (e: Exception) {
                    Log.w(TAG, "Chat history sync failed for $chatId, proceeding with local search", e)
                }
            }

            val videoDeferred = scope.async(Dispatchers.IO) {
                searchMessagesByType(chatId, limit, "searchMessagesFilterVideo")
            }
            val documentDeferred = scope.async(Dispatchers.IO) {
                searchMessagesByType(chatId, limit, "searchMessagesFilterDocument")
            }

            val videoMessages = videoDeferred.await()
            val documentMessages = documentDeferred.await()

            val mediaItems = mutableListOf<MediaItem>()

            // Process Videos
            for (i in 0 until videoMessages.length()) {
                val message = videoMessages.getJSONObject(i)
                parseVideoMessage(message, chatId)?.let { mediaItems.add(it) }
            }

            // Process Documents (filter for video types)
            for (i in 0 until documentMessages.length()) {
                val message = documentMessages.getJSONObject(i)
                parseDocumentMessage(message, chatId)?.let { mediaItems.add(it) }
            }

            // Deduplicate by file ID and sort by date (newest first)
            val result = mediaItems.distinctBy { it.fileId }
                .sortedByDescending { it.date }

            // Step 3: Close the chat to free TDLib resources (we only needed it for sync)
            try {
                val closeChatReq = JSONObject().apply {
                    put("@type", "closeChat")
                    put("chat_id", chatId)
                }
                sendAsync(closeChatReq)
                Log.d(TAG, "closeChat sent for $chatId")
            } catch (e: Exception) {
                Log.w(TAG, "closeChat failed for $chatId", e)
            }

            result

        } catch (e: Exception) {
            Log.e(TAG, "Error searching media", e)
            emptyList()
        }
    }

    private suspend fun searchMessagesByType(chatId: Long, limit: Int, filterType: String): org.json.JSONArray {
        val request = JSONObject().apply {
            put("@type", "searchChatMessages")
            put("chat_id", chatId)
            put("query", "")
            put("from_message_id", 0)
            put("offset", 0)
            put("limit", limit)
            put("filter", JSONObject().put("@type", filterType))
        }
        val response = sendAsync(request)
        return response.optJSONArray("messages") ?: org.json.JSONArray()
    }

    suspend fun getLatestMedia(limit: Int = 60, activeChats: List<ChatInfo>? = null, forceRefresh: Boolean = false): List<MediaItem> {
        return try {
            // On manual refresh, clear the cache so it reflects fresh server state
            // rather than accumulating stale entries indefinitely
            if (forceRefresh) {
                discoveredMediaCache.clear()
            }

            val mediaItems = mutableListOf<MediaItem>()

            // 1. Direct fetch from active chats histories (bypasses global search indexing lag)
            val chatsToUse = activeChats ?: getChats(40)
            val topChats = chatsToUse.take(40)

            val historyJobs = topChats.map { chat ->
                scope.async(Dispatchers.IO) {
                    try {
                        val historyReq = JSONObject().apply {
                            put("@type", "getChatHistory")
                            put("chat_id", chat.id)
                            put("from_message_id", 0)
                            put("offset", 0)
                            put("limit", 100) // Deep fetch to bypass text spam
                        }
                        val response = sendAsync(historyReq)

                        val messages = response.optJSONArray("messages")
                        val chatMedia = mutableListOf<MediaItem>()
                        if (messages != null) {
                            for (i in 0 until messages.length()) {
                                val message = messages.getJSONObject(i)
                                val content = message.optJSONObject("content")
                                if (content != null) {
                                    val type = content.optString("@type", "")
                                    if (type == "messageVideo") {
                                        parseVideoMessage(message, chat.id)?.let { chatMedia.add(it) }
                                    } else if (type == "messageDocument") {
                                        parseDocumentMessage(message, chat.id)?.let { chatMedia.add(it) }
                                    }
                                }
                            }
                        }
                        chatMedia
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting history for chat ${chat.id}", e)
                        emptyList()
                    }
                }
            }

            // 2. Fallback to global search for older messages
            val videoDeferred = scope.async(Dispatchers.IO) {
                searchGlobalMessagesByType(limit, "searchMessagesFilterVideo")
            }
            val documentDeferred = scope.async(Dispatchers.IO) {
                searchGlobalMessagesByType(limit, "searchMessagesFilterDocument")
            }

            // Await all results
            val historyResults = historyJobs.awaitAll()
            val videoMessages = videoDeferred.await()
            val documentMessages = documentDeferred.await()

            // Merge histories
            for (list in historyResults) {
                mediaItems.addAll(list)
            }

            // Merge global search videos
            for (i in 0 until videoMessages.length()) {
                val message = videoMessages.getJSONObject(i)
                val chatId = message.optLong("chat_id", 0L)
                parseVideoMessage(message, chatId)?.let { mediaItems.add(it) }
            }

            // Merge global search documents
            for (i in 0 until documentMessages.length()) {
                val message = documentMessages.getJSONObject(i)
                val chatId = message.optLong("chat_id", 0L)
                parseDocumentMessage(message, chatId)?.let { mediaItems.add(it) }
            }

            // Update cache with all found items
            for (item in mediaItems) {
                discoveredMediaCache[item.fileId] = item
            }

            // Return sorted cache values, ensuring we never lose temporarily dropped messages
            discoveredMediaCache.values.toList()
                .sortedByDescending { it.date }
                .take(limit)

        } catch (e: Exception) {
            Log.e(TAG, "Error searching global media", e)
            emptyList()
        }
    }

    private suspend fun searchGlobalMessagesByType(limit: Int, filterType: String): org.json.JSONArray {
        val request = JSONObject().apply {
            put("@type", "searchMessages")
            // No chat_list restriction, searches all folders/archives
            put("query", "")
            put("offset_date", 0)
            put("offset_chat_id", 0)
            put("offset_message_id", 0)
            put("limit", limit)
            put("filter", JSONObject().put("@type", filterType))
        }
        val response = sendAsync(request)
        return response.optJSONArray("messages") ?: org.json.JSONArray()
    }

    suspend fun searchMediaGlobally(query: String, limit: Int = 50): List<MediaItem> {
        return try {
            val videoDeferred = scope.async(Dispatchers.IO) {
                searchGlobalMessagesWithQuery(query, limit, "searchMessagesFilterVideo")
            }
            val documentDeferred = scope.async(Dispatchers.IO) {
                searchGlobalMessagesWithQuery(query, limit, "searchMessagesFilterDocument")
            }
            
            val videoMessages = videoDeferred.await()
            val documentMessages = documentDeferred.await()
            
            val mediaItems = mutableListOf<MediaItem>()
            
            for (i in 0 until videoMessages.length()) {
                val message = videoMessages.getJSONObject(i)
                val chatId = message.optLong("chat_id", 0L)
                parseVideoMessage(message, chatId)?.let { mediaItems.add(it) }
            }
            for (i in 0 until documentMessages.length()) {
                val message = documentMessages.getJSONObject(i)
                val chatId = message.optLong("chat_id", 0L)
                parseDocumentMessage(message, chatId)?.let { mediaItems.add(it) }
            }
            
            mediaItems.sortedByDescending { it.date }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching global media with query", e)
            emptyList()
        }
    }

    private suspend fun searchGlobalMessagesWithQuery(query: String, limit: Int, filterType: String): org.json.JSONArray {
        val request = JSONObject().apply {
            put("@type", "searchMessages")
            put("query", query)
            put("offset_date", 0)
            put("offset_chat_id", 0)
            put("offset_message_id", 0)
            put("limit", limit)
            put("filter", JSONObject().put("@type", filterType))
        }
        val response = sendAsync(request)
        return response.optJSONArray("messages") ?: org.json.JSONArray()
    }

    private suspend fun parseVideoMessage(message: JSONObject, chatId: Long): MediaItem? {
        val content = message.getJSONObject("content")
        Log.e(TAG, "Parsed video message content: $content")
        if (content.getString("@type") != "messageVideo") return null

        val video = content.getJSONObject("video")
        val file = video.getJSONObject("video")
        val remoteFile = file.getJSONObject("remote")
        val chatTitle = getChat(chatId)?.title ?: "Unknown"

        // Parse Thumbnail
        var thumbnailPath: String? = null
        val thumbnail = video.optJSONObject("thumbnail")
        if (thumbnail != null) {
            val fileLocal = thumbnail.getJSONObject("file").getJSONObject("local")
            val path = fileLocal.optString("path", "")
            if (path.isNotEmpty() && File(path).exists()) {
                thumbnailPath = path
            } else {
                 downloadThumbnail(thumbnail.getJSONObject("file").getInt("id"))
            }
        }

        return MediaItem(
            id = file.getInt("id").toLong(),
            chatId = chatId,
            messageId = message.getLong("id"),
            fileId = file.getInt("id"),
            remoteFileId = remoteFile.getString("id"),
            title = video.optString("file_name", "Video"),
            fileName = video.optString("file_name", "video.mp4"),
            mimeType = video.optString("mime_type", "video/mp4"),
            duration = video.optInt("duration", 0),
            size = file.getLong("expected_size"),
            thumbnailPath = thumbnailPath,
            chatTitle = chatTitle,
            mediaType = MediaType.VIDEO,
            date = message.optLong("date", 0L)
        )
    }

    private suspend fun parseDocumentMessage(message: JSONObject, chatId: Long): MediaItem? {
        val content = message.getJSONObject("content")
        if (content.getString("@type") != "messageDocument") return null

        val document = content.getJSONObject("document")
        val file = document.getJSONObject("document")
        val fileName = document.optString("file_name", "")
        val mimeType = document.optString("mime_type", "")

        val isVideo = isVideoFile(fileName, mimeType)
        val isSubtitle = isSubtitleFile(fileName)

        // Filter: Must be a video or subtitle file
        if (!isVideo && !isSubtitle) return null

        // Parse Thumbnail
        var thumbnailPath: String? = null
        val thumbnail = document.optJSONObject("thumbnail")
        if (thumbnail != null) {
            val fileLocal = thumbnail.getJSONObject("file").getJSONObject("local")
            val path = fileLocal.optString("path", "")
            if (path.isNotEmpty() && File(path).exists()) {
                thumbnailPath = path
            } else {
                 downloadThumbnail(thumbnail.getJSONObject("file").getInt("id"))
            }
        }

        val remoteFile = file.getJSONObject("remote")
        val chatTitle = getChat(chatId)?.title ?: "Unknown"

        return MediaItem(
            id = file.getInt("id").toLong(),
            chatId = chatId,
            messageId = message.getLong("id"),
            fileId = file.getInt("id"),
            remoteFileId = remoteFile.getString("id"),
            title = fileName.ifEmpty { if (isSubtitle) "Subtitle" else "Video Document" },
            fileName = fileName,
            mimeType = mimeType,
            duration = 0, // Documents don't have duration metadata usually
            size = file.getLong("expected_size"),
            thumbnailPath = thumbnailPath,
            chatTitle = chatTitle,
            mediaType = if (isSubtitle) MediaType.DOCUMENT else MediaType.VIDEO,
            date = message.optLong("date", 0L)
        )
    }

    private fun isVideoFile(fileName: String, mimeType: String): Boolean {
        if (mimeType.startsWith("video/")) return true
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in setOf("mkv", "avi", "mp4", "mov", "flv", "wmv", "m4v", "webm", "3gp")
    }

    private fun isSubtitleFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in setOf("srt", "ass", "vtt", "sub", "ssa", "ttml", "sbv", "scc")
    }

    /**
     * Downloads a file and exports it to the device's public Download directory.
     * Suspends until complete or timeout.
     */
    suspend fun exportFileToDownloads(fileId: Int, fileName: String, onProgress: (Int) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Request download
                val downloadRequest = JSONObject().apply {
                    put("@type", "downloadFile")
                    put("file_id", fileId)
                    put("priority", 32)
                    put("offset", 0)
                    put("limit", 0) // Download whole file
                    put("synchronous", false) 
                }
                send(downloadRequest.toString())

                // Poll for completion
                var localPath = ""
                var isCompleted = false
                var attempts = 0
                while (!isCompleted && attempts < 120) { // 2 minutes timeout for subtitles
                    val getFileReq = JSONObject().apply {
                        put("@type", "getFile")
                        put("file_id", fileId)
                    }
                    val response = sendAsync(getFileReq)
                    val expectedSize = response.optLong("expected_size", 1).coerceAtLeast(1)
                    val local = response.optJSONObject("local")
                    if (local != null) {
                        isCompleted = local.optBoolean("is_downloading_completed", false)
                        val downloadedSize = local.optLong("downloaded_size", 0)
                        val progress = ((downloadedSize.toFloat() / expectedSize.toFloat()) * 100).toInt()
                        
                        withContext(Dispatchers.Main) {
                            onProgress(progress.coerceIn(0, 100))
                        }

                        if (isCompleted) {
                            localPath = local.optString("path", "")
                            break
                        }
                    }
                    delay(1000)
                    attempts++
                }

                if (isCompleted && localPath.isNotEmpty()) {
                    val sourceFile = File(localPath)
                    if (sourceFile.exists()) {
                        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
                        
                        val destFile = File(downloadsDir, fileName)
                        sourceFile.copyTo(destFile, overwrite = true)
                        return@withContext true
                    }
                }
                false
            } catch (e: Exception) {
                Log.e(TAG, "Error exporting file to downloads", e)
                false
            }
        }
    }

    /**
     * Read file part using TdApi.ReadFilePart.
     * Supports 4GB files with Long offset.
     * Includes retry logic for transient failures.
     */
    suspend fun readFilePart(fileId: Int, offset: Long, count: Long): ByteArray? {
        val maxRetries = 15
        var lastException: Exception? = null
        
        repeat(maxRetries) { attempt ->
            try {
                val result = readFilePartInternal(fileId, offset, count)
                if (result != null) return result
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Read file part attempt ${attempt + 1}/$maxRetries failed", e)
                if (attempt < maxRetries - 1) {
                    kotlinx.coroutines.delay(1000L) // Wait 1 second before checking again
                }
            }
        }
        
        Log.e(TAG, "Error reading file part after $maxRetries attempts", lastException)
        return null
    }
    
    /**
     * Read data from a local cache file using RandomAccessFile for efficient seeking.
     * Returns null if the file doesn't exist or doesn't have enough data at the requested offset.
     */
    private fun tryReadLocalFile(localPath: String, offset: Long, chunkSize: Long): ByteArray? {
        val file = File(localPath)
        if (!file.exists() || file.length() <= offset) return null
        
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val available = (file.length() - offset).coerceAtMost(chunkSize)
                val buffer = ByteArray(available.toInt())
                val bytesRead = raf.read(buffer)
                if (bytesRead > 0) {
                    Log.d(TAG, "Fast-path: read $bytesRead bytes from local cache at offset=$offset")
                    if (bytesRead < buffer.size) buffer.copyOf(bytesRead) else buffer
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading local file at offset=$offset", e)
            null
        }
    }

    /**
     * Get (and cache) the local file path for a fileId.
     * Only makes a TDLib call if not already cached.
     */
    suspend fun getLocalFilePath(fileId: Int): String {
        localPathCache[fileId]?.let { cached ->
            if (File(cached).exists()) return cached
        }
        
        val request = JSONObject().apply {
            put("@type", "getFile")
            put("file_id", fileId)
        }
        return try {
            val response = sendAsync(request)
            val path = response.optJSONObject("local")?.optString("path", "") ?: ""
            if (path.isNotEmpty()) localPathCache[fileId] = path
            path
        } catch (e: Exception) {
            Log.w(TAG, "getLocalFilePath failed for $fileId", e)
            ""
        }
    }
    
    private suspend fun readFilePartInternal(fileId: Int, offset: Long, count: Long): ByteArray? {
        val chunkSize = count.coerceAtMost(CHUNK_SIZE)

        // ── FAST PATH: try reading directly from cached local file (NO TDLib calls) ──
        val cachedPath = localPathCache[fileId]
        if (cachedPath != null && getFileIsCompleted(fileId)) {
            val data = tryReadLocalFile(cachedPath, offset, chunkSize)
            if (data != null) return data
        }

        // ── SLOW PATH: file not in local cache, need to download from Telegram ──
        Log.d(TAG, "Cache miss for fileId=$fileId offset=$offset, downloading chunk...")

        // Request download of this chunk (synchronous = true blocks until data is ready)
        val downloadRequest = JSONObject().apply {
            put("@type", "downloadFile")
            put("file_id", fileId)
            put("priority", 32)
            put("offset", offset)
            put("limit", chunkSize)
            put("synchronous", true)
        }

        val downloadResponse = sendAsync(downloadRequest)
        val localObj = downloadResponse.optJSONObject("local")
        val localPath = localObj?.optString("path", "") ?: ""
        val isCompleted = localObj?.optBoolean("is_downloading_completed", false) ?: false
        val downloadedPrefixSize = localObj?.optLong("downloaded_prefix_size", 0L) ?: 0L
        val downloadedSize = localObj?.optLong("downloaded_size", 0L) ?: 0L

        Log.d(TAG, "downloadFile response: fileId=$fileId, offset=$offset, " +
              "path=$localPath, completed=$isCompleted, " +
              "prefixSize=$downloadedPrefixSize, downloadedSize=$downloadedSize")

        // Cache the path for future fast-path reads
        if (localPath.isNotEmpty()) {
            localPathCache[fileId] = localPath

            // Try reading from the local cache file after download if fully completed
            if (isCompleted) {
                val data = tryReadLocalFile(localPath, offset, chunkSize)
                if (data != null) return data
            }
        }

        // Fallback — use TDLib's readFilePart API (returns data as base64)
        Log.d(TAG, "Falling back to readFilePart API for fileId=$fileId offset=$offset")
        val readRequest = JSONObject().apply {
            put("@type", "readFilePart")
            put("file_id", fileId)
            put("offset", offset)
            put("count", chunkSize)
        }

        val response = sendAsync(readRequest)
        val dataBase64 = response.optString("data", "")
        if (dataBase64.isNotEmpty()) {
            val decoded = android.util.Base64.decode(dataBase64, android.util.Base64.DEFAULT)
            Log.d(TAG, "readFilePart returned ${decoded.size} bytes")
            return decoded
        }

        Log.e(TAG, "Both local file and readFilePart failed for fileId=$fileId offset=$offset")
        return null
    }

    /**
     * Resolve a remote file ID string to a TDLib local file ID.
     * Used as a fallback when a newly discovered file's fileId isn't yet registered
     * in TDLib's local database (e.g. a freshly sent message on another device).
     */
    suspend fun getFileByRemoteId(remoteFileId: String): Int? {
        val request = JSONObject().apply {
            put("@type", "getRemoteFile")
            put("remote_file_id", remoteFileId)
            put("file_type", JSONObject().put("@type", "fileTypeVideo"))
        }
        return try {
            val response = sendAsync(request)
            val resolvedId = response.optInt("id", -1)
            if (resolvedId > 0) {
                Log.d(TAG, "getRemoteFile resolved $remoteFileId -> fileId=$resolvedId")
                resolvedId
            } else {
                Log.w(TAG, "getRemoteFile returned invalid id for $remoteFileId")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getRemoteFile failed for $remoteFileId", e)
            null
        }
    }

    /**
     * Check if a file is fully downloaded.
     */
    suspend fun isFileCompleted(fileId: Int): Boolean {
        return getFileIsCompleted(fileId)
    }

    private suspend fun getFileIsCompleted(fileId: Int): Boolean {
        val request = JSONObject().apply {
            put("@type", "getFile")
            put("file_id", fileId)
        }
        return try {
            val response = sendAsync(request)
            response.optJSONObject("local")?.optBoolean("is_downloading_completed", false) ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get file size using Long for 4GB support.
     * Falls back to resolving via remoteFileId if expected_size is 0
     * (common for files received while the app was not running).
     */
    suspend fun getFileSize(fileId: Int): Long {
        val request = JSONObject().apply {
            put("@type", "getFile")
            put("file_id", fileId)
        }
        return try {
            val response = sendAsync(request)
            val size = response.optLong("expected_size", 0L)
            if (size > 0L) size else {
                // expected_size can be 0 for files TDLib hasn't fully resolved yet.
                // Try the remote object which carries the authoritative size.
                val remote = response.optJSONObject("remote")
                val remoteId = remote?.optString("id", "") ?: ""
                if (remoteId.isNotEmpty()) {
                    getFileSizeByRemoteId(remoteId)
                } else 0L
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file size for $fileId", e)
            0L
        }
    }

    /**
     * Resolve file size by remote ID — used when local expected_size is 0.
     */
    private suspend fun getFileSizeByRemoteId(remoteFileId: String): Long {
        return try {
            val request = JSONObject().apply {
                put("@type", "getRemoteFile")
                put("remote_file_id", remoteFileId)
                put("file_type", JSONObject().put("@type", "fileTypeVideo"))
            }
            val response = sendAsync(request)
            response.optLong("expected_size", 0L)
        } catch (e: Exception) {
            Log.w(TAG, "getFileSizeByRemoteId failed for $remoteFileId", e)
            0L
        }
    }

    /**
     * Pre-resolves a file for streaming by ensuring TDLib knows the remote file.
     * Call this before registering a file with the proxy when the file was
     * received while the app was not running (fileId may be stale).
     * Returns the best available fileId (resolved or original).
     */
    suspend fun prepareFileForStreaming(fileId: Int, remoteFileId: String): Int {
        return try {
            // Fast path: if the file already has a valid size, it's known to TDLib — skip resolution
            val existingSize = getFileSize(fileId)
            if (existingSize > 0L) {
                // Also warm the local path cache while we're at it
                getLocalFilePath(fileId)
                return fileId
            }

            // Size is 0 — file not yet resolved. Force TDLib to sync via remote ID.
            Log.w(TAG, "fileId=$fileId has size=0, resolving via remoteFileId")
            val resolvedId = getFileByRemoteId(remoteFileId)
            if (resolvedId != null) {
                getLocalFilePath(resolvedId) // warm cache
            }
            resolvedId ?: fileId
        } catch (e: Exception) {
            Log.e(TAG, "prepareFileForStreaming failed for fileId=$fileId", e)
            fileId
        }
    }

    private fun handleFileUpdate(json: JSONObject) {
        // Handle file download progress updates
        val file = json.optJSONObject("file") ?: return
        val fileId = file.getInt("id")
        val local = file.optJSONObject("local")
        if (local != null) {
            val isDownloadingCompleted = local.optBoolean("is_downloading_completed", false)
            val downloadedSize = local.optLong("downloaded_size", 0)
            Log.d(TAG, "File $fileId: downloaded=$downloadedSize, completed=$isDownloadingCompleted")
        }
        // Rate-limited storage optimization:
        // updateFile fires on every streamed chunk, so we guard against calling
        // optimizeStorage() too often — which would delete chunks mid-playback.
        // Startup call in authorizationStateReady is still unconditional.
        val now = System.currentTimeMillis()
        if (now - lastOptimizeTime > OPTIMIZE_INTERVAL_MS) {
            lastOptimizeTime = now
            optimizeStorage()
        }
    }

    private suspend fun sendAsync(request: JSONObject): JSONObject {
        val id = getNextRequestId()
        request.put("@extra", id)

        val deferred = CompletableDeferred<JSONObject>()
        pendingRequests[id] = deferred

        try {
            send(request.toString())
            return withTimeout(120000) { deferred.await() } // 120 seconds for large file operations
        } finally {
            pendingRequests.remove(id)
        }
    }

    private fun send(request: String) {
        JsonClient.td_send(clientId, request)
    }

    private fun getNextRequestId(): String {
        return "req_${requestId.getAndIncrement()}"
    }

    /**
     * Delete a specific file from TDLib cache.
     * Call this after video playback completes to free up space.
     */
    fun deleteFile(fileId: Int) {
        scope.launch {
            try {
                val request = JSONObject().apply {
                    put("@type", "deleteFile")
                    put("file_id", fileId)
                }
                sendAsync(request)
                Log.d(TAG, "Deleted file: $fileId")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting file $fileId", e)
            }
        }
    }

    /**
     * Optimize storage by deleting old/unused files.
     * Keeps cache under MAX_CACHE_SIZE and removes files older than MAX_FILE_AGE_SECONDS.
     * Preserves thumbnails, profile photos, stickers, and wallpapers.
     */
    fun optimizeStorage() {
        scope.launch {
            try {
                Log.d(TAG, "Running storage optimization...")
                val request = JSONObject().apply {
                    put("@type", "optimizeStorage")
                    put("size", MAX_CACHE_SIZE)
                    put("ttl", MAX_FILE_AGE_SECONDS)
                    put("count", -1) // No file count limit
                    put("immunity_delay", 30) // 30s immunity — just enough for active chunk, not whole movie
                    // File types to delete (videos, documents, audio, etc.)
                    // Exclude thumbnails (1), profile photos (2), stickers (5), wallpapers (8)
                    put("file_types", org.json.JSONArray().apply {
                        put(JSONObject().put("@type", "fileTypeVideo"))
                        put(JSONObject().put("@type", "fileTypeDocument"))
                        put(JSONObject().put("@type", "fileTypeAudio"))
                        put(JSONObject().put("@type", "fileTypeVideoNote"))
                        put(JSONObject().put("@type", "fileTypeVoiceNote"))
                    })
                    put("chat_ids", org.json.JSONArray()) // All chats
                    put("exclude_chat_ids", org.json.JSONArray()) // No exclusions, allow mid-stream segmented deletion
                    put("return_deleted_file_statistics", false)
                    put("chat_limit", 0)
                }
                val response = sendAsync(request)
                val freedSize = response.optLong("size", 0)
                val freedCount = response.optInt("count", 0)
                Log.d(TAG, "Storage optimized: freed ${freedSize / 1024 / 1024}MB, $freedCount files")
            } catch (e: Exception) {
                Log.e(TAG, "Error optimizing storage", e)
            }
        }
    }

    /**
     * Aggressive cleanup after playback ends.
     * Runs optimizeStorage with 0 immunity delay to reclaim all unused cache immediately.
     * Also cancels any pending file downloads that may be lingering.
     */
    fun forceCleanup() {
        scope.launch {
            try {
                Log.d(TAG, "Running FORCE cleanup after playback...")
                // Run optimizeStorage with ZERO immunity — delete everything beyond cache limit now
                val request = JSONObject().apply {
                    put("@type", "optimizeStorage")
                    put("size", MAX_CACHE_SIZE)
                    put("ttl", 60) // Files older than 1 minute can be deleted
                    put("count", -1)
                    put("immunity_delay", 0) // No immunity — we're done playing
                    put("file_types", org.json.JSONArray().apply {
                        put(JSONObject().put("@type", "fileTypeVideo"))
                        put(JSONObject().put("@type", "fileTypeDocument"))
                        put(JSONObject().put("@type", "fileTypeAudio"))
                        put(JSONObject().put("@type", "fileTypeVideoNote"))
                        put(JSONObject().put("@type", "fileTypeVoiceNote"))
                    })
                    put("chat_ids", org.json.JSONArray())
                    put("exclude_chat_ids", org.json.JSONArray())
                    put("return_deleted_file_statistics", false)
                    put("chat_limit", 0)
                }
                val response = sendAsync(request)
                val freedSize = response.optLong("size", 0)
                val freedCount = response.optInt("count", 0)
                Log.d(TAG, "FORCE cleanup: freed ${freedSize / 1024 / 1024}MB, $freedCount files")
                // Clear local path cache since files may have been deleted
                localPathCache.clear()
            } catch (e: Exception) {
                Log.e(TAG, "Error in force cleanup", e)
            }
        }
    }

    /**
     * Log out from Telegram. Waits up to 10 seconds for the server to
     * acknowledge session termination so the device disappears from
     * the "Active sessions" list in the Telegram phone app.
     */
    suspend fun logOut() {
        try {
            Log.d(TAG, "Logging out of TDLib...")
            isLoggingOut = true
            val request = JSONObject().apply {
                put("@type", "logOut")
            }
            send(request.toString())

            // Wait for authorizationStateClosed which confirms server-side session kill
            withTimeoutOrNull(10_000) {
                _logoutComplete.first()
            } ?: Log.w(TAG, "Logout timed out after 10s — session may still be visible")

            Log.d(TAG, "Logout complete. Server session terminated.")
        } catch (e: Exception) {
            Log.e(TAG, "Error logging out", e)
        }
    }

    fun close() {
        isShuttingDown = true
        scope.launch {
            try {
                Log.d(TAG, "Closing TDLib client gracefully")
                
                // Trigger a database optimization before close as a safety measure for compaction
                val optimizeRequest = JSONObject().apply {
                    put("@type", "optimizeStorage")
                    put("size", MAX_CACHE_SIZE)
                    put("ttl", MAX_FILE_AGE_SECONDS)
                    put("count", -1)
                    put("immunity_delay", 0)
                    put("chat_ids", org.json.JSONArray())
                    put("exclude_chat_ids", org.json.JSONArray())
                    put("return_deleted_file_statistics", false)
                    put("chat_limit", 0)
                }
                send(optimizeRequest.toString())

                val request = JSONObject().apply {
                    put("@type", "close")
                }
                send(request.toString())

                // Gracefully wait for pending updates to finish writing to disk and binlog compaction
                val timeoutMs = 5000L
                val startTime = System.currentTimeMillis()
                while (_authState.value != AuthState.Closed && System.currentTimeMillis() - startTime < timeoutMs) {
                    delay(100)
                }
                Log.d(TAG, "TDLib closed gracefully. State: ${_authState.value}")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing TDLib client", e)
            }
        }
    }

    /**
     * Fast synchronous close for shutdown broadcast.
     * Blocks the calling thread for up to 2 seconds to give TDLib time
     * to send the close packet to Telegram servers. This prevents ghost
     * sessions that cause 3-5 min reconnection delays after TV reboot.
     *
     * Called from ShutdownReceiver.onReceive() on the main thread —
     * intentionally blocking because the OS will kill us shortly after.
     */
    fun closeGracefully() {
        try {
            Log.d(TAG, "closeGracefully: sending close to TDLib...")
            isShuttingDown = true
            connectionWatchdogJob?.cancel()
            val request = JSONObject().apply {
                put("@type", "close")
            }
            send(request.toString())
            // Block briefly to give TDLib time to send the close packet
            // to the server before the OS kills our process.
            Thread.sleep(2000)
            Log.d(TAG, "closeGracefully: done.")
        } catch (e: Exception) {
            Log.e(TAG, "Error in closeGracefully", e)
        }
    }

    /**
     * Set TDLib online/offline flag. When set to false, Telegram servers
     * know this client is going away, reducing ghost session risk.
     * Called from ShutdownReceiver on ACTION_SCREEN_OFF.
     */
    fun setOnline(online: Boolean) {
        try {
            val request = JSONObject().apply {
                put("@type", "setOption")
                put("name", "online")
                put("value", JSONObject().apply {
                    put("@type", "optionValueBoolean")
                    put("value", online)
                })
            }
            send(request.toString())
            Log.d(TAG, "setOnline($online)")
        } catch (e: Exception) {
            Log.e(TAG, "Error in setOnline($online)", e)
        }
    }

    // ── Connection State Watchdog ──────────────────────────────────────

    private fun handleConnectionState(json: JSONObject) {
        val state = json.optJSONObject("state") ?: return
        val stateType = state.optString("@type", "")
        Log.d(TAG, "Connection state: $stateType")
        _connectionState.value = stateType
    }

    /**
     * Monitors TDLib connection state after authorization.
     * If stuck in connectionStateConnecting or connectionStateUpdating
     * (typically caused by a ghost session from a previous unclean shutdown),
     * forces a TCP reconnect via setNetworkType toggle with exponential backoff.
     *
     * Uses polling (every 2s) instead of StateFlow.collect because collect
     * only fires on state *changes* — if TDLib stays stuck in the same state,
     * collect would never re-fire to check elapsed time.
     */
    private fun startConnectionWatchdog() {
        connectionWatchdogJob?.cancel()
        connectionWatchdogJob = scope.launch {
            var backoffMs = 5_000L       // Start at 5 seconds
            val maxBackoffMs = 60_000L   // Cap at 60 seconds
            var stuckSince = 0L

            while (isActive) {
                delay(2_000) // Check every 2 seconds
                val state = _connectionState.value

                when (state) {
                    "connectionStateReady" -> {
                        // Connection is healthy — reset backoff
                        if (backoffMs > 5_000L) {
                            Log.d(TAG, "Watchdog: connection recovered. Resetting backoff.")
                        }
                        backoffMs = 5_000L
                        stuckSince = 0L
                    }
                    "connectionStateConnecting", "connectionStateUpdating" -> {
                        if (stuckSince == 0L) stuckSince = System.currentTimeMillis()
                        val elapsed = System.currentTimeMillis() - stuckSince

                        if (elapsed > backoffMs) {
                            Log.w(TAG, "Watchdog: stuck in $state for ${elapsed}ms. " +
                                  "Forcing TCP reconnect (backoff=${backoffMs}ms)")
                            forceReconnect()
                            backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
                            stuckSince = System.currentTimeMillis()
                        }
                    }
                }
            }
        }
    }

    /**
     * Forces TDLib to tear down and re-establish TCP connections by toggling
     * the network type to None and back. This is the TDLib-native way to
     * break free from a ghost session deadlock without destroying the client.
     */
    private fun forceReconnect() {
        try {
            // Tell TDLib the network is gone
            val disconnectRequest = JSONObject().apply {
                put("@type", "setNetworkType")
                put("type", JSONObject().put("@type", "networkTypeNone"))
            }
            send(disconnectRequest.toString())

            // After a brief pause, tell TDLib the network is back
            scope.launch {
                delay(500)
                val reconnectRequest = JSONObject().apply {
                    put("@type", "setNetworkType")
                    put("type", JSONObject().put("@type", "networkTypeOther"))
                }
                send(reconnectRequest.toString())
                Log.d(TAG, "forceReconnect: network toggled None → Other")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in forceReconnect", e)
        }
    }
}

sealed class AuthState {
    object Initial : AuthState()
    object WaitingForParameters : AuthState()
    object WaitingForEncryptionKey : AuthState()
    object WaitingForPhoneNumber : AuthState()
    object WaitingForQrScan : AuthState()
    object WaitingForCode : AuthState()
    object WaitingForPassword : AuthState()
    object Authorized : AuthState()
    object Closed : AuthState()
}
