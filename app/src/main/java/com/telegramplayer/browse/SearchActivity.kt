package com.telegramplayer.browse

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.telegramplayer.R

class SearchActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.search_frame, SearchFragment())
                .commit()
        }
    }
    
    override fun onSearchRequested(): Boolean {
        // Reroute hardware search button to in-app search
        startActivity(Intent(this, SearchActivity::class.java))
        return true
    }
}
