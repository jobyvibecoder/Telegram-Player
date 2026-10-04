# Telegram Player for Fire TV

A private Fire TV app (Leanback UI) that streams videos from your Telegram account using TDLib.

## Features

- **QR Code Login**: Scan with your Telegram mobile app - no need to type on TV
- **Phone Number Login**: Fallback option with verification code support
- **Browse Content**: View your private chats, groups, and channels
- **Video Streaming**: Stream videos directly to MX Player via local proxy
- **Large File Support**: Handles files up to 4GB with 10MB chunk streaming
- **Dark Theme**: TV-optimized dark interface with D-pad navigation

## Prerequisites

1. **MX Player** installed on your Fire TV
   - Download "MX Player" from Amazon App Store
   
2. **Telegram API Credentials**
   - Go to [my.telegram.org](https://my.telegram.org)
   - Log in with your phone number
   - Go to "API development tools"
   - Create a new application (or use existing)
   - Note your `api_id` (integer) and `api_hash` (string)

## Setup Instructions

### Step 1: Add Your API Credentials

1. Copy the example properties file:
   ```bash
   cp local.properties.example local.properties
   ```
   *(or copy and rename `local.properties.example` to `local.properties` manually)*

2. Open `local.properties` and add your Telegram API credentials:
   ```properties
   telegram.api_id=12345678
   telegram.api_hash=your_api_hash_string_here
   ```

> **Note:** `local.properties` is ignored by Git, so your credentials will remain private on your computer and never be committed.


### Step 2: Build the APK

```bash
# Windows
.\gradlew.bat assembleDebug

# Linux/Mac
./gradlew assembleDebug
```

The APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

### Step 3: Install on Fire TV

1. Enable Developer Options on Fire TV:
   - Settings → My Fire TV → Developer Options → ADB debugging: ON

2. Find Fire TV IP address:
   - Settings → My Fire TV → About → Network

3. Install via ADB:
```bash
adb connect <FIRE_TV_IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Launch "Telegram Player" from your Fire TV apps
2. Scan the QR code with your Telegram mobile app, OR
3. Use the remote to enter your phone number
4. Browse your chats and select a video to play
5. MX Player will open and stream the video

## Architecture

```
TelegramPlayer/
├── app/src/main/
│   ├── java/com/telegramplayer/
│   │   ├── auth/         # QR/Phone login
│   │   ├── browse/       # Leanback UI
│   │   ├── data/         # TDLib client & models
│   │   └── streaming/    # NanoHTTPD proxy
│   ├── jniLibs/          # TDLib native libraries
│   └── res/              # Resources & layouts
```

## Technical Details

- **TDLib Version**: 1.8.52 (via up9cloud/android-libtdjson)
- **Streaming**: NanoHTTPD with HTTP Range Request support
- **File Chunks**: 10MB chunks streamed on-demand
- **Max File Size**: 4GB (Telegram API limit)

## Troubleshooting

### "No video player found"
- Install MX Player from Amazon App Store

### QR Code not appearing
- Check your API credentials are correct
- Ensure internet connection is working

### Video not playing / buffering
- Check Fire TV has stable internet
- MX Player may need to buffer initially

### Build errors
- Ensure you have JDK 17+ installed
- Run `gradlew clean` before building

## License

Private use only. TDLib is licensed under the Boost Software License.

## Credits

- [TDLib](https://github.com/tdlib/td) - Telegram Database Library
- [up9cloud/android-libtdjson](https://github.com/up9cloud/android-libtdjson) - Prebuilt TDLib binaries
- [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) - Lightweight HTTP server
- [ZXing](https://github.com/zxing/zxing) - QR code generation
