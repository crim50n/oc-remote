# OC Remote v1.13.0 - Release Notes

Feature release introducing session tabs, automatic reading mode, and an expanded workspace file manager.

## Highlights

- **New session tabs:** keep sessions across servers in a fullscreen switcher, search and reorder tabs with a long press, close and restore tabs, and reconnect when opening a disconnected server's tab. Drafts and reading positions are preserved across navigation.
- **New reading mode:** chat panels slide away when scrolling toward newer messages and return on reverse scrolling or Back. Session activity remains visible in fullscreen, with optional hiding of system bars and translucent acrylic panels outside AMOLED mode.
- **Expanded file manager:** switch between a list and a three-column grid with image thumbnails; search filenames and preview text, Markdown, images, PDFs, and static HTML. V1 additionally supports server-provided text search and file-status information.
- Open Markdown and inline-code file links from chat, including line references and relative paths. Back from a chat file link returns directly to chat; Folder Up navigates within the project root.
- Download, open, and share server files with progress and cancellation. Large Base64 APK downloads on compatible V1 servers stream independently of preview limits; APKs use Android's installer MIME type.
- Added streamed uploads and text editing for V2 servers, with filesystem handling updated against OpenCode 2.1.4. Available file actions follow server capabilities.
- Added independent settings to disable Favorites, categories, and session tabs without deleting saved data. These switches participate in settings sync.
- Added an image-compression switch to the session menu; resolution and quality remain in main Settings. Grouped power controls and added icons for reading-mode preferences.
- Improved menu transitions, initial chat loading, toolbar stability, and system-bar handling. The tab-close snackbar now keeps its message separate from the restore action, including long translations.
- Improved AMOLED styling for chat panels, menus, file tiles, tab controls, and snackbars, using black surfaces with contrasting borders.
- Refreshed the README and all supported translations. Large V1 APK decoding was checked against a live OpenCode 1.19.3 server with a matching SHA-256; new Android UI flows and end-to-end V2 behavior still require broader device verification.

## Version

- `versionName`: `1.13.0`
- `versionCode`: `30`
