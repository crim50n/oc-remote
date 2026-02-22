# OC Remote v1.2.2 — Release Notes

Patch release focused on terminal access, copy/paste UX, and link correctness.

## Highlights

- Added a terminal action on the sessions screen top bar:
  - opens the latest session directly in terminal mode,
  - creates a new session and opens it in terminal mode when no sessions exist.
- Added terminal-mode deep-link/navigation support with explicit `openTerminal` route argument.
- Improved terminal text selection context actions by wiring a paste action into the selection toolbar.
- Kept `Ctrl+Alt+V` terminal paste behavior and unified clipboard sanitization path.
- Updated About screen repository URL to the current GitHub location across all localizations.

## Version

- `versionName`: `1.2.2`
- `versionCode`: `7`
