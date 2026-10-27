# OC Remote v1.14.0 - Release Notes

Feature release adding native release notes, more reliable update discovery on app return, and smoother session navigation.

## Highlights

- **New What's new screen:** read release notes directly from the update card or About, with the version, publication date, compact Markdown, and a pinned download/install button.
- Keep reading release notes while the APK downloads, with download progress shown on the same screen. Loaded notes are cached for offline access; unavailable notes can be retried without blocking installation.
- Release notes are matched to the exact release version while preserving APK verification metadata. Android's installer is launched only from the active screen.
- Automatic update checks now run when the app returns to the foreground, following a 24-hour interval between completed checks. Manual checks bypass the interval, and cancelled checks no longer consume it. Completed network failures still count toward the interval.
- The fullscreen session-tab switcher now shares the main app window instead of using a separate dialog, addressing light-theme system-bar conflicts. Underlying chat and terminal system-bar overrides pause while tabs are visible.
- Added explicit fade and scale transitions when opening and closing the tab switcher.
- Improved session-menu dismissal: the popup remains until its fade-out completes, including when closed during its entrance animation; selected actions run afterward.
- Updated all supported translations and the README. UI regression tests are included and compile, but system-bar appearance and animations still need broader Android-device verification.

## Version

- `versionName`: `1.14.0`
- `versionCode`: `31`
