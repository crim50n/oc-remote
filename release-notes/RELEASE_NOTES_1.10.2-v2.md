# 1.10.2-v2

- Fix an out-of-memory crash reproduced on an Android 16 device after connecting to a V2 server.
- Stream V2 responses and retain only the catalog/configuration fields used by the UI.
- Bound response bytes, JSON nesting, scalar size, retained text, and object counts before large allocations.
- Share provider/model catalogs between screens, with synchronized invalidation after authentication changes.
- Add regression coverage for oversized ignored metadata, parsing limits, and concurrent catalog loads.
- Add a physical-device navigation smoke test that preserves settings and sanitizes failure output.

Installation, launch, API activity, and automated navigation passed on a physical
Android 16 device. The live Qwen 3.7 Flash test reached prompt admission and history,
but generation failed with an insufficient quota/credits error from the server.
The full live prompt/response test is therefore partial.
