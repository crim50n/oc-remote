# OC Remote v1.15.0 - Release Notes

Feature release introducing a browser-style workspace for wide windows, a dockable terminal, project diffs, and OpenCode V2 request-queue controls, with refinements for phone navigation and input.

## Highlights

- **New browser workspace:** wide windows show sessions and app pages in one tab strip, including settings, server tools, and files. Search tabs from a compact popup, pin and reorder them, use mouse context menus and middle-click closing, and restore from a saved history of up to 100 closed tabs. Keyboard shortcuts support switching, closing, and reopening tabs when terminal input does not own focus.
- **New side-by-side terminal:** dock a terminal beside chat, resize the split, or expand the terminal. Directly selectable PTY tabs use compact numbered labels and close controls. Hardware keyboard input, terminal mouse tracking, local selection, clipboard shortcuts, zoom, and bracketed paste improve tablet and DeX workflows.
- **New project change viewer:** inspect working-tree and branch diffs on compatible V1 and V2 servers, plus committed-only diffs on V2. View patches for deleted files, colored additions/deletions, line wrapping, pinch zoom, and copy actions. Modern V1 servers use the VCS endpoints instead of the legacy empty file-status response.
- **New V2 queue controls:** see queued user requests, cancel pending requests, or change delivery between queue and steer. Cancellation accounts for requests already delivered to the conversation.
- Phone tab cards now follow Favorites' typography, spacing, and surfaces. Pin indicators and action buttons are aligned, and vertical menus use “after this one” and “below.” Phone tab lists and history show sessions only, while desktop page tabs remain saved for wide mode.
- Terminal extra-key visibility is remembered per session. Phone Volume Up + Q/K now toggles this panel like Termux; additional Ctrl aliases complete the standard phone volume-key mappings. Phone edge taps can open the terminal drawer, and terminal input requests no keyboard prediction or autocorrection.
- Wide chat can use a saved, synchronized full-width preference. Improved short-window layouts, visible-window sizing after rotation, scrollable dialogs, keyboard-aware menus, and snackbar borders and dismissal.
- Sync connection settings now save automatically after editing, and the automatic-sync switch persists immediately without a separate Save action.
- Updated the README and all supported translations. Automated tests, lint, and debug builds were checked; Android UI tests compile, while broader device/DeX and live V2 verification remain pending.

## Version

- `versionName`: `1.15.0`
- `versionCode`: `32`
