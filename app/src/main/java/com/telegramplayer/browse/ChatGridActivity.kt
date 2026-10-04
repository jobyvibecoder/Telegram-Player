package com.telegramplayer.browse

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.telegramplayer.R

/**
 * Activity that hosts the ChatGridFragment to display all chats of a specific category.
 */
class ChatGridActivity : FragmentActivity() {

    companion object {
        const val EXTRA_CHAT_TYPE = "extra_chat_type"
        const val EXTRA_TITLE = "extra_title"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_grid) // We can reuse the same frame layout

        if (savedInstanceState == null) {
            val chatType = intent.getStringExtra(EXTRA_CHAT_TYPE) ?: ""
            val title = intent.getStringExtra(EXTRA_TITLE) ?: "Chats"

            val fragment = ChatGridFragment().apply {
                arguments = Bundle().apply {
                    putString(EXTRA_CHAT_TYPE, chatType)
                    putString(EXTRA_TITLE, title)
                }
            }

            supportFragmentManager.beginTransaction()
                .replace(R.id.video_grid_frame, fragment)
                .commit()
        }
    }
}
