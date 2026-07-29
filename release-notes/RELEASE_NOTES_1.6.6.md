# OC Remote v1.6.6 — Release Notes

Patch release focused on provider disconnect consistency with OpenCode Web UI behavior.

## Highlights

- Fixed provider disconnect flow where providers like OpenAI could reappear as connected right after pressing **Disconnect**.
- After removing provider credentials, the app now calls global dispose on the server to refresh instance/provider state before reloading provider lists.
- Keeps providers visible in the available catalog after disconnect (not removed permanently), matching expected Web UI behavior.

## Version

- `versionName`: `1.6.6`
- `versionCode`: `19`
