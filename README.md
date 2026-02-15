# OC Remote

Android client for [OpenCode](https://github.com/anomalyco/opencode) servers. Displays the OpenCode web UI in a WebView while a background service maintains SSE connections for push notifications.

**This is an unofficial community project, not affiliated with the OpenCode team.**

## Features

- **WebView UI** -- full OpenCode web interface with auth, deep-linking, pull-to-refresh, file upload, and keyboard handling
- **Multi-server** -- connect to multiple OpenCode servers simultaneously, each with independent connect/disconnect
- **Background SSE** -- foreground service keeps SSE connections alive when the app is minimized
- **Push notifications** -- task completion, permission requests, errors; tap a notification to jump to the relevant session
- **Auto-reconnect** -- exponential backoff (1s to 30s), partial WakeLock to prevent CPU sleep
- **Battery optimization prompt** -- warns if the OS may kill the background service, with a one-tap fix

## Requirements

- Android 8.0+ (API 26)
- OpenCode server accessible over the network

## Setup

1. Start the OpenCode server with network access:

```bash
opencode web --port 4096 --hostname 0.0.0.0
```

2. In the app, tap **+** and enter the server URL (e.g. `http://192.168.0.10:4096`), username, and optional password.

3. Tap **Connect** on the server card.

## Building

### Android Studio

1. Open the project
2. Sync Gradle
3. Run on a device or emulator

### Command line

```bash
# Set Android SDK path
echo "sdk.dir=/path/to/android/sdk" > local.properties

# Build debug APK
./gradlew assembleDebug

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

```
dev.minios.ocremote/
├── MainActivity.kt                 # Single activity, edge-to-edge, deep-links
├── OpenCodeApp.kt                  # Hilt application
├── data/
│   ├── api/
│   │   ├── OpenCodeApi.kt          # Stateless REST client (Ktor/OkHttp)
│   │   └── SseClient.kt           # Stateless SSE client
│   └── repository/
│       ├── EventReducer.kt         # Central SSE event state management
│       └── ServerRepository.kt     # Server config persistence (DataStore)
├── domain/model/                   # Data classes (Session, Message, Part, etc.)
├── service/
│   └── OpenCodeConnectionService.kt  # Multi-server foreground service
├── di/
│   └── NetworkModule.kt            # Hilt DI
└── ui/
    ├── screens/
    │   ├── home/                   # Server list, connect/disconnect, battery banner
    │   └── webview/                # WebView with auth, file chooser, pull-to-refresh
    ├── navigation/                 # Compose Navigation graph
    └── theme/                      # Material 3 theme
```

## License

MIT
