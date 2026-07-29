# OC Remote v1.7.0 - Release Notes

Feature release focused on cross-server session organization, reliable chat and terminal workflows, broader file support, diagnostics, and current OpenCode compatibility.

## Highlights

- Added persistent cross-server Favorites with global ordering, offline snapshots, connect prompts, and consistent session cards.
- Added reusable session categories with custom names, colors, icons, per-session assignment, category filters, and animated Busy accents.
- Added project-aware session browsing with search, optional project grouping, branch badges, expandable sections, project paths, and project-scoped new chats.
- Expanded Android sharing to accept one or multiple supported files and added a destination picker that prioritizes Favorites, preserves their global order, and displays categories for connected sessions.
- Added device attachments for images, PDFs, text, source code, and configuration files with MIME and size validation.
- Made outgoing prompts durable and visible immediately while delivery is confirmed; prompts not accepted by the server are removed from the local queue and restored to the composer instead of remaining stuck.
- Unified pending permissions and questions into an ordered queue with position indicators, retry feedback, parent-chat routing, and confirmation before permanent approval.
- Improved OpenCode event compatibility for streamed reasoning, tool progress, shell events, workspace state, model and agent updates, and tool attachments.
- Added detailed context usage with input, output, reasoning, cache, session totals, remaining capacity, message count, and cost.
- Improved reasoning and Markdown with descriptive elapsed-time titles, optional auto-expand, GFM parsing, task markers, tables, multilingual tilde handling, and code-copy actions.
- Improved tool, patch, edit, attachment, and session cards with compact output, copy actions, file-change summaries, completed attachments, unified metadata, and pulsing activity accents.
- Fixed streaming auto-scroll, recovered older history around reverts, and added a local Reload session action.
- Upgraded terminal interaction with inertial scrollback, smoother pinch zoom, Samsung/Gboard-safe input, shared rendering metrics, and improved accessibility.
- Added explicit terminal tab states for starting, reconnecting, disconnected, and exited sessions; transient failures reconnect the existing PTY while exited sessions can restart in the same tab.
- Added a privacy-sanitized Diagnostics screen with severity filters, bounded persistent storage, crash capture, copy, file sharing, and confirmation-protected clearing.
- Added secure in-app GitHub Release updates with automatic discovery, manual checks from About, verified downloads, progress, and handoff to Android's system installer.
- Improved reconnect recovery, notification navigation, Busy/Idle accuracy, and Stop behavior while reducing background preload work, battery use, and memory pressure on large multi-server installations.
- Made child and subagent sessions safely read-only while preserving history, context visibility, and tool-driven navigation.
- Improved provider OAuth errors and local runtime setup with clearer server details, localhost-first defaults, LAN/auth and proxy controls, background launch, auto-start, and timeout handling.
- Standardized dialogs, menus, cards, buttons, pickers, terminal controls, and chat surfaces across light, dark, dynamic, and AMOLED themes.
- Updated all supported localizations for the new workflows and interface text.

## Version

- `versionName`: `1.7.0`
- `versionCode`: `23`
