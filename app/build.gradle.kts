plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = java.util.Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

val telegramApiId = (localProps.getProperty("telegram.api_id") ?: "0").trim().toIntOrNull() ?: 0
val telegramApiHash = localProps.getProperty("telegram.api_hash")?.trim() ?: ""

android {
    namespace = "com.telegramplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.telegramplayer"
        minSdk = 21
        targetSdk = 34
        versionCode = 3
        versionName = "1.0.2"

        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId.toString())
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")

        // Filter for Fire TV architectures (arm64-v8a primary, armeabi-v7a fallback)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        val releaseKeystore = file("../release.keystore")
        if (releaseKeystore.exists()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = "password"
                keyAlias = "release"
                keyPassword = "password"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning != null) {
                signingConfig = releaseSigning
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        buildConfig = true
    }

    // Configure jniLibs location
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

tasks.withType<JavaCompile> {
    enabled = false
}

dependencies {
    // Leanback UI for Fire TV
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.leanback:leanback-preference:1.0.0")

    // Core AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.1")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-process:2.6.2")

    // NanoHTTPD for local streaming proxy
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Coroutines for async TDLib operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // QR Code generation for auth
    implementation("com.google.zxing:core:3.5.2")

    // Glide for image loading (channel thumbnails)
    implementation("com.github.bumptech.glide:glide:4.16.0")
}
