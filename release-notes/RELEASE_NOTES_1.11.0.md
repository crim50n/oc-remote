# OC Remote v1.11.0 - Release Notes

Feature release focused on memory-saving tool cards and smarter settings synchronization across devices.

## Highlights

- Added a Hide tool details setting that shows lightweight tool headers without rendering command output, file listings, diffs, or tool attachments to reduce chat memory use. Navigation to child-agent sessions remains available.
- Added automatic merging of independent settings sync changes across preferences, remote servers, categories, assignments, Favorites, and hidden models.
- Avoided repeated conflicts for equivalent configurations with different local server IDs or older payload defaults.
- Updated conflict resolution so choosing local or remote values preserves unrelated changes from both copies, including the selected ordering for conflicting lists.
- Protected local changes made during synchronization and handled server, category, assignment, and encrypted-password deletions more safely.
- Preserved values omitted by older sync files and published their updated representation for subsequent synchronization.
- Added a separate encrypted-password conflict group without exposing password values, and bounded encrypted-data parameters before decryption.
- Updated all supported localizations for the new tool-card setting and smarter sync conflict explanations.

## Version

- `versionName`: `1.11.0`
- `versionCode`: `28`
