# OpenCode Android

Android client for [OpenCode](https://github.com/anomalyco/opencode) servers with native UI and full feature parity.

**This is an unofficial community project, not affiliated with the OpenCode team.**

## Features

### Native UI
- **Full chat interface** — native Material 3 UI with markdown rendering (code blocks, tables, syntax highlighting)
- **Message streaming** — real-time text streaming with auto-scroll
- **Smart scroll behavior** — manual scroll disables auto-scroll; automatically re-enables when scrolled to bottom
- **File mentions** — `@file` completion with fuzzy search
- **Image support** — inline base64 images in chat
- **Tool outputs** — expandable tool call results with syntax highlighting
- **Slash commands** — `/new`, `/fork`, `/compact`, `/share`, `/rename`, `/undo`, `/redo`
- **Swipe to revert** — swipe user messages to undo (with confirmation dialog)

### Session Management  
- **Multi-session** — switch between sessions, view history
- **Session actions** — create, fork, compact, share/unshare, rename via dropdown menu
- **Load older messages** — pagination for large sessions (initial 50, expandable)
- **OOM protection** — `largeHeap` enabled, reduced logging, pagination prevents crashes on huge sessions
- **Session export** — export full session as text file with streaming progress notification
- **Draft persistence** — input text, image attachments, and @file mentions saved per session; survives navigation, app restart, and WebUI detours

### Model & Agent Selection
- **Model picker** — select provider and model with variant support
- **Agent toggle** — switch between Build/Plan agents
- **Token usage** — displays total tokens and cost in toolbar subtitle
- **Context window** — percentage display above input, color-coded (normal < 70%, warning 70-90%, critical > 90%)
- **Compact layout** — horizontally scrollable toolbar prevents overflow on long translations

### Localization
- **13 languages** — English (source), Russian, German, Spanish, French, Italian, Portuguese (BR), Japanese, Korean, Chinese (Simplified), Ukrainian, Turkish, Arabic, Polish
- **Auto-translation** — lokit integration for automatic string translation
- **Settings** — language and theme selection in Settings screen

### Settings
- **Dynamic colors** — Material You dynamic color support (Android 12+)
- **Chat font size** — small, medium, or large text in chat messages and code blocks
- **Code word wrap** — toggle horizontal scrolling vs. word wrap in code blocks and tool outputs
- **Notifications** — toggle task completion notifications
- **Auto-accept permissions** — automatically approve tool permission requests
- **Initial message count** — configure how many messages to load per session (10–200)
- **Confirm before send** — optional confirmation dialog before sending messages

### Connection
- **Multi-server** — connect to multiple OpenCode servers simultaneously
- **SSE event stream** — real-time session status, permissions, questions
- **Auto-reconnect** — exponential backoff (1s to 30s)
- **Background service** — foreground service keeps connections alive when app is minimized

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
# Build debug APK
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

```
dev.minios.ocremote/
├── MainActivity.kt                 # Single activity, edge-to-edge, deep-links
├── OpenCodeApp.kt                  # Hilt application
├── data/
│   ├── api/
│   │   ├── OpenCodeApi.kt          # Stateless REST client (Ktor/OkHttp)
│   │   └── SseClient.kt            # SSE client for event streaming
│   └── repository/
│       ├── DraftRepository.kt      # Per-session draft persistence (text, images, @files)
│       ├── EventReducer.kt         # Central SSE event state management
│       ├── ServerRepository.kt     # Server config persistence (DataStore)
│       └── SettingsRepository.kt   # App settings persistence (DataStore)
├── domain/model/                   # Data classes (Session, Message, Part, etc.)
├── service/
│   └── OpenCodeConnectionService.kt  # Multi-server foreground service
├── di/
│   └── NetworkModule.kt            # Hilt DI (Ktor, OkHttp, lokit)
└── ui/
    ├── screens/
    │   ├── home/                   # Server list, session list, battery banner
    │   ├── chat/                   # Native chat UI with markdown, streaming, scroll
    │   ├── settings/               # Settings (appearance, chat, behavior)
    │   └── webview/                # Fallback WebView for "Open in Web" action
    ├── components/                 # Reusable UI components
    ├── navigation/                 # Compose Navigation graph
    └── theme/                      # Material 3 theme (dynamic color support)
```

## Recent Changes

### Settings
- 7 new settings: dynamic colors, chat font size, code word wrap, notifications toggle, auto-accept permissions, initial message count, confirm before send
- Settings screen reorganized into Appearance, Chat, and Behavior sections
- Font size scales both markdown body text and code blocks
- Code word wrap toggles horizontal scrolling in all tool output cards and diff views

### Draft persistence
- Input text, image attachments, and `@file` mentions saved per session
- Restored automatically when returning to a session — survives navigation and app restart

### Session export
- Export full session as a text file with progress notification

### UI polish
- Pulsing dots indicator and breathing circle animation for send button
- Compaction divider with long-press to revert
- Context window percentage above input area, color-coded by usage level
- Toolbar subtitle always shows total tokens + cost

### Session management
- Dropdown menu (⋮) in chat topbar: Open in Web, New Session, Fork, Compact, Share/Unshare, Rename
- Horizontally scrollable agent/model/variant toolbar
- Pagination with "Load earlier messages" button
- Smart auto-scroll with manual override and FAB to re-enable

## License

MIT
