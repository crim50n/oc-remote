# oc-remote 1.2.0

This release delivers a major terminal-mode overhaul focused on Termux-like behavior, PTY reliability, and AMOLED UI consistency.

## Highlights

- Reworked terminal mode to use a full-screen terminal flow with server-scoped tabs and drawer-based session switching.
- Fixed PTY resize behavior by using the correct server update path, improving row/column updates during IME and viewport changes.
- Added terminal WebSocket PTY transport and lifecycle handling for improved interactive shell responsiveness.
- Improved TUI rendering reliability (including full-grid output behavior) for apps like `mc`.
- Updated terminal input handling with mobile-friendly modifier support (Ctrl/Alt latch and volume-key virtual modifiers).
- Brought drawer and terminal surfaces closer to AMOLED styling, including cleaner panel framing and dark-theme consistency.
- Added shell-mode support controls in chat settings and input flow.

## Version

- `versionName`: `1.2.0`
- `versionCode`: `5`
