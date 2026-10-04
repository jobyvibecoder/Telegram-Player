package com.telegramplayer.browse

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.VerticalGridPresenter
import androidx.lifecycle.lifecycleScope
import com.telegramplayer.TelegramPlayerApp
import com.telegramplayer.data.ChatInfo
import com.telegramplayer.data.ChatType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragment that displays a vertical grid of chat cards for a specific category.
 */
class ChatGridFragment : VerticalGridSupportFragment() {

    companion object {
        private const val TAG = "ChatGridFragment"
        private const val NUM_COLUMNS = 5
    }

    private var chatTypeStr: String = ""
    private val tdClient get() = TelegramPlayerApp.getTdClient()
    private lateinit var gridAdapter: ArrayObjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        chatTypeStr = arguments?.getString(ChatGridActivity.EXTRA_CHAT_TYPE) ?: ""
        val title = arguments?.getString(ChatGridActivity.EXTRA_TITLE) ?: "Chats"
        
        setupUI(title)
        loadChats()
    }

    private fun setupUI(titleString: String) {
        title = titleString
        
        gridAdapter = ArrayObjectAdapter(ChatCardPresenter())
        
        val gridPresenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_NONE, false).apply {
            numberOfColumns = NUM_COLUMNS
            shadowEnabled = false
        }
        
        setGridPresenter(gridPresenter)
        adapter = gridAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is ChatInfo) {
                val intent = Intent(context, VideoGridActivity::class.java).apply {
                    putExtra(VideoGridActivity.EXTRA_CHAT_ID, item.id)
                    putExtra(VideoGridActivity.EXTRA_CHAT_TITLE, item.title)
                }
                startActivity(intent)
            }
        }
    }

    private fun loadChats() {
        lifecycleScope.launch {
            try {
                // Fetch chats from TDLib (usually already cached since MainBrowseFragment loaded them)
                val allChats = withContext(Dispatchers.IO) { tdClient.getChats(150) }
                
                val chatType = try {
                    ChatType.valueOf(chatTypeStr)
                } catch (e: Exception) {
                    null
                }

                val filteredChats = if (chatType != null) {
                    allChats.filter { it.type == chatType }
                } else {
                    allChats
                }

                gridAdapter.clear()
                filteredChats.forEach { gridAdapter.add(it) }

                if (filteredChats.isEmpty()) {
                    Toast.makeText(context, "No chats found", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading chats", e)
                if (isAdded) {
                    context?.let { Toast.makeText(it, "Error loading chats", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }
}
