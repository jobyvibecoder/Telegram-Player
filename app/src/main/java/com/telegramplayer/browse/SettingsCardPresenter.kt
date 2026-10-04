package com.telegramplayer.browse

import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.telegramplayer.R

/**
 * Presenter for the Settings card.
 */
class SettingsCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(300, 300)
            
            // Add vertical margins to prevent shadow clipping by the row container
            layoutParams = android.view.ViewGroup.MarginLayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = (16 * context.resources.displayMetrics.density).toInt()
                setMargins(0, margin, 0, margin)
            }
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val cardView = viewHolder.view as ImageCardView
        
        if (item is String) {
            cardView.titleText = item
            
            if (item == "Player Settings") {
                cardView.contentText = "Configure app settings"
                cardView.mainImage = ContextCompat.getDrawable(cardView.context, R.drawable.ic_settings_large)
            } else if (item == "Logout") {
                cardView.contentText = "Sign out of Telegram"
                cardView.mainImage = ContextCompat.getDrawable(cardView.context, R.drawable.ic_logout_large)
            }
            
            cardView.setMainImageScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE)
            
            // Custom Focus Handling matching Video Tiles
            cardView.setOnFocusChangeListener { view, hasFocus ->
                val v = view as ImageCardView
                if (hasFocus) {
                    // No Scale
                    v.animate().cancel()
                    v.scaleX = 1.0f
                    v.scaleY = 1.0f
                    
                    // Cinema-gold glowing border overlay
                    val focusBg = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.TRANSPARENT)
                        setCornerRadius(0f)
                        setStroke(
                            (3 * cardView.context.resources.displayMetrics.density).toInt(),
                            ContextCompat.getColor(cardView.context, R.color.focus_glow)
                        )
                    }
                    v.foreground = focusBg

                    // Highlighted info area (accent color)
                    v.infoAreaBackground = android.graphics.drawable.GradientDrawable().apply {
                        setColor(ContextCompat.getColor(cardView.context, R.color.netflix_red))
                        cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
                    }
                    
                    v.translationZ = 8f * cardView.context.resources.displayMetrics.density
                } else {
                    // No Scale
                    v.animate().cancel()
                    v.scaleX = 1.0f
                    v.scaleY = 1.0f
                    
                    // Normal Background
                    v.foreground = null
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        setColor(ContextCompat.getColor(cardView.context, R.color.card_background))
                        cornerRadius = 0f
                        setStroke(1, ContextCompat.getColor(cardView.context, R.color.card_border))
                    }
                    v.background = bg

                    // Normal info area
                    v.infoAreaBackground = android.graphics.drawable.GradientDrawable().apply {
                        setColor(ContextCompat.getColor(cardView.context, R.color.surface_elevated))
                        cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
                    }
                    
                    v.translationZ = 0f
                }
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.badgeImage = null
        cardView.mainImage = null
    }
}
