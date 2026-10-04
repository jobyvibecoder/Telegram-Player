package com.telegramplayer.browse

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.telegramplayer.R

/**
 * Activity that hosts the VideoGridFragment to display videos for a specific chat.
 */
class VideoGridActivity : FragmentActivity() {

    companion object {
        const val EXTRA_CHAT_ID = "extra_chat_id"
        const val EXTRA_CHAT_TITLE = "extra_chat_title"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_grid)

        if (savedInstanceState == null) {
            val chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0L)
            val title = intent.getStringExtra(EXTRA_CHAT_TITLE) ?: "Videos"

            val fragment = VideoGridFragment().apply {
                arguments = Bundle().apply {
                    putLong(EXTRA_CHAT_ID, chatId)
                    putString(EXTRA_CHAT_TITLE, title)
                }
            }

            supportFragmentManager.beginTransaction()
                .replace(R.id.video_grid_frame, fragment)
                .commit()
        }
    }
}
