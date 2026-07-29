# OC Remote v1.2.1 — Release Notes

Patch release focused on terminal stability, command correctness, and drawer usability.

## Highlights

- Added terminal tab reconnection support:
  - automatic reconnect with exponential backoff for dropped PTY sockets,
  - manual reconnect action for offline tabs in the terminal drawer,
  - reconnect keeps the same tab/runtime instead of creating duplicate tabs.
- Improved terminal resize persistence by retaining pending dimensions while a tab is offline and applying them after reconnect.
- Fixed `/init` command execution to use correct project path context:
  - command names are normalized,
  - session directory is loaded when missing,
  - default path argument is injected for `/init` when not provided.
- Restored blinking terminal cursor using an overlay approach so text selection/copy handles remain stable.
- Polished terminal drawer UI for offline tabs with clearer status and reconnect affordance.
- Improved drawer controls when IME is visible by applying keyboard-aware padding.
- Fixed terminal extra-key clickability regressions by ensuring `ESC`/`TAB` are not blocked by drawer edge gesture layering.

## Version

- `versionName`: `1.2.1`
- `versionCode`: `6`
