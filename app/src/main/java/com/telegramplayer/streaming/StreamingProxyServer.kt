package com.telegramplayer.streaming

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.telegramplayer.TelegramPlayerApp
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

/**
 * Local HTTP streaming proxy server using NanoHTTPD.
 * Handles Range Requests for seeking in MX Player.
 * Uses Long offsets to support files up to 4GB.
 */
class StreamingProxyServer(port: Int = 0) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "StreamingProxy"
        private const val CHUNK_SIZE = 2L * 1024 * 1024 // 2MB chunks for lower memory pressure
        private const val CLEANUP_DELAY_MS = 60 * 60 * 1000L // 1 hour after last access

        @Volatile
        private var instance: StreamingProxyServer? = null

        fun getInstance(): StreamingProxyServer {
            return instance ?: synchronized(this) {
                instance ?: StreamingProxyServer().also { instance = it }
            }
        }
    }

    // Cache file sizes to avoid repeated lookups
    private val fileSizeCache = ConcurrentHashMap<Int, Long>()
    private val fileNameCache = ConcurrentHashMap<Int, String>()
    // Cache remote file IDs for resolving newly discovered files
    private val remoteFileIdCache = ConcurrentHashMap<Int, String>()
    // Cache local file paths for zero-overhead chunk reads
    private val localPathCache = ConcurrentHashMap<Int, String>()
    // Cache completion status to safely use local fast-path
    private val isCompletedCache = ConcurrentHashMap<Int, Boolean>()
    
    // Track last access time and cleanup runnables for each file
    private val lastAccessTime = ConcurrentHashMap<Int, Long>()
    private val cleanupRunnables = ConcurrentHashMap<Int, Runnable>()
    private val handler = Handler(Looper.getMainLooper())

    fun registerFile(fileId: Int, fileName: String, fileSize: Long, remoteFileId: String = "") {
        fileSizeCache[fileId] = fileSize
        fileNameCache[fileId] = fileName
        if (remoteFileId.isNotEmpty()) {
            remoteFileIdCache[fileId] = remoteFileId
        }
    }

    init {
        // Use a fixed thread pool to limit concurrent connections and reduce memory pressure
        this.setAsyncRunner(object : AsyncRunner {
            private val executor: ExecutorService = Executors.newFixedThreadPool(16)
            private val handlers = java.util.Collections.synchronizedList(mutableListOf<ClientHandler>())

            override fun closeAll() {
                executor.shutdownNow()
                handlers.toList().forEach { it.close() }
                handlers.clear()
            }

            override fun closed(clientHandler: ClientHandler) {
                handlers.remove(clientHandler)
            }

            override fun exec(clientHandler: ClientHandler) {
                handlers.add(clientHandler)
                executor.submit(clientHandler)
            }
        })
    }

    fun getStreamUrl(fileId: Int): String {
        if (!this.isAlive) {
            try {
                this.start(30000, false) // 30 seconds timeout to allow slow networks to establish TDLib connection
                Log.e(TAG, "Auto-started proxy server on port $listeningPort")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-start proxy server", e)
            }
        }
        return "http://127.0.0.1:$listeningPort/stream/$fileId/video.mp4"
    }
    
    /**
     * Schedule cleanup for a file after CLEANUP_DELAY_MS of inactivity.
     * Cancels any existing cleanup runnable and schedules a new one.
     */
    private fun scheduleCleanup(fileId: Int) {
        // Cancel existing cleanup
        cleanupRunnables[fileId]?.let { handler.removeCallbacks(it) }
        
        // Schedule new cleanup
        val runnable = Runnable {
            val lastAccess = lastAccessTime[fileId] ?: 0L
            val elapsed = System.currentTimeMillis() - lastAccess
            
            if (elapsed >= CLEANUP_DELAY_MS) {
                Log.e(TAG, "Cleaning up cached file: $fileId (inactive for ${elapsed/1000}s)")
                
                // Clear local caches
                fileSizeCache.remove(fileId)
                fileNameCache.remove(fileId)
                remoteFileIdCache.remove(fileId)
                localPathCache.remove(fileId)
                isCompletedCache.remove(fileId)
                lastAccessTime.remove(fileId)
                cleanupRunnables.remove(fileId)
            }
        }
        
        cleanupRunnables[fileId] = runnable
        handler.postDelayed(runnable, CLEANUP_DELAY_MS)
    }
    
    /**
     * Mark a file as accessed, refreshing the cleanup timer.
     */
    private fun markAccessed(fileId: Int) {
        lastAccessTime[fileId] = System.currentTimeMillis()
        scheduleCleanup(fileId)
    }
    
    /**
     * Clear all in-memory caches. Called after playback ends to ensure
     * TDLib's optimizeStorage can reclaim disk space without stale references.
     */
    fun clearAllCaches() {
        Log.e(TAG, "Clearing all proxy caches (${fileSizeCache.size} files tracked)")
        // Cancel all pending cleanup runnables
        cleanupRunnables.values.forEach { handler.removeCallbacks(it) }
        
        fileSizeCache.clear()
        fileNameCache.clear()
        remoteFileIdCache.clear()
        localPathCache.clear()
        isCompletedCache.clear()
        lastAccessTime.clear()
        cleanupRunnables.clear()
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.e(TAG, "Request: ${session.method} $uri")

        if (!uri.startsWith("/stream/")) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }

        val fileIdStr = uri.removePrefix("/stream/").split("/").firstOrNull()
        val fileId = fileIdStr?.toIntOrNull()

        if (fileId == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid file ID")
        }

        // Get file size — may be 0 for newly received files not yet resolved by TDLib
        var resolvedFileId = fileId
        var fileSize = fileSizeCache[fileId] ?: runBlocking {
            TelegramPlayerApp.getTdClient().getFileSize(fileId).also {
                if (it > 0) fileSizeCache[fileId] = it
            }
        }

        // Size still 0? Attempt resolution via remoteFileId (file received while app was closed)
        if (fileSize <= 0 && remoteFileIdCache.containsKey(fileId)) {
            val remoteId = remoteFileIdCache[fileId]!!
            Log.e(TAG, "fileId=$fileId size=0, resolving via remoteFileId in serve()")
            val newId = runBlocking { TelegramPlayerApp.getTdClient().getFileByRemoteId(remoteId) }
            if (newId != null) {
                resolvedFileId = newId
                fileSize = runBlocking {
                    TelegramPlayerApp.getTdClient().getFileSize(newId).also {
                        if (it > 0) {
                            fileSizeCache[newId] = it
                            fileNameCache[newId] = fileNameCache[fileId] ?: "video.mp4"
                            remoteFileIdCache[newId] = remoteId
                        }
                    }
                }
            }
        }

        if (fileSize <= 0) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found or size unknown")
        }

        // Check completion status for safe fast-path reading
        val isCompleted = isCompletedCache[resolvedFileId] ?: runBlocking {
            TelegramPlayerApp.getTdClient().isFileCompleted(resolvedFileId).also {
                if (it) isCompletedCache[resolvedFileId] = true
            }
        }
        
        // Track file access for cleanup scheduling
        markAccessed(resolvedFileId)

        val fileName = fileNameCache[resolvedFileId] ?: fileNameCache[fileId] ?: "video.mp4"
        val mimeType = getMimeType(fileName)

        // Parse Range header
        val rangeHeader = session.headers["range"]
        Log.e(TAG, "Range header: $rangeHeader, File size: $fileSize")

        return if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            handleRangeRequest(resolvedFileId, fileSize, rangeHeader, mimeType)
        } else {
            handleFullRequest(resolvedFileId, fileSize, mimeType)
        }
    }

    private fun handleRangeRequest(
        fileId: Int,
        fileSize: Long,
        rangeHeader: String,
        mimeType: String
    ): Response {
        val rangeValue = rangeHeader.removePrefix("bytes=")
        val parts = rangeValue.split("-")

        val startByte = parts[0].toLongOrNull() ?: 0L
        val endByte = if (parts.size > 1 && parts[1].isNotEmpty()) {
            parts[1].toLongOrNull() ?: (fileSize - 1)
        } else {
            fileSize - 1
        }

        if (startByte >= fileSize || endByte >= fileSize || startByte > endByte) {
            val response = newFixedLengthResponse(
                Response.Status.RANGE_NOT_SATISFIABLE,
                "text/plain",
                "Range not satisfiable"
            )
            response.addHeader("Content-Range", "bytes */$fileSize")
            return response
        }

        val contentLength = endByte - startByte + 1
        Log.e(TAG, "Serving range: $startByte-$endByte/$fileSize (${contentLength} bytes)")

        val inputStream = TdLibInputStream(fileId, startByte, endByte)
        
        val response = newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT,
            mimeType,
            inputStream,
            contentLength
        )

        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Range", "bytes $startByte-$endByte/$fileSize")

        return response
    }

    private fun handleFullRequest(fileId: Int, fileSize: Long, mimeType: String): Response {
        Log.e(TAG, "Serving full file: fileId=$fileId, size=$fileSize")

        val inputStream = TdLibInputStream(fileId, 0, fileSize - 1)
        
        val response = newFixedLengthResponse(
            Response.Status.OK,
            mimeType,
            inputStream,
            fileSize
        )

        response.addHeader("Accept-Ranges", "bytes")

        return response
    }

    private inner class TdLibInputStream(
        private val fileId: Int,
        private val startOffset: Long,
        private val endOffset: Long
    ) : InputStream() {
        private var currentOffset = startOffset
        private var buffer: ByteArray? = null
        private var bufferPos = 0

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) == 1) b[0].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (currentOffset > endOffset) return -1
            
            if (buffer == null || bufferPos >= buffer!!.size) {
                // First chunk is smaller (256KB) to start playback instantly, subsequent chunks are 2MB
                val isFirstChunk = (currentOffset == startOffset)
                val chunkSize = if (isFirstChunk) 256L * 1024 else CHUNK_SIZE
                
                val remaining = endOffset - currentOffset + 1
                val toRead = chunkSize.coerceAtMost(remaining)
                if (toRead <= 0) return -1

                buffer = runBlocking {
                    var localData: ByteArray? = null
                    val localPath = localPathCache[fileId]
                    val isCompleted = isCompletedCache[fileId] == true
                    if (localPath != null && isCompleted) {
                        localData = tryReadLocalFile(localPath, currentOffset, toRead)
                    }

                    if (localData != null) {
                        localData
                    } else {
                        var data = TelegramPlayerApp.getTdClient().readFilePart(fileId, currentOffset, toRead)
                        
                        if (data != null) {
                            val path = TelegramPlayerApp.getTdClient().getLocalFilePath(fileId)
                            if (path.isNotEmpty()) localPathCache[fileId] = path
                        } else if (remoteFileIdCache.containsKey(fileId)) {
                            val remoteId = remoteFileIdCache[fileId]!!
                            val resolvedId = TelegramPlayerApp.getTdClient().getFileByRemoteId(remoteId)
                            if (resolvedId != null && resolvedId != fileId) {
                                data = TelegramPlayerApp.getTdClient().readFilePart(resolvedId, currentOffset, toRead)
                            }
                        }
                        data
                    }
                }
                bufferPos = 0
                if (buffer == null || buffer!!.isEmpty()) return -1
            }
            
            val available = buffer!!.size - bufferPos
            val toCopy = minOf(len, available)
            System.arraycopy(buffer!!, bufferPos, b, off, toCopy)
            bufferPos += toCopy
            currentOffset += toCopy
            return toCopy
        }
    }

    /**
     * Read data directly from a local cache file using RandomAccessFile.
     * Returns null if the file doesn't exist or doesn't have data at the requested offset.
     */
    private fun tryReadLocalFile(localPath: String, offset: Long, count: Long): ByteArray? {
        val file = File(localPath)
        if (!file.exists() || file.length() <= offset) return null
        
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val available = (file.length() - offset).coerceAtMost(count)
                val buffer = ByteArray(available.toInt())
                val bytesRead = raf.read(buffer)
                if (bytesRead > 0) {
                    Log.d(TAG, "Proxy fast-path: read $bytesRead bytes at offset=$offset from local cache")
                    if (bytesRead < buffer.size) buffer.copyOf(bytesRead) else buffer
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading local file at offset=$offset", e)
            null
        }
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "m4v" -> "video/x-m4v"
            "3gp" -> "video/3gpp"
            "ts" -> "video/mp2t"
            "flv" -> "video/x-flv"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }
}
