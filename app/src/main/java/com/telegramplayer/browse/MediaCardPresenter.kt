package com.telegramplayer.browse

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.telegramplayer.R
import com.telegramplayer.data.MediaItem
import com.telegramplayer.data.MediaType
import com.telegramplayer.data.CinemetaRepository
import com.telegramplayer.data.MalayalamPosterRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Premium Netflix-style presenter for media item cards.
 * 
 * Visual design:
 * - 2:3 poster aspect ratio (vertical movie poster style)
 * - Rounded corners (12dp) with subtle card elevation
 * - Focus: smooth 1.08x scale + cinema-gold glowing border + elevated info bar
 * - Unfocus: spring-back to 1.0x with muted styling
 * - Poster loading priority: Telegram thumbnail → Cinemeta poster → gradient placeholder
 */
class MediaCardPresenter(
    private val cardWidth: Int = 220,
    private val cardHeight: Int = 330,
    private val onLongClick: ((MediaItem) -> Unit)? = null
) : Presenter() {

    companion object {
        private const val CORNER_RADIUS_DP = 0f
        private const val FOCUS_SCALE = 1.08f
        private const val FOCUS_ANIM_DURATION = 250L
        private const val FOCUS_BORDER_WIDTH = 3
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val jobs = mutableMapOf<ViewHolder, kotlinx.coroutines.Job>()

    class MediaViewHolder(
        val wrapper: android.widget.FrameLayout,
        val cardView: ImageCardView,
        val imdbBadge: android.widget.TextView
    ) : Presenter.ViewHolder(wrapper)

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val density = parent.context.resources.displayMetrics.density
        val cornerRadius = CORNER_RADIUS_DP * density

        val wrapper = android.widget.FrameLayout(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val cardView = ImageCardView(parent.context).apply {
            isFocusable = false
            isFocusableInTouchMode = false

            // Dark card background with rounded corners and subtle border
            val background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.card_background))
                setCornerRadius(cornerRadius)
                setStroke(1, ContextCompat.getColor(context, R.color.card_border))
            }
            setBackgroundDrawable(background)

            // Setup title text for marquee
            val titleView = findViewById<android.widget.TextView>(androidx.leanback.R.id.title_text)
            titleView?.setSingleLine(true)
            titleView?.marqueeRepeatLimit = -1

            // Info area: dark translucent bottom section
            infoAreaBackground = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.surface_elevated))
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, cornerRadius, cornerRadius, cornerRadius, cornerRadius)
            }

            // Poster image fills card with center crop
            setMainImageScaleType(ImageView.ScaleType.CENTER_CROP)
        }

        // IMDb Badge
        val badge = android.widget.TextView(parent.context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                topMargin = (8 * density).toInt()
                marginEnd = (8 * density).toInt()
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F5C518")) // IMDb Yellow
                setCornerRadius(4f * density)
            }
            setTextColor(Color.BLACK)
            textSize = 12f
            setPadding((6 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
            android.graphics.Typeface.DEFAULT_BOLD?.let { typeface = it }
            visibility = android.view.View.GONE
            elevation = 4f * density // slightly above poster
        }
        wrapper.addView(cardView)
        wrapper.addView(badge)

        return MediaViewHolder(wrapper, cardView, badge)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val mediaItem = item as MediaItem
        val mediaViewHolder = viewHolder as MediaViewHolder
        val wrapper = mediaViewHolder.wrapper
        val cardView = mediaViewHolder.cardView
        val badge = mediaViewHolder.imdbBadge
        val context = cardView.context
        val density = context.resources.displayMetrics.density
        val cornerRadius = CORNER_RADIUS_DP * density

        // ── Title & Metadata ──
        cardView.titleText = mediaItem.title
        val durationText = formatDuration(mediaItem.duration)
        val sizeText = formatSize(mediaItem.size)
        cardView.contentText = if (mediaItem.duration > 0) "$durationText • $sizeText" else sizeText

        cardView.setMainImageDimensions(cardWidth, cardHeight)

        // ── Poster Loading (Cinemeta priority) ──
        // Set gradient placeholder immediately to avoid blank flashes
        setGradientPlaceholder(cardView, mediaItem)



        // Hide badge initially
        badge.visibility = android.view.View.GONE

        if (mediaItem.mediaType == MediaType.VIDEO) {
            val cinemetaRepo = CinemetaRepository.getInstance(context)
            val malayalamRepo = MalayalamPosterRepository.getInstance(context)
            if (cinemetaRepo.looksLikeMovie(mediaItem.fileName, mediaItem.duration, mediaItem.size)) {
                val job = scope.launch {
                    // Step 1: Parse the clean title and year from the filename
                    val parsed = cinemetaRepo.parseFileName(mediaItem.fileName)
                    
                    // Priority 1: Search Malayalam Posters cache
                    val malayalamPoster = malayalamRepo.searchPoster(parsed.title, parsed.year, parsed.isSeries)
                    
                    // Always query Cinemeta to get the IMDb rating (and fallback poster)
                    val cinemetaResult = cinemetaRepo.searchMoviePoster(mediaItem.fileName)

                    // Safety check: skip if view is detached or context is destroyed
                    if (context is android.app.Activity && context.isDestroyed) return@launch
                    
                    try {
                        val finalPosterUrl = malayalamPoster ?: cinemetaResult?.posterUrl
                        
                        if (!finalPosterUrl.isNullOrEmpty()) {
                            com.bumptech.glide.Glide.with(context)
                                .load(finalPosterUrl)
                                .centerCrop()
                                .placeholder(cardView.mainImageView.drawable)
                                .into(cardView.mainImageView)
                            cardView.mainImageView.clearColorFilter()
                        } else if (mediaItem.thumbnailPath != null) {
                            // Priority 3: Fallback to Telegram-provided thumbnail
                            com.bumptech.glide.Glide.with(context)
                                .load(mediaItem.thumbnailPath)
                                .centerCrop()
                                .placeholder(cardView.mainImageView.drawable)
                                .into(cardView.mainImageView)
                            cardView.mainImageView.clearColorFilter()
                        }

                        // Always show IMDb Badge if rating exists from Cinemeta
                        if (cinemetaResult != null && !cinemetaResult.imdbRating.isNullOrBlank()) {
                            badge.text = "★ ${cinemetaResult.imdbRating}"
                            badge.visibility = android.view.View.VISIBLE
                        }
                    } catch (e: Exception) {
                        // Ignore Glide destroyed activity exceptions
                    }
                }
                jobs[viewHolder] = job
            } else {
                // Not a movie (e.g. short clip). Priority: Telegram thumbnail
                if (mediaItem.thumbnailPath != null) {
                    com.bumptech.glide.Glide.with(context)
                        .load(mediaItem.thumbnailPath)
                        .centerCrop()
                        .placeholder(cardView.mainImageView.drawable)
                        .into(cardView.mainImageView)
                    cardView.mainImageView.clearColorFilter()
                }
            }
        } else {
            // Not a video (audio/doc). Priority: Telegram thumbnail
            if (mediaItem.thumbnailPath != null) {
                com.bumptech.glide.Glide.with(context)
                    .load(mediaItem.thumbnailPath)
                    .centerCrop()
                    .placeholder(cardView.mainImageView.drawable)
                    .into(cardView.mainImageView)
                cardView.mainImageView.clearColorFilter()
            }
        }

        // ── Premium Focus/Unfocus Animations ──
        wrapper.setOnFocusChangeListener { view, hasFocus ->
            val v = view as android.widget.FrameLayout
            val titleView = cardView.findViewById<android.widget.TextView>(androidx.leanback.R.id.title_text)
            
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
                cardView.infoAreaBackground = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.netflix_red))
                    cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
                }

                // Bring to front for overlap effect
                v.translationZ = 8f * density

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

                // Normal info area
                cardView.infoAreaBackground = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, R.color.surface_elevated))
                    cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
                }

                // Reset elevation
                v.translationZ = 0f
            }
        }

        // ── Long Click (document download) ──
        if (onLongClick != null) {
            wrapper.setOnLongClickListener {
                onLongClick.invoke(mediaItem)
                true
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val mediaViewHolder = viewHolder as MediaViewHolder
        val wrapper = mediaViewHolder.wrapper
        val cardView = mediaViewHolder.cardView
        jobs.remove(viewHolder)?.cancel()
        
        try {
            com.bumptech.glide.Glide.with(cardView.context).clear(cardView.mainImageView)
        } catch (e: Exception) {}
        
        cardView.mainImage = null
        wrapper.onFocusChangeListener = null
        wrapper.setOnLongClickListener(null)
    }

    /**
     * Sets a gradient placeholder with a media-type icon.
     * Creates a premium dark gradient background instead of a blank card.
     */
    private fun setGradientPlaceholder(cardView: ImageCardView, mediaItem: MediaItem) {
        val context = cardView.context

        // Set gradient background on the image area
        val gradientDrawable = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                ContextCompat.getColor(context, R.color.poster_placeholder_start),
                ContextCompat.getColor(context, R.color.poster_placeholder_end)
            )
        )
        cardView.mainImageView.setImageDrawable(gradientDrawable)

        // Overlay a centered media-type icon
        val iconRes = when (mediaItem.mediaType) {
            MediaType.VIDEO -> android.R.drawable.ic_media_play
            MediaType.AUDIO -> android.R.drawable.ic_lock_silent_mode_off
            MediaType.DOCUMENT -> android.R.drawable.ic_menu_save
        }
        cardView.mainImageView.apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER
            setColorFilter(ContextCompat.getColor(context, R.color.text_secondary))
            setBackgroundDrawable(gradientDrawable)
        }
    }

    private fun formatDuration(seconds: Int): String {
        if (seconds <= 0) return ""
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) {
            "${hours}h ${minutes}m"
        } else {
            "${minutes}m"
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> String.format("%.2f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
