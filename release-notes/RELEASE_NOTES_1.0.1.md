# OC Remote v1.0.1 — Release Notes

Bug fix release focused on multi-project session visibility and UI improvements.

---

## Multi-Project Session Visibility

- Sessions from all server projects are now loaded and displayed — previously only sessions from the server's working directory were visible
- Each session shows its project path with `~` substitution
- Flat session list sorted by last update time across all projects — no more grouping that breaks chronological order

## Revert Populates Input Field

- Reverting a user message now restores the original text to the input field, allowing quick re-editing and re-sending
- Works for both swipe-to-revert and undo via menu

## Notification Deep-Link Fixes

- Fixed cold-start deep-link navigation — tapping a notification now reliably opens the correct session
- Added fallback to raw session ID when session path is not yet available in the event store

## Bug Fixes

- Fixed initial message count race condition — message loading now correctly waits for DataStore preference read
- Fixed `/init` command sending wrong directory to server
