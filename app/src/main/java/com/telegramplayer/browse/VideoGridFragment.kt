package com.telegramplayer.browse

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import com.telegramplayer.R
import com.telegramplayer.TelegramPlayerApp
import com.telegramplayer.data.MediaItem
import com.telegramplayer.streaming.StreamingProxyServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragment that displays a vertical grid of video cards for a specific chat.
 * Videos are sorted by date (newest first).
 */
class VideoGridFragment : VerticalGridSupportFragment() {

    companion object {
        private const val TAG = "VideoGridFragment"
        private const val NUM_COLUMNS = 4
        private const val MX_PLAYER_PACKAGE = "com.mxtech.videoplayer.ad"
        private const val MX_PLAYER_PRO_PACKAGE = "com.mxtech.videoplayer.pro"
    }

    private var chatId: Long = 0L
    private val tdClient get() = TelegramPlayerApp.getTdClient()
    private lateinit var gridAdapter: ArrayObjectAdapter
    private val proxyServer = StreamingProxyServer.getInstance()

    private var pendingDownloadItem: MediaItem? = null

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            pendingDownloadItem?.let { downloadSubtitle(it) }
        } else {
            Toast.makeText(context, "Storage permission required to download", Toast.LENGTH_SHORT).show()
        }
    }

    private val manageStorageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            pendingDownloadItem?.let { downloadSubtitle(it) }
        } else {
            Toast.makeText(context, "All files access required to download", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        chatId = arguments?.getLong(VideoGridActivity.EXTRA_CHAT_ID) ?: 0L
        val title = arguments?.getString(VideoGridActivity.EXTRA_CHAT_TITLE) ?: "Videos"
        
        setupUI(title)
        loadVideos()
    }

    override fun onResume() {
        super.onResume()
        // When user comes back from external player, aggressively clean up cached video data
        // This prevents a full movie (1-2GB) from lingering in TDLib's cache
        Log.d(TAG, "onResume: triggering post-playback cache cleanup")
        tdClient.forceCleanup()
        proxyServer.clearAllCaches()
    }

    private fun setupUI(titleString: String) {
        title = titleString
        
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        
        // Reverted to 5 columns on 1080p as per user request
        // 1920 / 5 = 384px per column slot
        
        val minColumnWidth = 380
        val columns = (width / minColumnWidth).coerceAtLeast(4)
        
        // Calculate exact card width to fit perfectly
        // We want to fill the screen as much as possible to reduce side margins
        val usableWidth = width - (80 * displayMetrics.density) 
        val cardWidth = ((usableWidth / columns) / 1.15).toInt()
        val cardHeight = (cardWidth * 3 / 2) // 2:3 aspect ratio (Netflix Vertical Poster)
        
        gridAdapter = ArrayObjectAdapter(MediaCardPresenter(cardWidth, cardHeight) { mediaItem ->
            handleLongClick(mediaItem)
        })
        
        val gridPresenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_NONE, false).apply {
            numberOfColumns = columns
            shadowEnabled = false // We handle focus effects in CardPresenter
        }
        
        setGridPresenter(gridPresenter)
        adapter = gridAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is MediaItem) {
                if (item.mediaType == com.telegramplayer.data.MediaType.DOCUMENT) {
                    Toast.makeText(context, "Long press 'Select' to download this file", Toast.LENGTH_SHORT).show()
                } else {
                    playWithSelectedPlayer(item)
                }
            }
        }
    }

    private fun loadVideos() {
        lifecycleScope.launch {
            try {
                // Pass 1: Instant load from local DB (no sync)
                val localItems = withContext(Dispatchers.IO) {
                    tdClient.searchChatMedia(chatId, 100, sync = false)
                }
                
                val sortedLocalItems = localItems.sortedByDescending { it.date }
                gridAdapter.clear()
                sortedLocalItems.forEach { gridAdapter.add(it) }
                
                if (sortedLocalItems.isEmpty()) {
                    Toast.makeText(context, "Checking for new videos...", Toast.LENGTH_SHORT).show()
                }

                // Pass 2: Sync from server
                val syncedItems = withContext(Dispatchers.IO) {
                    tdClient.searchChatMedia(chatId, 100, sync = true)
                }
                
                val sortedSyncedItems = syncedItems.sortedByDescending { it.date }

                // Only update UI if there are new items to avoid flicker
                val needsUpdate = sortedSyncedItems.size != sortedLocalItems.size || 
                    sortedSyncedItems.firstOrNull()?.messageId != sortedLocalItems.firstOrNull()?.messageId

                if (needsUpdate) {
                    gridAdapter.clear()
                    sortedSyncedItems.forEach { gridAdapter.add(it) }
                    
                    if (sortedLocalItems.isNotEmpty()) {
                        Toast.makeText(context, "Grid updated with new videos", Toast.LENGTH_SHORT).show()
                    }
                }

                if (sortedSyncedItems.isEmpty() && sortedLocalItems.isEmpty()) {
                    Toast.makeText(context, "No videos found", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading videos", e)
                if (isAdded) {
                    context?.let { Toast.makeText(it, "Error loading videos", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun playWithSelectedPlayer(mediaItem: MediaItem) {
        lifecycleScope.launch {
            try {
                // Force resolve via remote ID to wake up TDLib for this file
                val resolvedFileId = withContext(Dispatchers.IO) {
                    tdClient.prepareFileForStreaming(mediaItem.fileId, mediaItem.remoteFileId)
                }

                // ALWAYS fetch the latest size from TDLib after resolution
                var confirmedSize = withContext(Dispatchers.IO) { 
                    tdClient.getFileSize(resolvedFileId) 
                }
                
                // Fallback to the size from the message if TDLib returns 0
                if (confirmedSize <= 0L && mediaItem.size > 0L) {
                    confirmedSize = mediaItem.size
                }

                proxyServer.registerFile(resolvedFileId, mediaItem.fileName, confirmedSize, mediaItem.remoteFileId)
                val streamUrl = proxyServer.getStreamUrl(resolvedFileId)

                Toast.makeText(context, "Playing: ${mediaItem.title}", Toast.LENGTH_SHORT).show()

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(streamUrl), mediaItem.mimeType)
                    putExtra("title", mediaItem.title)
                    putExtra("secure_uri", true)
                }

                val playerManager = com.telegramplayer.data.PlayerManager(requireContext())
                val selectedPackage = playerManager.getSelectedPlayer()

                if (selectedPackage != null && selectedPackage != com.telegramplayer.data.PlayerManager.PACKAGE_ASK_EVERY_TIME) {
                    intent.setPackage(selectedPackage)
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Selected player not found", Toast.LENGTH_SHORT).show()
                        launchPlayerChooser(intent)
                    }
                } else {
                    launchPlayerChooser(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error preparing file for streaming", e)
                Toast.makeText(context, "Error preparing video", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchPlayerChooser(intent: Intent) {
        try {
            startActivity(Intent.createChooser(intent, "Select Video Player"))
        } catch (e: Exception) {
            Toast.makeText(context, "No video player found", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleLongClick(mediaItem: MediaItem) {
        if (mediaItem.mediaType != com.telegramplayer.data.MediaType.DOCUMENT) return

        pendingDownloadItem = mediaItem
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                downloadSubtitle(mediaItem)
            } else {
                Toast.makeText(context, "Please allow 'All files access' to save to Downloads", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
                manageStorageLauncher.launch(intent)
            }
        } else {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                downloadSubtitle(mediaItem)
            } else {
                 requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun downloadSubtitle(item: MediaItem) {
        Toast.makeText(context, "Downloading ${item.title}...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                tdClient.exportFileToDownloads(item.fileId, item.fileName) { progress ->
                    // Optional progress UI could be added here
                }
            }
            if (success) {
                Toast.makeText(context, "Saved to Download folder", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to download", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
