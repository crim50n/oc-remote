# OC Remote v1.4.0 — Release Notes

Major feature release that introduces built-in local server mode on Android and improves provider auth/session/chat UX.

## Highlights

- Added a new Local Server mode in Home so OpenCode can run directly on-device via Termux (setup/start/stop/open sessions from the app UI).
- Implemented Debian-based local runtime bootstrap with stronger install/start reliability, better diagnostics, and `opencode-local` helper commands (`start`, `stop`, `status`, `doctor`).
- Added optional proxy support for local runtime traffic, with automatic bypass for localhost and private LAN ranges.
- Improved provider connect OAuth/device-code flow so browser completion is detected reliably and provider connected state refreshes correctly.
- Added session folder creation from the Sessions screen for faster project setup.
- Improved chat error rendering so backend/provider failures are shown directly in assistant messages instead of appearing as empty bubbles.

## Version

- `versionName`: `1.4.0`
- `versionCode`: `11`
