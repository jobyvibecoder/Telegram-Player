package com.telegramplayer.browse

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import com.telegramplayer.TelegramPlayerApp
import com.telegramplayer.data.MediaItem
import com.telegramplayer.data.MediaType
import com.telegramplayer.streaming.StreamingProxyServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    companion object {
        private const val TAG = "SearchFragment"
    }

    private val tdClient get() = TelegramPlayerApp.getTdClient()
    private val proxyServer = StreamingProxyServer.getInstance()

    private lateinit var rowsAdapter: ArrayObjectAdapter
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
        setSearchResultProvider(this)

        setOnItemViewClickedListener { _, item, _, _ ->
            if (item is MediaItem) {
                if (item.mediaType == MediaType.DOCUMENT) {
                    Toast.makeText(context, "Long press 'Select' to download this file", Toast.LENGTH_SHORT).show()
                } else {
                    playWithSelectedPlayer(item)
                }
            }
        }
    }

    override fun getResultsAdapter(): ObjectAdapter {
        return rowsAdapter
    }

    override fun onQueryTextChange(newQuery: String?): Boolean {
        performSearch(newQuery)
        return true
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        performSearch(query)
        return true
    }

    private fun performSearch(query: String?) {
        searchJob?.cancel()
        if (query.isNullOrBlank()) {
            rowsAdapter.clear()
            return
        }

        searchJob = lifecycleScope.launch {
            delay(500) // Debounce
            
            rowsAdapter.clear()
            val loadingHeader = HeaderItem("Searching for '$query'...")
            rowsAdapter.add(ListRow(loadingHeader, ArrayObjectAdapter()))

            try {
                val mediaItems = withContext(Dispatchers.IO) {
                    tdClient.searchMediaGlobally(query, limit = 50)
                }

                rowsAdapter.clear()
                
                if (mediaItems.isEmpty()) {
                    val noResultsHeader = HeaderItem("No results found for '$query'")
                    rowsAdapter.add(ListRow(noResultsHeader, ArrayObjectAdapter()))
                } else {
                    val header = HeaderItem("Search Results")
                    val listRowAdapter = ArrayObjectAdapter(MediaCardPresenter(
                        cardWidth = 320, 
                        cardHeight = 480
                    ) { mediaItem ->
                        // Long click handler (if needed for download)
                    })
                    
                    mediaItems.forEach { listRowAdapter.add(it) }
                    rowsAdapter.add(ListRow(header, listRowAdapter))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Search error", e)
            }
        }
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
                        startActivity(Intent.createChooser(intent, "Select Video Player"))
                    }
                } else {
                    startActivity(Intent.createChooser(intent, "Select Video Player"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error preparing file for streaming", e)
                Toast.makeText(context, "Error preparing video", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
