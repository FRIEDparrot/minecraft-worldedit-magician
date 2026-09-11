# Incremental agent roadmap

This roadmap intentionally keeps each milestone small enough to compile, test, and review independently. It is ordered around safe world interaction rather than attempting all builder features in one change.

## Completed today — operate/context-region foundation

- Add pure, unit-tested `OperateRegion` and `ContextRegion` value types.
- Keep confirmed torch chunks as the only writable `OperateRegion`; unconfirmed orange drafts never enter either agent region.
- Derive the default `ContextRegion` by adding the eight surrounding chunks plus the selected chunk for every operate chunk, and five Y blocks above and below the operating band.
- Enforce the safety invariant in the type API: a context must contain every operate chunk and Y level before a future read tool may use it.
- Bound caller-supplied context margins and materialized chunk count; larger observation needs the later paged read design.

## Completed today — bounded read-only region inspection

- Add a bounded `inspect_region` request/result contract tied to the validated agent scope.
- Summarize loaded context blocks as deterministic palette counts, Y-band density, and block-entity type/position entries.
- Read integrated singleplayer worlds on the server thread; use only the client-visible level on remote servers.
- Expose the manual `/wemc inspect` validation command as the initial player-facing validation slice.

## Completed today — agent-facing observation contract

- Add a provider-independent `wemc-tool` envelope for `inspect_region`.
- Parse and validate bounded numeric limits without letting the model choose a world scope.
- Capture the immutable operate/context scope at Flow start; reject a missing or changed selection before reading.
- Execute the existing live inspection adapter on the correct game thread, then feed its bounded result into the next Flow request.
- Keep tool calls read-only and count their continuation against the existing AI-request budget.

## Completed today — agent-facing directional observation

- Add a bounded `inspect_directional_view` tool for a compact front elevation centered on the captured player position and facing.
- Let the model choose only bounded distance, lateral width, vertical offsets, and sample count; keep coordinates and write authority client-owned.
- Return deterministic per-lateral lanes, Y rows, palette symbols, and explicit unknown/unloaded/out-of-scope counts.
- Execute on the correct game thread and feed the result through the existing FLOW continuation budget.

## Completed today — post-edit verification boundary

- Record the dispatched command batch together with bounded server feedback before requesting another AI step.
- Require a fresh `inspect_region` read of the captured operate/context scope before the agent can propose a repair or next non-terminal batch.
- Reject stale or missing scope and failed verification reads instead of unlocking an unverified repair.
- Keep the change UI-free: verification is automatic and does not add a player decision.

## Next working day — terminal verification and write-boundary validation

- Decide whether terminal `<eof>` batches should also run the same verification before ending the flow, while preserving the one-request budget contract.
- Connect WCL coordinate validation to the confirmed operate Y/chunk bounds before enabling larger autonomous builds.
- Add a bounded retry policy for transient observation failures rather than ending the flow immediately.
- Exercise the checkpoint in a running integrated-server client and verify the exact chat/status presentation.

## Later — build verification loop

- Split large edits into approval-gated batches and collect a screenshot or compact directional block-view summary after each batch.
- Let the agent compare the observed result with its stated plan before proposing a repair batch.
- Add structure-detail tools for inventories, block entities, mobs, and equipment only after block edits, undo, and context reads are stable.

## Future UI direction

The existing settings and command controls remain sufficient for today because this change has no new player decision. The operate/context milestone should add a small mode indicator and explicit toggle in the selection UI; the verification milestone should add a read-only activity card (step, observed result, pending next action) before any image-comparison controls.
