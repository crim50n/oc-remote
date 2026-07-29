# OC Remote v1.6.0 — Release Notes

Feature and reliability release focused on local runtime launch controls, terminal usability, and runtime script robustness.

## Highlights

- Added comprehensive **local runtime launch options** in the app: LAN bind (`0.0.0.0`), server password, auto-start on app launch, startup timeout, and proxy/`NO_PROXY` controls.
- Improved **terminal interaction quality**: better follow/scrollback behavior, more reliable selection while scrolling, corrected cursor viewport positioning, and cleaner copied output without padded trailing spaces.
- Expanded **image attachment optimization controls**: added a keep-original-resolution mode (convert/compress only), updated max-side options, and clearer optimization summaries in chat.
- Refined **local runtime notifications** to reduce false positives and duplicates by notifying only when meaningful assistant output is ready.
- Hardened **Termux local setup/runtime scripts** end-to-end: better self-update flow, runtime script refresh behavior, install/repair/uninstall tooling, and fixed hostname propagation so LAN mode reliably binds as configured.
- Refreshed localization packs via lokit after English source-string updates.

## Version

- `versionName`: `1.6.0`
- `versionCode`: `13`
