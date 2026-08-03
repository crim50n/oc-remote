# OC Remote v1.8.1 - Release Notes

Hotfix release focused on reliable and complete settings synchronization.

## Highlights

- Fixed GitHub Gist uploads failing with HTTP 400 by replacing the unsupported conditional PATCH header with a revision preflight and retaining read-after-write verification.
- Added Favorites, global favorite ordering, offline favorite snapshots, hidden models, local runtime visibility, and diagnostic log level to settings synchronization.
- Preserved local data when importing older version-1 payloads that do not contain the newly synchronized fields.
- Kept Local OpenCode device-specific by excluding its localhost connection, password, categories, Favorites, snapshots, and hidden models from uploads and by ignoring Local OpenCode entries in older payloads during import.
- Continued remapping synchronized per-server data to local server IDs by normalized URL so it remains portable across devices.

## Version

- `versionName`: `1.8.1`
- `versionCode`: `25`
