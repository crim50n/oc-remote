# OpenCode Android v1.0.0 — Release Notes

Native Android client for [OpenCode](https://github.com/anomalyco/opencode) servers — full-featured Material 3 app with real-time streaming and multi-server support.

---

## Native Chat UI

- Markdown rendering with syntax highlighting, code blocks, and tables (mikepenz/multiplatform-markdown-renderer)
- Real-time message streaming via SSE events
- Smart auto-scroll — disables on manual scroll up, re-enables when scrolled to bottom, FAB to jump down
- `@file` mentions with fuzzy search completion
- Image and PDF attachments — pick from gallery or receive via Android share target from other apps
- Slash commands — `/new`, `/fork`, `/compact`, `/share`, `/rename`, `/undo`, `/redo`
- Server-side and MCP commands — automatically discovered and available in command palette
- Swipe user messages to revert (with confirmation dialog), revert banner with tap-to-redo
- Copy assistant responses to clipboard
- Edge-to-edge display with transparent system bars

### Reasoning Blocks

- Dedicated rendering for agent "Thinking" blocks with accent border and italic text

### Tool Output Cards

Specialized renderers for each tool type matching WebUI behavior:

- **Edit / Write** — file editing with diff display
- **Bash** — command execution output
- **Read** — file content display
- **Glob / Grep** — search results
- **Task** — sub-agent task display
- **TodoList** — task list rendering
- **Patch** — diff/patch display
- **File** — file attachment cards
- **Image** — horizontal thumbnail row for generated images

All tool cards are expandable/collapsible with a "steps" toggle:
- Summary labels ("Making edits", "Running commands", "Searching codebase")
- Pulsing dots animation while tools are running
- Configurable auto-expand via settings

### Visual Polish

- Pulsing dots indicator and breathing circle animation for send button
- Compaction divider with long-press to revert
- Context window percentage above input area, color-coded (normal < 70%, warning 70–90%, critical > 90%)
- Toolbar subtitle showing total tokens and cost

---

## Session Management

- Multi-session — switch between sessions, view history
- Sessions grouped by project directory with collapsible groups
- Session status indicators — pulsing dots for "Working", error dots for "Retrying"
- Diff summary (`+N/-N`) displayed in session list rows
- Session actions via dropdown menu — New Session, Fork, Compact, Share/Unshare, Rename, Open in Web
- Swipe gestures in session list — swipe right to rename, swipe left to delete (with confirmation)
- Load older messages — pagination with configurable initial count (25–200)
- Session export — export full session as JSON file with streaming progress notification
- Draft persistence — input text, image attachments, and @file mentions saved per session; survives navigation, app restart, and WebUI detours
- OOM protection — `largeHeap`, reduced logging, pagination prevents crashes on huge sessions

### Open Project / Directory Browser

- Browse server filesystem to create sessions in arbitrary project directories
- Quick-start dialog showing recently used project directories
- Search directories with debounce, breadcrumb navigation

---

## Model & Agent Selection

- Model picker — select provider and model
- Variant cycling — cycle through model variants (thinking effort levels) with dedicated toolbar button
- Agent toggle — switch between Build/Plan agents
- Token usage — total tokens and cost displayed in toolbar subtitle
- Context window — percentage display above input, color-coded by usage level
- Horizontally scrollable toolbar prevents overflow on long translations

---

## Multi-Server Connection

- Connect to multiple OpenCode servers simultaneously
- SSE event stream — real-time session status, permissions, questions
- Auto-reconnect with configurable exponential backoff (aggressive/normal/conservative)
- Foreground service with WakeLock keeps connections alive in background
- Server health check on connect
- Server management — add, edit, delete servers with URL validation and normalization
- Connection error display inline on server cards
- Battery optimization detection — warning banner with "Fix" button when battery saver may kill the service

### Notifications

- **Task completion** — notify when agent finishes a task (regular or silent channel)
- **Permission requests** — high-priority notification when a tool needs file permission (vibration + lights)
- **Agent questions** — notification when the agent asks a question
- **Session errors** — notification on session errors
- Notifications grouped per-server with summary
- InboxStyle persistent notification showing per-server connection status
- Notification watchdog — auto-restores foreground notification if dismissed
- Deep-link navigation — tapping a notification opens the specific session's chat screen (works on cold and warm start)

### Permission & Question Handling

- Interactive permission cards with Allow/Deny/Always actions
- Interactive question cards for agent questions
- Notification fallback when app is in background

---

## Settings

14 settings organized into 5 sections:

**General:**
- Language selection (13 languages + system default)
- Reconnect mode — aggressive (1–5s), normal (1–30s), conservative (1–60s)

**Appearance:**
- Theme — light, dark, system default
- Dynamic colors — Material You (Android 12+)
- AMOLED dark mode — pure black background for OLED screens

**Chat Display:**
- Chat font size — small, medium, large (scales markdown body and code blocks)
- Compact messages — reduce spacing between messages
- Code word wrap — toggle horizontal scrolling in code blocks and tool outputs
- Auto-expand tool results — show tool cards expanded by default

**Chat Behavior:**
- Initial message count — 25, 50, 100, or 200
- Confirm before send — optional confirmation dialog
- Haptic feedback — vibrate on send and revert (API 30+ CONFIRM, older CONTEXT_CLICK)
- Keep screen on — prevent screen timeout while chat screen is open

**Notifications:**
- Task completion notifications on/off
- Silent notifications — suppress sound and vibration

---

## Localization

13 languages with automatic translation via lokit:

| Language | Code |
| --- | --- |
| English | `en` (source) |
| Russian | `ru` |
| German | `de` |
| Spanish | `es` |
| French | `fr` |
| Italian | `it` |
| Portuguese (BR) | `pt-BR` |
| Japanese | `ja` |
| Korean | `ko` |
| Chinese (Simplified) | `zh-CN` |
| Ukrainian | `uk` |
| Turkish | `tr` |
| Arabic | `ar` |
| Polish | `pl` |

---

## WebView Fallback

- "Open in Web" action in chat dropdown menu opens the full OpenCode WebUI for the current session
- Pull-to-refresh, file upload support, in-WebView navigation

