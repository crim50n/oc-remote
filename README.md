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
│       └── ServerRepository.kt     # Server config persistence (DataStore)
├── domain/model/                   # Data classes (Session, Message, Part, etc.)
├── service/
│   └── OpenCodeConnectionService.kt  # Multi-server foreground service
├── di/
│   └── NetworkModule.kt            # Hilt DI (Ktor, OkHttp, lokit)
└── ui/
    ├── screens/
    │   ├── home/                   # Server list, session list, battery banner
    │   ├── chat/                   # Native chat UI with markdown, streaming, scroll
    │   ├── settings/               # Language/theme selection
    │   └── webview/                # Fallback WebView for "Open in Web" action
    ├── components/                 # Reusable UI components
    ├── navigation/                 # Compose Navigation graph
    └── theme/                      # Material 3 theme (dynamic color support)
```

## Recent Changes

### Draft persistence
- Input text, image attachments, and `@file` mentions saved per session via `DraftRepository`
- Restored automatically when returning to a session — survives navigation, app restart, WebUI detours
- Uses JSON file storage (`session_drafts.json`) with in-memory cache
- `takePersistableUriPermission()` ensures image URIs survive process death

### Session export
- Export full session as a text file with streaming download and progress notification

### UI polish
- **Pulsing dots indicator** — replaced spinning progress across ChatScreen, SessionListScreen, HomeScreen
- **Breathing circle** — animated send button spinner during agent work
- **Compaction divider** — visual separator `——— Context compacted ———` with long-press to revert
- **Context window display** — percentage shown above input area, color-coded by usage level
- **Toolbar subtitle** — always shows total tokens + cost (e.g. `275.2k tokens · $0.042`)
- Paperclip alignment, settings divider removed, keyboard toolbar always visible

### Bug fixes
- **Part.Patch deserialization** — fixed crash: `files` field is `List<String>`, not `List<FilePatch>`
- **Compaction detection** — checks `Part.Compaction` in message parts instead of relying on agent field
- **Notification strings** — all 27+ hardcoded English strings in foreground service converted to `getString(R.string.*)`

### Auto-scroll fixes
- Fixed scroll position during streaming/summarization — now scrolls to bottom of tall messages, not top
- Manual scroll disables auto-scroll; scrolling back to bottom re-enables it
- FAB "scroll to bottom" button appears when auto-scroll is disabled

### UI improvements
- Globe button replaced with dropdown menu (⋮) in chat topbar with 6 actions: Open in Web, New Session, Fork, Compact, Share/Unshare, Rename
- Share/Unshare toggle — button changes based on session share status
- Empty user messages (e.g. from `/compact`) are now hidden
- Fixed red background visible behind user message bubbles (swipe-to-revert background)

### Toolbar layout fixes
- Agent/model/variant area now horizontally scrollable to prevent overflow
- Reduced padding/spacing for compact layout on all languages
- Paperclip button always visible and pinned right

### Pagination & OOM fixes
- Initial load: 50 messages (expandable with "Load earlier messages" button)
- `largeHeap="true"` enables ~512MB heap (was ~256MB)
- Ktor logging reduced from INFO to HEADERS (prevents response body buffering)
- OOM recovery: if initial load fails, retry with halved limit

## License

MIT
