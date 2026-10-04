package com.telegramplayer.browse

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.telegramplayer.R
import com.telegramplayer.data.ChatInfo
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.request.RequestOptions

/**
 * Presenter for Chat/Folder cards on the main screen.
 * Displays chat thumbnail and title with enhanced focus effects.
 * Large cards for TV viewing.
 */
class ChatCardPresenter : Presenter() {

    companion object {
        private const val CARD_WIDTH = 280
        private const val CARD_HEIGHT = 280
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
            
            // Dark card background
            val background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.card_background))
                cornerRadius = 0f
            }
            setBackgroundDrawable(background)
            
            // Info area styling
            infoAreaBackground = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.surface_elevated))
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            }
            
            // Add vertical margins to prevent shadow clipping by the row container
            layoutParams = android.view.ViewGroup.MarginLayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = (16 * context.resources.displayMetrics.density).toInt()
                setMargins(0, margin, 0, margin)
            }
            
            // Setup title text for marquee
            val titleView = findViewById<android.widget.TextView>(androidx.leanback.R.id.title_text)
            titleView?.setSingleLine(true)
            titleView?.marqueeRepeatLimit = -1
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val cardView = viewHolder.view as ImageCardView
        val context = cardView.context

        when (item) {
            is ChatInfo -> {
                cardView.titleText = item.title
                cardView.contentText = if (item.mediaCount > 0) "${item.mediaCount} videos" else ""
                
                if (item.photoPath != null) {
                    Glide.with(context)
                        .load(item.photoPath)
                        .apply(RequestOptions.fitCenterTransform())
                        .error(R.drawable.ic_folder)
                        .into(cardView.mainImageView)
                } else {
                    val icon = ContextCompat.getDrawable(context, R.drawable.ic_folder)
                    cardView.mainImageView.setImageDrawable(icon)
                    cardView.mainImageView.setColorFilter(getColorForId(item.id))
                }
            }
            is com.telegramplayer.data.SeeMoreItem -> {
                cardView.titleText = "See More"
                cardView.contentText = item.title
                val icon = ContextCompat.getDrawable(context, R.drawable.ic_folder) // Use a better icon if available
                cardView.mainImageView.setImageDrawable(icon)
                cardView.mainImageView.setColorFilter(Color.GRAY)
            }
        }

        // Custom Focus Handling
        cardView.setOnFocusChangeListener { view, hasFocus ->
            val v = view as ImageCardView
            val titleView = v.findViewById<android.widget.TextView>(androidx.leanback.R.id.title_text)
            
            if (hasFocus) {
                // Enable marquee scrolling
                titleView?.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                titleView?.isSelected = true

                // No Scale
                v.animate().cancel()
                v.scaleX = 1.0f
                v.scaleY = 1.0f
                
                // Cinema-gold glowing border overlay
                val focusBg = GradientDrawable().apply {
                    setColor(android.graphics.Color.TRANSPARENT)
                    setCornerRadius(0f)
                    setStroke(
                        (3 * context.resources.displayMetrics.density).toInt(),
                        ContextCompat.getColor(context, R.color.focus_glow)
                    )
                }
                v.foreground = focusBg

                // Highlighted info area (accent color)
                v.infoAreaBackground = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.netflix_red))
                    cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
                }
                
                v.translationZ = 8f * context.resources.displayMetrics.density
            } else {
                // Disable marquee scrolling
                titleView?.ellipsize = android.text.TextUtils.TruncateAt.END
                titleView?.isSelected = false

                // No Scale
                v.animate().cancel()
                v.scaleX = 1.0f
                v.scaleY = 1.0f
                
                // Normal Background
                v.foreground = null
                val bg = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.card_background))
                    cornerRadius = 0f
                    setStroke(1, ContextCompat.getColor(context, R.color.card_border))
                }
                v.background = bg

                // Normal info area
                v.infoAreaBackground = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.surface_elevated))
                    cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
                }
                
                v.translationZ = 0f
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.mainImage = null
        cardView.onFocusChangeListener = null
    }

    private fun getColorForId(id: Long): Int {
        val colors = listOf(
            0xFFE57373, 0xFFF06292, 0xFFBA68C8, 0xFF9575CD, 0xFF7986CB, 
            0xFF64B5F6, 0xFF4FC3F7, 0xFF4DD0E1, 0xFF4DB6AC, 0xFF81C784,
            0xFFAED581, 0xFFFF8A65, 0xFFD4E157, 0xFFFFD54F, 0xFFFFB74D
        )
        return colors[(kotlin.math.abs(id) % colors.size).toInt()].toInt()
    }
}
