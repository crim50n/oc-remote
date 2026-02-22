# OC Remote v1.3.0 — Release Notes

Minor release focused on terminal reliability, server management quality-of-life, and keyboard/input compatibility.

## Highlights

- Reworked terminal rendering for TUI-heavy workflows:
  - switched to pixel-aligned canvas rendering for cleaner fixed-grid output,
  - improved box-drawing alignment and removed visible row seams/gaps,
  - enforced consistent black terminal background and updated overlay/status-bar behavior in terminal mode.
- Improved terminal session stability:
  - fixed first-tab resize race conditions by synchronizing session directory loading,
  - aligned PTY resize updates with tab directory context,
  - tightened initial tab/open behavior and terminal tab handling for session-scoped flows.
- Added better hardware-keyboard compatibility in terminal mode:
  - printable key forwarding from hardware events,
  - support for Enter/Backspace/Tab and Ctrl+A..Z paths,
  - improved remote-control keyboard input behavior.
- Added terminal font-size preference plumbing:
  - terminal default font size is now configurable in settings,
  - pinch-to-zoom behavior and active terminal font state are better integrated.
- Expanded server configuration UX:
  - added per-server auto-connect toggle,
  - added startup auto-connect for configured servers,
  - made server name optional and auto-derived it from URL when empty.
- Removed sessions-list direct terminal launch entry to avoid unsupported non-project terminal startup path.

## Version

- `versionName`: `1.3.0`
- `versionCode`: `8`
