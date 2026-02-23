# OC Remote v1.4.0 — Release Notes

Feature release focused on local on-device runtime stability, provider auth reliability, and Home screen UX polish.

## Highlights

- Reworked local runtime setup around Debian in Termux, with a more reliable installer flow, mirror selection, and improved startup diagnostics.
- Added `opencode-local` helper commands (`start`, `stop`, `status`, `doctor`) and optional proxy support for local runtime with private-network bypass defaults.
- Improved Providers connect flow for OpenAI and other OAuth methods so browser authorization completes more reliably and connected state refreshes correctly.
- Refined Home local runtime controls (sessions-first actions), improved empty-state layout behavior, and updated local runtime strings/translations.
- Improved chat error rendering so provider failures are visible directly in assistant responses instead of appearing as empty bubbles.

## Version

- `versionName`: `1.4.0`
- `versionCode`: `11`
