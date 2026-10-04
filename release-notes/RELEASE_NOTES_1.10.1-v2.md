# 1.10.1-v2

- Detect OpenCode V1/V2 APIs per connection, preserving authentication errors and proxy URL prefixes.
- Adapt the released V2 2.0.22 routes, request bodies, response envelopes, and pagination to the native UI.
- Support V2 prompts, attachments, agent/model selection, permissions, basic forms, providers, file browsing, and PTY tickets.
- Render current V2 events and keep queued inputs, text, reasoning, tools, and session patches consistent with history snapshots.
- Preserve history response limits, image caching, and streaming session exports.
- Keep forks independent from subagent sessions and distinguish definitive HTTP prompt failures from uncertain delivery.

V2 API limitations and the debug APK installation identity are documented in README.md.
Validation uses contract fixtures; live server/device validation is not included.
