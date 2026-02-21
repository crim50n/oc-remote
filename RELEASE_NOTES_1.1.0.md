# OC Remote v1.1.0 — Release Notes

Feature release focused on server settings parity with the official OpenCode web interface.

---

## Server Settings Structure

- Added a dedicated server settings section with separate entries for `Providers` and `Models`
- Removed server settings controls from the chat session header to keep session UI focused on chat-only controls
- Updated naming from `Model filter` to `Models` for consistency with web terminology

## Providers Management

- Implemented provider connect/disconnect flow (instead of simple enable toggles)
- Added API key auth and OAuth connect flows in server settings
- Added provider auth methods loading with API key fallback when provider auth metadata is missing
- Improved connected/available provider sectioning to better align with web behavior

## Models and Defaults

- Added model visibility controls per provider/model in server settings
- Added defaults management for `Default model`, `Small model`, and `Default agent`
- Switched defaults read/write to global config endpoints to fix defaults not persisting correctly
- Updated default model picker style to match the session model picker structure and AMOLED visuals

## AMOLED UI Alignment

- Unified true-black surfaces and border styling across new server settings screens and dialogs
- Updated switches and cards in settings/model screens for consistent AMOLED rendering
