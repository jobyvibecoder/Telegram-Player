package com.telegramplayer.browse

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.BackgroundManager
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.telegramplayer.R
import com.telegramplayer.TelegramPlayerApp
import com.telegramplayer.data.ChatInfo
import com.telegramplayer.data.ChatType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitAll
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import com.telegramplayer.data.MediaItem
import com.telegramplayer.data.MediaType
import com.telegramplayer.streaming.StreamingProxyServer

// Sidebar action IDs
private const val ACTION_REFRESH = "action_refresh"
private const val ACTION_LATEST = "action_latest"

/**
 * Main Leanback browse fragment displaying Chat Folders.
 * Rows: Private Chats, Groups, Channels.
 * Features Netflix-style branding and background updates.
 */
class MainBrowseFragment : BrowseSupportFragment() {

    companion object {
        private const val TAG = "MainBrowseFragment"
    }

    private val tdClient get() = TelegramPlayerApp.getTdClient()
    private lateinit var backgroundManager: BackgroundManager
    private var defaultBackground: ColorDrawable? = null
    
    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter().apply {
        shadowEnabled = false
        selectEffectEnabled = true
    })

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

    private var currentChats: List<ChatInfo>? = null
    private var currentLatestMedia: List<MediaItem>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()
        setupBackgroundManager()
        setupEventListeners()
    }

    override fun onResume() {
        super.onResume()
        // Only fetch on first load — subsequent returns (from Settings, player, etc.)
        // skip the 43 TDLib requests since data hasn't changed.
        // User can always hit the refresh button for fresh data.
        if (currentChats == null && currentLatestMedia == null) {
            loadChats()
        }
    }



    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.background = ColorDrawable(Color.TRANSPARENT)
        
        // Safely change Search Orb icon and inject the new Smart Search button
        view.post { changeSearchOrbIconAndInjectSearch(view) }
    }

    private fun changeSearchOrbIconAndInjectSearch(view: View) {
        if (view is androidx.leanback.widget.SearchOrbView) {
            // This is the original SearchOrbView — repurpose it as Refresh
            view.orbIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_refresh)

            val parent = view.parent as? android.view.ViewGroup ?: return
            // Only inject once
            if (parent.findViewById<View>(R.id.smart_search_orb) != null) return

            // 1. Remember original layout params & position
            val originalLp = view.layoutParams
            val originalIndex = parent.indexOfChild(view)

            // 2. Remove the original SearchOrbView from TitleView
            parent.removeView(view)

            // 3. Create a new Search orb
            val searchOrb = androidx.leanback.widget.SearchOrbView(requireContext()).apply {
                id = R.id.smart_search_orb
                orbIcon = ContextCompat.getDrawable(requireContext(), android.R.drawable.ic_menu_search)
                setOnClickListener {
                    startActivity(android.content.Intent(context, SearchActivity::class.java))
                }
                orbColors = androidx.leanback.widget.SearchOrbView.Colors(
                    ContextCompat.getColor(requireContext(), R.color.netflix_red)
                )
            }

            // 4. Create a horizontal LinearLayout wrapper holding both orbs
            val wrapper = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                // Let focus pass through to children, don't block it
                descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
                isFocusable = false
            }

            // Add Search orb first (left), then Refresh orb (right)
            val density = requireContext().resources.displayMetrics.density
            val searchLp = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (8 * density).toInt()
            }
            searchOrb.isFocusable = true
            searchOrb.isFocusableInTouchMode = true
            wrapper.addView(searchOrb, searchLp)

            val refreshLp = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            wrapper.addView(view, refreshLp)

            // Force D-pad left/right navigation manually, as Leanback often intercepts focus
            view.setOnKeyListener { v, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                    searchOrb.requestFocus()
                    true
                } else {
                    false
                }
            }
            
            searchOrb.setOnKeyListener { v, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                    view.requestFocus()
                    true
                } else {
                    false
                }
            }

            // 5. Insert the wrapper back into TitleView at the same position
            //    with the same layout params so TitleView treats it as the orb area
            parent.addView(wrapper, originalIndex, originalLp)

            return
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                changeSearchOrbIconAndInjectSearch(view.getChildAt(i))
            }
        }
    }

    private fun setupUI() {
        title = getString(R.string.browse_title)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        
        // Netflix-style branding
        brandColor = ContextCompat.getColor(requireContext(), R.color.netflix_red)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.netflix_red)
        
        setOnSearchClickedListener {
            Toast.makeText(context, "Refreshing chats…", Toast.LENGTH_SHORT).show()
            loadChats(true)
        }

        adapter = rowsAdapter
    }
    
    private fun setupBackgroundManager() {
        backgroundManager = BackgroundManager.getInstance(requireActivity())
        backgroundManager.attach(requireActivity().window)
        defaultBackground = ColorDrawable(ContextCompat.getColor(requireContext(), R.color.netflix_background))
        backgroundManager.color = ContextCompat.getColor(requireContext(), R.color.netflix_background)
    }

    private fun setupEventListeners() {
        // Launch Video Grid / handle sidebar actions on Click
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when {
                item is MediaItem -> {
                    if (item.mediaType == MediaType.DOCUMENT) {
                        Toast.makeText(context, "Long press 'Select' to download this file", Toast.LENGTH_SHORT).show()
                    } else {
                        playWithSelectedPlayer(item)
                    }
                }
                item is com.telegramplayer.data.SeeMoreItem -> {
                    val intent = Intent(context, ChatGridActivity::class.java).apply {
                        putExtra(ChatGridActivity.EXTRA_CHAT_TYPE, item.chatType.name)
                        putExtra(ChatGridActivity.EXTRA_TITLE, item.title)
                    }
                    startActivity(intent)
                }
                item is ChatInfo -> {
                    val intent = Intent(context, VideoGridActivity::class.java).apply {
                        putExtra(VideoGridActivity.EXTRA_CHAT_ID, item.id)
                        putExtra(VideoGridActivity.EXTRA_CHAT_TITLE, item.title)
                    }
                    startActivity(intent)
                }
                item is String && item == "Player Settings" -> {
                    startActivity(SettingsActivity.newIntent(requireContext()))
                }
                item is String && item == "Logout" -> {
                    lifecycleScope.launch {
                        tdClient.logOut()
                        val intent = Intent(requireContext(), com.telegramplayer.auth.AuthActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(intent)
                        requireActivity().finish()
                    }
                }
            }
        }

        // Update background on item selection
        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
            if (item is ChatInfo) {
                updateBackground(item.photoPath)
            }
        }
    }

    private fun updateBackground(uri: String?) {
        if (uri != null) {
            Glide.with(this)
                .load(uri)
                .apply(RequestOptions.centerCropTransform())
                .into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.drawable.Drawable>() {
                    override fun onResourceReady(
                        resource: android.graphics.drawable.Drawable,
                        transition: com.bumptech.glide.request.transition.Transition<in android.graphics.drawable.Drawable>?
                    ) {
                        backgroundManager.drawable = resource
                    }

                    override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {
                        backgroundManager.drawable = defaultBackground
                    }
                })
        } else {
            backgroundManager.drawable = defaultBackground
        }
    }

    private fun loadChats(isManualRefresh: Boolean = false) {
        lifecycleScope.launch {
            try {
                val cache = com.telegramplayer.data.StartupCache(requireContext())

                // ── Phase 1: Instant UI from startup cache (< 5ms) ──────────
                if (currentChats == null && currentLatestMedia == null) {
                    val cachedChats = cache.loadChats()
                    val cachedMedia = cache.loadMediaItems()

                    if (cachedChats != null || cachedMedia != null) {
                        currentChats = cachedChats
                        currentLatestMedia = cachedMedia
                        rebuildRows(cachedChats ?: emptyList(), cachedMedia ?: emptyList())
                    } else {
                        // No cache (first launch) — show loading indicator
                        rowsAdapter.clear()
                        val loadingHeader = HeaderItem(rowsAdapter.size().toLong(), "Loading chats...")
                        val loadingAdapter = ArrayObjectAdapter(ChatCardPresenter())
                        rowsAdapter.add(ListRow(loadingHeader, loadingAdapter))
                    }
                }

                // ── Phase 2: Refresh from TDLib in background ────────────
                if (isManualRefresh) {
                    withContext(Dispatchers.IO) { 
                        tdClient.loadChats(150) 
                        kotlinx.coroutines.delay(1000) // Give TDLib a second to process new messages
                    }
                }
                val chats = withContext(Dispatchers.IO) { tdClient.getChats(150) }
                val latestMedia = withContext(Dispatchers.IO) { tdClient.getLatestMedia(60, chats, isManualRefresh) }

                // Always update UI on manual refresh; otherwise only if data changed (prevents Leanback row selection reset)
                if (isManualRefresh || chats != currentChats || latestMedia != currentLatestMedia) {
                    currentChats = chats
                    currentLatestMedia = latestMedia
                    rebuildRows(chats, latestMedia)

                    // Save to cache for next launch
                    cache.saveChats(chats)
                    cache.saveMediaItems(latestMedia.take(50))
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading chats", e)
                Toast.makeText(context, "Error loading chats", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun rebuildRows(chats: List<ChatInfo>, latestMedia: List<MediaItem>) {
        rowsAdapter.clear()

        // Row: Latest Files
        if (latestMedia.isNotEmpty()) {
            addLatestFilesRow(latestMedia)
        }

        // Group chats by type
        val privateChats = chats.filter { it.type == ChatType.PRIVATE }
        val groups = chats.filter { it.type == ChatType.GROUP }
        val channels = chats.filter { it.type == ChatType.CHANNEL }

        if (privateChats.isNotEmpty()) addChatRow("💬 Private Chats", privateChats, ChatType.PRIVATE)
        if (groups.isNotEmpty()) addChatRow("👥 Groups", groups, ChatType.GROUP)
        if (channels.isNotEmpty()) addChatRow("📺 Channels", channels, ChatType.CHANNEL)
        addSettingsRow()
    }

    private fun addChatRow(title: String, chats: List<ChatInfo>, type: ChatType) {
        val cardPresenter = ChatCardPresenter()
        val listRowAdapter = ArrayObjectAdapter(cardPresenter)
        
        // Add top 10 chats to row
        val limit = 10
        chats.take(limit).forEach { listRowAdapter.add(it) }

        if (chats.size > limit) {
            listRowAdapter.add(com.telegramplayer.data.SeeMoreItem(type, title))
        }

        val headerItem = HeaderItem(rowsAdapter.size().toLong(), title)
        rowsAdapter.add(ListRow(headerItem, listRowAdapter))
    }

    private fun addSettingsRow() {
        val listRowAdapter = ArrayObjectAdapter(SettingsCardPresenter())
        listRowAdapter.add("Player Settings")
        listRowAdapter.add("Logout")
        val headerItem = HeaderItem(rowsAdapter.size().toLong(), "⚙️ Settings")
        rowsAdapter.add(ListRow(headerItem, listRowAdapter))
    }

    private fun addLatestFilesRow(items: List<MediaItem>) {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val minColumnWidth = 380
        val columns = (width / minColumnWidth).coerceAtLeast(4)
        val usableWidth = width - (80 * displayMetrics.density)
        val cardWidth = ((usableWidth / columns) / 1.15).toInt()
        val cardHeight = (cardWidth * 3 / 2)

        val cardPresenter = MediaCardPresenter(cardWidth, cardHeight) { mediaItem ->
            handleLongClick(mediaItem)
        }
        val listRowAdapter = ArrayObjectAdapter(cardPresenter)
        
        items.forEach { listRowAdapter.add(it) }

        val headerItem = HeaderItem(rowsAdapter.size().toLong(), "🕐 Latest Files")
        rowsAdapter.add(ListRow(headerItem, listRowAdapter))
    }

    private fun playWithSelectedPlayer(mediaItem: MediaItem) {
        lifecycleScope.launch {
            try {
                val resolvedFileId = withContext(Dispatchers.IO) {
                    tdClient.prepareFileForStreaming(mediaItem.fileId, mediaItem.remoteFileId)
                }
                var confirmedSize = withContext(Dispatchers.IO) { tdClient.getFileSize(resolvedFileId) }
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
        if (mediaItem.mediaType != MediaType.DOCUMENT) return
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
                tdClient.exportFileToDownloads(item.fileId, item.fileName) { _ -> }
            }
            if (success) {
                Toast.makeText(context, "Saved to Download folder", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to download", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
