# Development plan — 2026-09-07

## Today — bounded read-only region inspection

Delivered one independently testable observation slice:

1. Add the `RegionInspectionRequest`/`RegionInspectionResult` contract around an immutable `AgentRegionScope`.
2. Summarize a bounded context snapshot as deterministic block-palette counts, sampled/non-air Y bands, and block-entity type/position entries.
3. Keep explicit caps on scanned blocks, palette entries, and block entities; report unloaded chunks and omitted data.
4. Read an integrated singleplayer `ServerLevel` on its server thread. On remote servers, use only the client-visible level because this remains a client-only mod.
5. Expose the slice through `/wemc inspect` for manual dev-client validation. It is intentionally not wired into FLOW tool calls yet.
6. Add unit coverage for request caps, deterministic output, palette truncation, and entity truncation.

No new screen is needed: inspection is an explicit read-only command and does not introduce a player decision that belongs in the settings UI.

## Tomorrow — agent-facing observation contract

1. Add a provider-independent tool-call envelope for `inspect_region` and a bounded result serializer.
2. Include the current region scope and inspection result in the actual FLOW provider request path, not only the SINGLE system prompt.
3. Keep tool requests read-only and reject requests whose scope is missing, stale, or outside the confirmed operate/context invariant.
4. Add an explicit continuation policy for truncated or partially unloaded observations before allowing the agent to propose edits.

## Later, deliberately separate milestones

1. Add directional block views and staged post-edit observations before opt-in screenshots.
2. Add approval-gated inventory, mob, equipment, and redstone observations only after the block inspection contract is stable.
3. Design local-image import and comparison with explicit file permission, size limits, and provenance.
4. Treat downloaded chunks/schematics as untrusted data: allowlisted sources, size/type validation, isolated deserialization, and no automatic execution or self-training.
