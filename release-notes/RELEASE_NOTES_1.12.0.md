# OC Remote v1.12.0 - Release Notes

Feature release adding initial OpenCode V2 compatibility, more reliable project and model handling, and cancellable connection attempts.

## Highlights

- Added automatic OpenCode V1/V2 detection and initial V2 support targeting the published `@opencode/cli` 2.0.30 API contract, while retaining V1 support.
- Added V2 discovery, native chat and tool streaming, permissions, basic forms, session actions, workspace file browsing, export, and ticketed terminal connections.
- Preserved V2 session-owned model, agent, and variant state, separated queued input from confirmed messages, and added REST reconciliation for session and inbox recovery.
- Fixed V2 cursor pagination and duplicated text after authoritative stream-completion events. Bounded catalog, history, file-preview, and event processing reduces the risk of out-of-memory crashes.
- Added a web-client fallback for unsupported forms and disabled unavailable V2 configuration controls and native MCP authentication.
- Fixed opening projects with Chinese, Cyrillic, emoji, and other non-ASCII directory names, including Windows paths (#35).
- Fixed global-project sessions collapsing into one group; sessions now match real project roots or separate directories, with Windows and UNC path handling (#38).
- Fixed first-message model selection, immediate selection-to-send races, project-scoped model defaults, and selection from reverted history (#41).
- Added cancellation for connection attempts to unreachable hosts. The connecting indicator remains above Cancel; connected cards retain Sessions above Disconnect.
- Updated supported translations and documented V2 compatibility and resource limits.
- V2 support is new and has automated regression coverage, but live generation, reconnect recovery, forms, and PTY still require end-to-end Android-device verification. V2 session sharing and configuration writes are unavailable; complex forms require the web client.

## Version

- `versionName`: `1.12.0`
- `versionCode`: `29`
