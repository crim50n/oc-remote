# OC Remote v1.9.0 - Release Notes

Feature release focused on workspace access, portable file-based settings sync, faster large-session loading, and more capable terminal and session workflows.

## Highlights

- Added a workspace file browser with folder navigation, file-type icons, ignored-file indicators, syntax-highlighted text previews, formatted or raw Markdown, image previews, word wrapping, and downloads through Android's document picker.
- Added settings synchronization through files selected from compatible Android document providers such as Google Drive, alongside GitHub Gist and WebDAV, with retained access for manual and background sync.
- Made long chats usable sooner by showing the newest 10 messages first and loading the configured history target in background pages without blocking initial content.
- Added a configurable history response memory limit, disk-backed response processing, cached Base64 message images, adaptive page sizing, and safe omission of oversized payload fields to improve stability with very large sessions.
- Added drag-and-drop Favorites reordering with correct ordering across category filters and offline sessions.
- Improved terminal mode with panel-opening guidance, repeating arrow keys, corrected extra-key borders and insets, faster resizing, and support for server-controlled cursor visibility, blinking, and shape.
- Added a persistent disconnected-server banner to open chats so failed actions are not mistaken for accepted requests.
- Fixed custom subagent cards losing their child-session links when late tool events arrived, and made tool lifecycle updates preserve completed states and metadata.
- Fixed reverted messages and queued prompts reappearing after stale SSE or REST updates.
- Fixed the Android share-target session picker reopening repeatedly after a destination was selected.
- Fixed provider errors crashing message rendering when the server returned primitive error data, and corrected syntax coloring for punctuation in fenced code blocks.
- Expanded privacy-safe question diagnostics with pending-state transitions, REST reconciliation counts, and reply/reject status codes to support investigation of issue #33 without recording question or answer content.
- Updated all supported localizations for the new workspace, sync, terminal, connection, and history controls.

## Version

- `versionName`: `1.9.0`
- `versionCode`: `26`
