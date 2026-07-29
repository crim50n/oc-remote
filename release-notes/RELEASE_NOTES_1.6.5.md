# OC Remote v1.6.5 — Release Notes

Patch release focused on local runtime reliability, authentication, and startup controls.

## Highlights

- Added local runtime username support (`OPENCODE_SERVER_USERNAME`) with optional input: leave username empty to use server default (`opencode`).
- Added local launch mode toggle to run Termux start command in foreground or background directly from app settings.
- Auto-start is now restricted to background mode to avoid inaccessible foreground task launches.
- Fixed local runtime health detection when server auth is enabled by sending authenticated health checks from the app.
- Improved Termux setup/start scripts with safer auth env export behavior, automatic script refresh on start, and clearer runtime error diagnostics.

## Version

- `versionName`: `1.6.5`
- `versionCode`: `18`
