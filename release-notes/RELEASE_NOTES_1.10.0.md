# OC Remote v1.10.0 - Release Notes

Feature release focused on resilient offline behavior, remote server organization, clearer settings sync conflicts, and a refreshed icon system.

## Highlights

- Added long-press drag-and-drop ordering for remote server cards, with the order preserved locally and through settings sync while Local OpenCode remains fixed.
- Added consistent disconnected and reconnecting banners across chat, sessions, terminal, workspace files, WebView, providers, models, MCP, and server settings.
- Preserved cached server content in read-only mode while offline, paused unavailable network actions, and resumed loading after reconnection.
- Added persistent settings sync conflict warnings, notifications that open conflict resolution, and grouped comparisons of local and remote settings, servers, categories, assignments, Favorites, and hidden models.
- Added a configurable diagnostic export limit for the newest 100, 250, 500, or 1,000 privacy-sanitized messages.
- Replaced the app's Material Extended icons with Lucide icons, added explicit RTL handling for directional controls, and expanded session categories from 10 to 32 icon choices.
- Fixed the model visibility screen briefly showing an empty state before its first provider request completed.
- Fixed stale server session data remaining after changing a server identity or deleting it, while retaining useful cached data through ordinary disconnects.
- Fixed terminal reconnect and resize work continuing while its server was unavailable, and corrected disconnected-banner spacing in terminal mode.
- Fixed Web fetch cards using a translation icon instead of a globe, along with other semantic icon corrections across chat and settings.
- Updated all supported localizations for sync conflict notifications and comparisons, diagnostic export controls, and connection states.

## Version

- `versionName`: `1.10.0`
- `versionCode`: `27`
