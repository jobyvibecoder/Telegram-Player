# Add project specific ProGuard rules here.
# Keep TDLib JSON classes
-keep class org.drinkless.tdlib.** { *; }
-keep class io.github.up9cloud.td.** { *; }

# Keep NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# Keep app data classes
-keep class com.telegramplayer.data.** { *; }
-keep class com.telegramplayer.browse.** { *; }

# Keep Leanback classes
-keep class androidx.leanback.** { *; }

# Keep BuildConfig
-keep class com.telegramplayer.BuildConfig { *; }

# Keep Glide generated API
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { *; }
