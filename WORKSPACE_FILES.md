# Workspace file capabilities

Implementation references:
- OpenCode V1 `v1.19.4`, tag commit `148842ef7cb1bc34192b3fff57d0f9721ab9c801`.
- OpenCode V2 `v2.1.4`, commit `68bc26b7da1990ce10ffecfbc1e09eb13aa5d824`.

| Operation | V1 | V2 |
| --- | --- | --- |
| Directory listing / filename search | `/file`, `/find/file` | `/api/fs/list`, `/api/fs/find` |
| File contents | JSON text or Base64 via `/file/content` | Raw stream via `/api/fs/read/*` |
| Download / Open / Share | Contents exposed by the server | Raw streamed download, independent of preview limit |
| Search contents | `/find`, with line navigation | No equivalent filesystem endpoint in the inspected contract |
| Git status / diff | `/vcs/status`, `/vcs/diff`; legacy `/file/status` fallback when status endpoint is missing | `/api/vcs/status`, `/api/vcs/diff` |
| Upload / edit text | No published write endpoint | `/api/experimental/fs/write` |
| Rename / delete server files | No published filesystem operation | No published filesystem operation |

The V1 1.19.4 handler returns Base64 for binary files. Some older V1 releases return an empty binary
placeholder instead, which cannot be downloaded. V1 previews are bounded to 32 MiB transport / 24 Mi
characters retained. APKs bypass preview loading; large Base64 binary downloads decode incrementally
from the JSON stream, independently of those preview limits. This decoder was verified against a live
1.19.3 server by comparing the downloaded APK's SHA-256 with the local artifact.
Project changes support working-tree and branch comparisons on both APIs, and committed-only
comparison on V2. Selecting any changed file opens a read-only patch, including deleted files.
Diff requests use three context lines and bounded responses; large displayed patches are shortened
with a notice, while Copy preserves the full received patch. No Git mutations are performed.
The V1 server can trim text before returning
it, so text downloads are not advertised as byte-exact copies. Some V1 versions return empty lists
from legacy `/file/status` and symbol handlers; the client prefers `/vcs/status` for project changes.

V2 previews retain the 8 MiB limit. Larger files can still be downloaded or opened externally using
a streamed transfer. Android receives a content URI with a read grant and a filename-specific MIME
type; APKs use `application/vnd.android.package-archive`. File opening is explicit, not automatic.

Uploads accept a user-selected document and explicit target path. Existing targets are overwritten
as specified by the API. Text editing is available for previews up to one million characters.
Transfers belong to the file screen's ViewModel and are cancelled if that ViewModel is destroyed;
they are not persistent background workers or resumable transfers. Incomplete SAF downloads are
deleted where the document provider supports deletion.

Downloads and uploads, SAF-provider behavior and APK installer permissions still require physical
device verification. Endpoint/serialization, MIME and streaming regressions have automated coverage.
