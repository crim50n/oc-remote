# Known Issues

Track open terminal bugs here. Remove items once they are fixed and verified on device.

---

## Open

- Local runtime start gets stuck in `Starting` and then fails when Termux `allow-external-apps` is disabled. Root cause: Termux rejects `RunCommandService` until user enables `allow-external-apps = true` in `~/.termux/termux.properties` and fully restarts Termux. Pointer: `app/src/main/kotlin/dev/minios/ocremote/ui/screens/home/HomeViewModel.kt`.
