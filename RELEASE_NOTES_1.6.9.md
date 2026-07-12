# OC Remote v1.6.9 - Release Notes

Patch release focused on chat reliability, safer session actions, and server state consistency.

## Highlights

- Added collapsible reasoning blocks that stay compact until opened.
- Replaced accidental horizontal swipe actions with explicit session menus and a long-press message action.
- Fixed new messages disappearing or flashing incomplete history while the initial REST snapshot and SSE stream overlap.
- Prevented duplicate patch cards when the server reports the same cumulative session diff.
- Added guarded Markdown syntax highlighting so malformed highlight ranges fall back to plain code instead of crashing the chat.
- Fixed server settings access for custom providers that do not publish a model list.
- Preserved Busy and Retry states when session events arrive out of order and improved per-server state cleanup.
- Added regression tests for message visibility, patch deduplication, highlighting, provider capability checks, and session event ordering.

## Version

- `versionName`: `1.6.9`
- `versionCode`: `22`
