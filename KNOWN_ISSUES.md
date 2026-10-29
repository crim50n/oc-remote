# Known Issues

Track open bugs here. Remove items once they are fixed and verified on device.

---

## Open

- Phone tab menu used horizontal "to the right" labels in a vertical list (device report).
  It now uses "after this one" / "below"; desktop retains horizontal labels. Device visual
  confirmation pending (`SessionTabContextMenu.kt`, `SessionTabsSheet.kt`).

- Phone volume shortcuts were compared with Termux: Volume Up+Q/K now toggles extra keys instead
  of the Android keyboard, clears held Fn, and releases the hidden panel's viewport space.
  Numeric Ctrl aliases and Ctrl+/ now match Termux. Device/IME verification pending
  (`ChatScreen.kt`, `TerminalPhoneShortcutsTest.kt`).

- Terminal extra-key visibility reset after session tab navigation: it was only rememberSaveable
  state keyed by hardware keyboard presence and absent from ChatViewSnapshot. The explicit choice
  is now saved/restored per session, with keyboard-based defaults only before a choice is made.
  Device navigation verification pending (`ChatScreen.kt`, `SessionWorkspaceRepository.kt`).

- Phone tab cards were oversized and the pin displaced the two-line heading (device feedback).
  Cards now follow Favorites' shape, surface, typography and three-row spacing, with a single-line
  heading and aligned actions. Visual/device verification pending (`SessionTabsSheet.kt:137–210`).

- Terminal toolbar/layout visual correction awaits device verification: the underline-style tabs
  did not match the app (device feedback). PTYs now use compact outlined tabs and stable `[n]`
  labels shared with the phone drawer. The terminal frame is removed,
  and the splitter draws a 1dp line inside a 12dp drag target. Composer width now accounts for the
  splitter (`DesktopTerminalToolbar.kt`, `TerminalPaneDivider.kt`, `ChatScreen.kt`).

- Phone terminal edge interaction was reported to work only alongside text. The edge layer had no
  ordinary tap handler and shared the content's z-order. It now handles tap as well as long-press
  and swipe in a 24dp-wide foreground strip spanning the viewport above the extra keys. Verify
  taps beside both text and empty rows on device (`ChatScreen.kt`, around line 2800).

- Desktop terminal input and split layout are new. Hardware keys honor DECCKM and xterm modifiers;
  paste honors DEC 2004; mouse tracking supports SGR/URXVT/UTF-8 with Shift-local selection and
  scrollback. Legacy text-frame mouse coordinates are limited to ASCII-safe values. Focus routing,
  physical keyboard layouts, TUI mouse interaction, clipboard shortcuts and resizing a docked PTY
  require device/live-TUI verification (`TerminalDesktopProtocol.kt`, `TerminalDesktopInput.kt`,
  `DesktopTerminalToolbar.kt`, `ChatScreen.kt`).

- Follow-up browser corrections need device verification: desktop Undo now has a finite snackbar
  duration; phone tab lists/counts/history and bulk operations are session-only while desktop pages
  remain saved. Home's wide-mode header is removed and About/Add server are in the desktop menu.
  Tab widths now shrink before scrolling, keeping the add button visible (`BrowserTabs.kt`,
  `DesktopTabWidths.kt`, `DesktopSessionTabs.kt`, `NavGraph.kt`, `HomeScreen.kt`).

- Terminal IME prediction was not fully disabled by autoCorrectEnabled=false alone. The terminal
  input connection now requests visible-password/no-suggestions input and disables personalized
  learning and extract/fullscreen IME modes. Verify with the device keyboard (`TerminalKeyboardInput.kt`).

- Page tabs and the desktop tab-search popup are new. Wide-mode app/settings/server/file pages now
  have typed, credential-free tab identities, sharing close/pin/restore with sessions. Legacy saved
  session tabs and closed history are migrated. The left-hand tab search uses an anchored popup;
  settings/about/sync/update headers are hidden when represented by a wide-mode tab. Device
  verification pending (`WorkspaceTab.kt`, `WorkspacePages.kt`, `DesktopTabSearch.kt`).

- Browser workspace changes need device verification: shared pinned-tab ordering and a persisted
  100-entry closed history now back both tab presentations. Context menus support rename/reload,
  pinning and bulk close; mouse middle-click closes and Ctrl+Shift+T restores. The wide layout
  removes the session header and old navigation rail, exposes settings through its session menu,
  and supports a saved full-width chat preference (`BrowserTabs.kt`, `ChatSessionMenu.kt`,
  `DesktopSessionTabs.kt`, `NavGraph.kt`).

- Desktop session tabs are new: wide chat windows use a persistent draggable tab strip backed by
  the same saved tabs as the mobile switcher. Close/undo, existing-session picker, hover tooltips,
  Ctrl+Tab/Ctrl+Shift+Tab and Ctrl+W (reserved while connected terminal input has focus) require device/DeX verification
  (`DesktopSessionTabs.kt`, `NavGraph.kt`, `ChatScreen.kt`).

- Locale override copied the entire Configuration, potentially retaining portrait dimensions after
  rotation with configChanges enabled. It now overrides only locale, and menus use the actual
  visible window frame, including IME/split-screen changes. Previous configuration-based menu
  height fix did not work on device; this replacement still needs device verification
  (`MainActivity.kt:attachBaseContext`, `AppDropdownMenu.kt`).

- Responsive-layout audit changes need device verification: About, category editing, context
  details and upload forms now scroll; dialog heights follow the visible frame and actions wrap.
  File editing allows a single visible line. Short windows use smaller suggestions, attachments,
  a two-line composer and an initially collapsed inbox. Wide windows now use browser-style tabs
  with optional full-width chat (`AdaptiveLayout.kt`, `DesktopSessionTabs.kt`, `AppSurfaces.kt`,
  affected screen roots). Verify rotation, split-screen, keyboard transitions, large fonts and
  restored navigation state. Simultaneous list/detail panes are not implemented.

- Long dropdown menus were clipped on short/landscape screens after introducing the animated
  surface wrapper. The common menu now bounds its surface to the available screen height and
  scrolls content inside that surface. Device verification pending (`AppDropdownMenu.kt`).

- V2 inbox controls are new: confirmed queued user requests expose cancel and queue/steer actions.
  Cancellation checks whether the item was already delivered before marking it cancelled; stale
  snapshots cannot revive the cancelled item. Live V2 and device verification pending
  (`InboxQueueBar.kt`, `ChatViewModel.kt`, `EventReducer.kt`).

- Modern V1 file status returned no changes because `/file/status` is a stub in 1.19.4. Project
  changes now use `/vcs/status` and `/vcs/diff` (V2: `/api/vcs/*`), with read-only patch viewing
  including deleted files. Older V1 status falls back only on missing endpoints. Device
  verification pending (`WorkspaceChangesPane.kt`, `WorkspaceFilesViewModel.kt`, `OpenCodeApi.kt`).

- Automatic sync switch only changed an unsaved form value, so leaving Settings reset it. The
  switch now persists immediately and updates WorkManager without saving or resetting connection
  fields. The remaining form now autosaves after an input pause and flushes on Back; a completed
  save must not overwrite newer edits. Device verification pending (`SyncSettingsScreen.kt`,
  `SyncSettingsViewModel.kt`, `SyncRepository.kt`).

- Tab switcher could retain the chat/search keyboard after moving into the main Activity window.
  Entering the switcher or closing its search now clears focus and hides the keyboard; device
  verification pending (`SessionTabsSheet.kt`).

- Shared snackbars drew an outer border around Material's built-in margin, creating a second
  oversized frame. AppSnackbarHost now uses the content overload with margin outside the border;
  all messages using it receive the fix. Device verification pending (`AppSnackbarHost.kt`).

- Moving tabs from Dialog to the Activity removed the platform window transition. The overlay now
  has explicit fade/scale entrance and exit animations. Session menus also retain their popup until
  fade-out completes, including dismissal during entrance; device verification pending
  (`NavGraph.kt`, `AppDropdownMenu.kt`).

- Native update release-notes screen is new: version-specific GitHub notes are cached for offline
  reading, with a fixed download/install action and progress. Navigation, long Markdown, AMOLED,
  and installer return still need device verification (`ui/screens/update/`, `UpdateRepository.kt`).

- Light-theme tab switcher still showed some white status icons on a light background (device
  report after 1.13.0). Synchronizing dialog InsetsController and legacy flags did not fix it on
  device. The switcher now uses the Activity's own window as a fullscreen overlay instead of a
  floating Dialog; underlying chat system-bar overrides are suspended. Device verification pending
  (`SessionTabsSheet.kt`, `NavGraph.kt`, `ChatScreen.kt`).

- Automatic update discovery only ran when HomeViewModel was created. It now runs on Activity
  resume with a 24-hour completed-check interval, following Telegram standalone; cancelled checks
  do not consume the interval. Device verification pending (`MainActivity.kt`, `UpdateRepository.kt`).

- Tab-close snackbar's long translated action squeezed the message into a narrow column. New
  snackbars place actions below the message and use theme-aware outlined surfaces. AMOLED fixes
  also cover the tab FAB, file tiles, menus, and chat panels. Device verification pending
  (`AppSnackbarHost.kt`, `SessionTabsSheet.kt`, `ChatAcrylic.kt`).

- Back from a chat file link walked parent directories instead of returning to chat. Link-origin
  screens now exit directly on Back (including loading/error states); Folder Up separately opens
  the parent directory within the project. Device verification pending (`WorkspaceFilesScreen.kt`,
  `WorkspaceFilesViewModel.kt`).

- Reading mode hid the session activity row along with the composer. Activity and retry status now
  sit outside the collapsing panel; fullscreen visibility and navigation-bar spacing need device
  verification (`ChatScreen.kt`, bottomBar and SessionActivityRow).

- Opening the debug APK on OpenCode 1.19.3 exceeded the shared preview parser's 24 MiB character
  limit (the live V1 response is about 27 MB of Base64 JSON). APKs now open a binary file card,
  and V1 Base64 downloads decode incrementally outside the preview limits. Device verification
  of save/open/install remains pending (`WorkspaceFilesViewModel.kt`, `V1BinaryDownload.kt`).

- Workspace parent navigation could leave the project and expose raw JSON decoding errors on V1.
  Directory navigation is now normalized to project-relative paths and blocked outside the root;
  V1 listing validates status and payload shape first. Device verification pending
  (`WorkspaceFilesViewModel.kt`, `OpenCodeApi.kt`).

- Inline-code file paths looked like links but had no click handler (including the APK path in chat).
  Recognized paths now receive native Compose link annotations; device verification of tapping and
  selection is pending (`InteractiveMarkdownLinks.kt`).

- Markdown file links reportedly did not open from chat. Inline links now use native Compose
  LinkAnnotation callbacks instead of the renderer's pointerInput(Unit), which captures stale text
  during streaming. Actual file navigation and menu surface fade still need device verification
  (`InteractiveMarkdownLinks.kt`, `AppDropdownMenu.kt`).

- Workspace downloads previously discarded the saved URI and offered no Open action; generic MIME
  also prevented APK installer routing. Downloads now retain content URIs, use specific MIME types,
  grant read permission, and offer Open/Share. V2 transfers stream separately from preview limits;
  upload, cancellation cleanup, SAF providers and APK installation still need device verification
  (`WorkspaceFileActions.kt`, `WorkspaceFilesViewModel.kt`, `OpenCodeV2Api.kt`).

- Leaving fullscreen at the conversation bottom released the scroll anchor after the lower panel's
  animation while the slower upper panel was still changing list padding. Bottom anchoring now runs
  through both animations and their final layout frames, without a second competing scroll command;
  the reported Back gesture still needs device verification (`ChatScreen.kt` around lines 1125-1170).

- Server-file links and expanded workspace previews now target the OpenCode 2.1.4 filesystem
  contract: links resolve on the server, v2 reads outside the project use the file's parent location,
  and fs.find entry objects are decoded correctly. Link navigation, Windows paths, PDF rendering,
  and static HTML previews still need live-server/device verification (`ServerFileLink.kt`,
  `OpenCodeV2Api.kt`, and `ui/screens/files/`).

- Cold-opening a tab on a disconnected server rendered the connection banner under the status
  bar because the chat toolbar depended on sessionLoaded. The toolbar now remains visible before
  loading, and the empty conversation prompt is replaced with connection/loading state until the
  session arrives. Device verification is pending (`ChatScreen.kt` around lines 1800 and 2850).

- Chat panels now use Haze-backed acrylic surfaces and continuous slide/fade on user scrolling.
  Backdrop blur, status-bar contrast, keyboard insets, and frame pacing need device verification;
  unsupported Android versions use a tinted fallback (`ChatAcrylic.kt` and `ReadingMode.kt`).

- Automatic reading mode was blocked at the end of loaded history and defaulted off; tab-switcher
  system bars also appeared black on device instead of matching the surface. The end-of-history
  restriction is removed, unset auto-hide/status-bar options default on, and dialog window colors
  are reapplied after window setup. These device-reported regressions require rechecking
  (`ChatScreen.kt` around line 1100 and `SessionTabsSheet.kt` around line 80).

- Session-tab switching and reading mode are new. Tabs restore lightweight metadata and bounded
  history anchors; fullscreen hides selected system bars and restores them on leaving the screen.
  Cross-server drafts, long-message scroll anchors, IME/insets, accessibility, and terminal return
  still need device verification (`SessionWorkspaceRepository.kt`, `NavGraph.kt`, `ReadingMode.kt`,
  and `ChatScreen.kt`).

- An unreachable host could leave its card connecting without a cancellation action. Connecting
  cards now expose Cancel, which cancels the health-check job or service connection, and health
  checks propagate coroutine cancellation. The actual unreachable-host flow still needs device
  verification (`HomeScreen.kt` around line 1135 and `HomeViewModel.kt` around lines 900-913).

- OpenCode v2 integration is based on the published 2.0.30 contract (PR #45). V1 fallback, scoped
  discovery, cursor pagination, session-owned selections, inbox admission, authoritative SSE
  completion and bounded history/file parsing have regression coverage, but successful generation,
  reconnect recovery, forms and ticketed PTY still need end-to-end device verification
  (`OpenCodeV2Api.kt`, `V2Protocol.kt`, `EventReducer.kt`, and `ChatViewModel.kt`). Complex forms
  require the web client; configuration writes and native MCP OAuth are not exposed on v2.

- The first chat prompt could use a hidden server-default model instead of the model shown by the
  selector (#41). UI, prompts, shell commands, and compaction now share model resolution from
  explicit selection, agent configuration, history, project configuration, and visible models,
  but first-message routing still needs device verification (`ChatModelSelection.kt` and `ChatViewModel.kt`).

- Settings sync previously imported settings, servers, and category assignments through separate
  DataStore edits. Import and upload snapshots are now atomic, but the fix still needs cross-device
  verification (`SyncRepository.kt` around lines 350-375).

- GitHub does not document atomic compare-and-swap support for Gist PATCH requests. Sync verifies the
  observed revision immediately before writing and verifies content afterward, but a concurrent Gist
  writer can still create a narrow lost-update race (`GistSyncTransport.kt` around lines 50-72).

- GitHub rejected the Gist PATCH request in 1.8.0 because its API does not accept the response ETag
  as an `If-Match` precondition (`GistSyncTransport.kt` around line 60). The header has been replaced
  by an explicit revision preflight, but the fix still needs physical-device verification.

- The Favorites shortcut could be inserted above the viewport on app startup because the server list
  rendered before the asynchronous favorites check completed, and `LazyColumn` retained the local
  server as its scroll anchor (`HomeScreen.kt` around line 241). The list now waits for both data
  sources before rendering, but the fix still needs physical-device verification.

- The Stop control disappeared while `SessionStatus.Retry` waited for its next attempt (#30).
  Retry now remains an active, stoppable state and shows its error and countdown, but aborting a
  pending retry still needs physical-device verification (`ChatScreen.kt` around lines 7459-7617).

- Local OpenCode could be persisted and connected twice when startup refreshes overlapped. Server
  creation now uses an atomic normalized-URL upsert, and the connection service rejects a second
  active SSE stream for the same endpoint, but the fix still needs physical-device verification
  (`ServerRepository.kt` and `OpenCodeConnectionService.kt`).

- Settings sync credentials could not be saved on AndroidKeyStore providers that reject a
  caller-provided GCM IV during encryption (`LocalSyncSecretStore.kt` around line 33). The code now
  uses the IV generated by the Keystore cipher, but the fix still needs physical-device verification.

- Settings sync omitted per-server session category assignments, Favorites, offline favorite
  snapshots, global favorite ordering, hidden models, local runtime visibility, and diagnostic log
  level. The payload now includes them and remaps remote server IDs to local IDs by normalized URL,
  while preserving data when importing an older version-1 payload, but the fix still needs
  cross-device verification.

- Settings sync included the `127.0.0.1` Local OpenCode connection even though local runtime and
  proxy configuration is device-specific. New uploads exclude the local server, its password, and
  all associated per-server data; imports also ignore the entry in older payloads, but the fix still
  needs cross-device verification.

- Haptic feedback strength was inconsistent on Samsung because vendor-defined `TICK`, `CLICK`, and
  `HEAVY_CLICK` effects were not monotonic. It now uses explicit duration/amplitude levels, but the
  calibration still needs physical-device verification.

- Terminal extra-key row borders could overlap, the AMOLED drawer border stopped at system insets,
  cursor visibility/blink/style escape sequences were ignored, and terminal resize had redundant
  UI and workspace delays. Borders, DEC cursor behavior, and resize dispatch have been corrected,
  and the terminal panel now has navigation-aware launch guidance, but the complete interaction
  still needs physical-device verification (`ChatScreen.kt` and `TerminalEmulator.kt`).

- Android document providers do not expose a portable atomic compare-and-swap operation. File-based
  settings sync hashes the full content, checks it immediately before writing, and verifies it after
  writing, but a concurrent writer can still create a narrow lost-update race. Persisted access and
  background operation also need verification with Google Drive and other providers
  (`DocumentSyncTransport.kt`).

- Syntax highlighting could apply an adjacent token color to `=` and `-` in fenced code blocks.
  These operators now inherit the base code color, but the rendering fix still needs
  physical-device verification (`SafeMarkdownHighlighting.kt`).

- Running custom subagent cards could not open their child session because a late
  `session.next.tool.called` or `session.next.tool.input.ended` event replaced `metadata.sessionId`
  received from `message.part.updated`. Tool lifecycle updates are now monotonic and preserve title
  and metadata, but parallel custom subagents still need physical-device verification
  (`EventReducer.kt` around lines 385-490).

- A single message containing hundreds of megabytes of tool/file content could exhaust the Android
  heap while Ktor buffered and deserialized a history page; retrying with fewer messages still failed
  when that message remained in the page. History loading now reduces the page size from the response
  `Content-Length` before reading its body, streams accepted pages from disk, caches Base64 image data
  outside the deserialized model, and omits only oversized payload fields when a single message remains
  too large. The threshold is configurable, but the refined fallback and cached image rendering still
  need verification with the reported KoW Bot session (`OpenCodeApi.kt` around lines 515-605).

- Assistant error rendering crashed when a server returned `error.data` as a JSON primitive instead
  of an object. Error messages now accept object and primitive payloads and safely fall back to the
  error name, but the reported provider-error flow still needs physical-device verification
  (`Message.kt` around lines 118-130).

- The share-target session picker could close and immediately reopen repeatedly because pending
  attachments remained set until the destination chat consumed them. Automatic reopening is now
  limited to the explicit wait-for-server-connection flow and blocked once a target session is set,
  but the Android share flow still needs physical-device verification (`NavGraph.kt` around lines 200-290).

- Opening a long session could block the chat until the complete configured history page had been
  transferred and processed (#32). The newest 10 messages are now displayed first and the remainder
  is appended in background pages without reloading known messages, but time-to-first-content and
  scroll stability still need verification on a slow remote connection (`ChatViewModel.kt` around lines 675-760).

- A chat opened from a notification or left open during an SSE disconnect did not show that its
  server was offline, making failed permission replies and other REST actions look misleading. The
  chat now observes the same connection state as Home and displays a persistent disconnected banner,
  but disconnect/reconnect transitions still need physical-device verification (`NavGraph.kt` and
  `ChatScreen.kt` around lines 1640-1960).

- Question prompts reportedly cannot be answered from the Android UI in some remote-server sessions
  (#33), although the question card and matching OpenCode endpoints are present. Release diagnostics
  previously omitted REST reconciliation and reply status details behind `BuildConfig.DEBUG`; these
  events are now persisted without question or answer content, but the root cause still requires a
  diagnostic export captured immediately after reproduction (`SseClient.kt`, `OpenCodeApi.kt`,
  `EventReducer.kt`, and `ChatViewModel.kt`).

- A disconnected server was indicated only in the native chat while session lists, terminal,
  workspace files, WebView, provider/model, and MCP screens still attempted requests and exposed
  network actions. Server-scoped screens now share an explicit disconnected/reconnecting banner,
  preserve cached content in read-only mode, suppress automatic requests, and resume loading after
  reconnect. The terminal also no longer applies the status-bar inset twice below that banner, but
  the complete transition and corrected spacing need physical-device verification (`NavGraph.kt`,
  `ServerConnectionBanner.kt`, `ChatScreen.kt`, and the affected screen ViewModels).

- Settings sync conflicts were visible only after opening Sync Settings and did not explain how the
  local and remote copies differed. Conflicts now persist a secret-free grouped comparison, appear
  on the main Settings screen, and post a notification that opens conflict resolution, but background
  detection, notification permission behavior, and resolution cleanup still need device verification
  (`SyncRepository.kt`, `SyncConflictSummary.kt`, and `SyncConflictNotifier.kt`).

- The model visibility screen briefly showed “Models not found” before the first provider request
  started because the connection-state repository initially emitted an empty connected-server set.
  The empty state now requires a completed provider load, but startup behavior still needs device
  verification (`ServerSettingsViewModel.kt` and `ServerModelFilterScreen.kt`).

- Web fetch tool cards used the translation/languages icon after the Lucide migration. They now use
  the globe icon, but the corrected rendering still needs device verification (`ChatScreen.kt`).

- Settings sync treated any simultaneous local/remote marker change as a whole-copy conflict, even
  when values were semantically equal or different items changed independently. Sync now compares a
  canonical three-way baseline, normalizes server IDs by URL, and merges non-overlapping changes,
  but multi-device convergence and conflict resolution still need physical verification
  (`SyncRepository.kt` and `SyncMerge.kt`).
