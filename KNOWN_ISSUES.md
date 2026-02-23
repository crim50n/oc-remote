# Known Issues — opencode-android Terminal

Track open terminal bugs here. Remove items once they are fixed and verified on device.

---

## Open

- Local runtime start gets stuck in `Starting` and then fails when Termux `allow-external-apps` is disabled. Root cause: Termux rejects `RunCommandService` until user enables `allow-external-apps = true` in `~/.termux/termux.properties` and fully restarts Termux. Pointer: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/home/HomeViewModel.kt`.
- In AMOLED theme, markdown links inside user message bubbles can become nearly invisible (e.g., setup URL line in `curl` command). Root cause: markdown `linkText` uses `onPrimaryContainer` for user messages even when the bubble background is black in AMOLED mode. Pointer: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`.
- Local PTY terminal fails on Termux + proot Alpine with `gnu_get_libc_version: symbol not found` from `bun-pty`, so local terminal cannot open even when server is running. Root cause: `bun-pty` native library expects glibc symbols while Alpine uses musl. Pointer: `scripts/opencode-local-setup.sh` and `app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt`.
- After successful browser OAuth (shows "you can close this page"), provider dialog can remain open until manually cancelled. Root cause hypothesis: provider list refresh is not retriggered reliably on app resume for all OAuth methods, so connected state is not observed in time. Pointer: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/server/ServerProvidersScreen.kt`.
- Cloudflare/WAF HTML error pages in chat can render as mostly empty blue blocks (only plain text before first tag is visible). Root cause: markdown renderer interprets raw HTML tags and hides unsupported DOM content instead of showing literal source. Pointer: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/chat/ChatScreen.kt`.
