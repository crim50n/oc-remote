# OpenCode V2 Compatibility

## Contract

The integration adapts PR #45 onto the current application, retaining the Unicode directory,
global-session grouping, and model-selection fixes from #35, #38, and #41.

Reference: OpenCode tag `v2.0.30`, commit `f16ef9ad8878279ea2be3cfab7baeed97aef61aa`.
OpenCode V2 is published as `@opencode/cli`; GitHub's latest V1 release and `opencode-ai`
are not authoritative version sources for the V2 protocol.

## Implemented

- Authenticated V1/V2 detection with bounded discovery responses and confirmed V1 health.
- Location-scoped provider, model, agent, and configuration discovery; directory-aware catalog caches.
- Opaque history/session cursors without conflicting first-page filters on continuation requests.
- Session-owned agent, model, variant, and revert state, separate from legacy message history.
- Durable inbox admission separate from confirmed transcript messages; queued prompts reconstructed
  from REST snapshots, with stale snapshots rejected when lifecycle events race with reconciliation.
- Authoritative text/reasoning completion replacing buffered deltas rather than duplicating them.
- Permissions, basic forms, tool events, export, file browsing, and ticketed PTY connections.
- Unsupported forms remain visible with a web-client action instead of disappearing silently.
- V2 configuration controls and unavailable native MCP authentication are gated.

## Resource Limits

- Catalog transport: up to 64 MiB, projected to bounded retained data rather than a full JSON tree.
- General JSON transport: up to 8 MiB.
- History download: up to 128 MiB on disk; parsing is bounded to 8 Mi characters per literal and
  retained tree, 250,000 nodes, and 128 nesting levels. Oversized tool content can be omitted;
  unrepresentable responses fail safely instead of being allocated without bounds.
- File preview: 8 MiB, including responses without Content-Length.
- Export streams to its destination and is not subject to the history download limit.
- SSE frames retain the existing 1 Mi-character limit; line reads are bounded before allocation.

## Limitations

- V2 does not expose the same configuration-write and sharing operations as V1. These are not
  emulated by silently changing server configuration files.
- Conditional, external, numeric/constrained, and otherwise unsupported forms require the web UI.
- Native MCP OAuth is unavailable on V2. Provider authentication needing additional forms also
  requires the web client.
- Shell and compaction use V2 session semantics, not V1 per-command provider parameters.
- Inbox control items are tracked, but the chat renders only representable pending user prompts.
- Ambiguous prompt delivery is kept pending rather than automatically resubmitted. This prioritizes
  avoiding duplicate agent execution over guessing that an absent transcript entry was rejected.
- Recovery uses REST snapshots, not the experimental durable session log. Large file previews fail
  at the preview limit; a separate unlimited V2 download path is not claimed.

## Validation

Regression fixtures cover protocol detection, endpoints, scoped caches, pagination, projection,
memory limits, forms, SSE/reducer convergence, session selection, inbox races, and pending prompt
reconstruction. Tests are not proof of live-server interoperability.

The following live-server/device checks have not yet been completed:

1. Successful generation and tool output, including project-local agents and non-default models.
2. Multi-page history, revert/redo, and continuation after committing a revert.
3. Disconnect while input is queued, restart/reconnect, and observe exactly one delivered prompt.
4. Answer permissions/basic forms and open unsupported forms in the web client.
5. Connect, resize, disconnect, and reconnect a terminal with a fresh ticket.
6. Repeat core chat and terminal flows against a V1 server.
