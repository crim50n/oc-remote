# OC Remote v1.8.0 - Release Notes

Feature release focused on portable settings, server administration, richer media and model controls, and more reliable active-session recovery.

## Highlights

- Added settings synchronization through either private GitHub Gists or WebDAV for app preferences, server definitions, session categories, and per-server category assignments.
- Added separate retained configuration for each storage, explicit single-storage selection, guarded writes, conflict handling, and read-after-write verification.
- Made synchronized settings, servers, categories, and assignments apply atomically from one DataStore snapshot.
- Added encrypted local credential storage, optional AES-256-GCM protection for synchronized server passwords, manual conflict resolution, forced upload/download, and periodic background sync.
- Added per-server MCP management with live status, connect, disconnect, retry, and OAuth authentication actions.
- Expanded the fullscreen image viewer with pinch zoom, panning, double-tap zoom, Markdown image support, and saving original Markdown images to device storage.
- Improved model selection with search, server-provided provider and variant ordering, a dedicated variant menu, and direct access to model management.
- Replaced fixed haptic presets with adjustable vibration duration and amplitude plus an immediate test action; haptic settings are included in synchronization.
- Kept Stop visible and actionable during network retry waits, added retry error details and a live countdown, and allowed aborting the pending run without waiting for the next attempt (#30).
- Refined terminal recovery with compact reconnect controls that remain readable with large system font sizes.
- Added subtle three-dot active-session indicators using category colors without changing card backgrounds or alignment.
- Fixed duplicate Local OpenCode entries and connection streams with atomic normalized-URL upsert and endpoint-level connection single-flight.
- Fixed the Favorites shortcut appearing above the visible viewport during startup and aligned its card styling with the other Home cards.
- Disabled Material You by default while retaining it as an option, and refined AMOLED surfaces, borders, and accent treatment.
- Fixed Android Keystore compatibility on devices that reject caller-provided GCM initialization vectors.
- Updated all supported localizations for the new settings, synchronization, MCP, retry, and haptic workflows.

## Version

- `versionName`: `1.8.0`
- `versionCode`: `24`
