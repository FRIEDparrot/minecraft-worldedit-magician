# Development plan — 2026-09-09

## Today — expose bounded region inspection to Flow

Completed one vertical slice:

1. Add a provider-independent `wemc-tool` response envelope.
2. Support the `inspect_region` request with strict caps for blocks, palette entries, block entities, and height bands.
3. Capture the operate/context scope when Flow starts; the model supplies limits only, never coordinates or write authority.
4. Reject missing or changed selections before reading.
5. Run the existing live inspection adapter on the integrated-server or client-visible world thread as appropriate.
6. Feed the deterministic, truncation-aware inspection result back into the next Flow request.
7. Add parser, cap, scope, and request-budget tests.

No new UI is needed: this is a read-only agent interaction and the existing selection HUD remains the player-facing scope indicator.

## Tomorrow — directional observation and safer execution

1. Add a compact directional block-view read tool for local shape understanding.
2. Add a post-edit observation checkpoint before the agent may propose a repair batch.
3. Add coordinate validation against the confirmed operate chunk/Y bounds before enabling autonomous block-edit batches.
4. Keep screenshots opt-in and separate from the textual observation contract.

## Deliberately deferred

- Inventory, mob, equipment, and redstone-specific observations.
- Downloading or deserializing arbitrary online chunks/schematics.
- Local image import and image comparison.
- Full autonomous construction and undo/rollback.
