# OC Remote v1.6.4 — Release Notes

Patch release focused on release pipeline stability and localization consistency.

## Highlights

- Fixed release build failures in GitHub Actions caused by stale locale-only keys (`settings_show_shell_button*`) that no longer exist in default strings.
- Cleaned localized `values-*` resources to remove extra translations and restore successful lint-vital checks for release APK builds.
- Keeps the 1.6.3 chat/review bubble fixes intact while ensuring tag-based publication succeeds.

## Version

- `versionName`: `1.6.4`
- `versionCode`: `17`
