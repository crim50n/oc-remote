# Known Issues

Track open bugs here. Remove items once they are fixed and verified on device.

---

## Open

- Chat does not show `SessionStatus.Retry` details or countdown even though the retry state reaches
  `ChatUiState`; `ChatScreen.kt` treats only `Busy` as active and renders retry details only for
  message-level `Part.Retry` objects (around lines 1526, 1980, and 4758).
