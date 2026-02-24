# OC Remote v1.5.0 — Release Notes

Stability and UX release focused on local runtime reliability, attachment workflows, and AMOLED consistency across core screens.

## Highlights

- Improved local Termux runtime reliability: startup scripts are now hardened against broken runtime caches, proxy environment regressions, and command-path issues; setup now self-refreshes runtime scripts from the repository when they change.
- Expanded local proxy controls: you can now edit custom `NO_PROXY` exclusions directly in the app (defaults are prefilled), and these exclusions are passed to the local runtime on start.
- Improved chat revert behavior to match Web UX: reverting a user message now restores both the draft text and image attachments.
- Added image preview and save flows for both sent images and draft attachments from the input bar.
- Improved shell/tool output UX: bash output blocks are now selectable and include a one-tap copy action that copies the full rendered command + output.
- Improved HTML error handling in chat: long HTML payload errors can be switched between rendered page view and raw code view, with code-first default for better performance on slower devices.
- Fixed terminal keyboard behavior: Caps Lock / symbol-mode lock from mobile IMEs now remains stable while typing in terminal mode.
- Refined AMOLED styling across key surfaces: connection/action states and settings picker dialogs now use AMOLED-appropriate black surfaces, borders, and selected-state visuals for consistent contrast.

## Version

- `versionName`: `1.5.0`
- `versionCode`: `12`
