# Incremental agent roadmap

This roadmap intentionally keeps each milestone small enough to compile, test, and review independently. It is ordered around safe world interaction rather than attempting all builder features in one change.

## Completed today — operate/context-region foundation

- Add pure, unit-tested `OperateRegion` and `ContextRegion` value types.
- Keep confirmed torch chunks as the only writable `OperateRegion`; unconfirmed orange drafts never enter either agent region.
- Derive the default `ContextRegion` by adding the eight surrounding chunks plus the selected chunk for every operate chunk, and five Y blocks above and below the operating band.
- Enforce the safety invariant in the type API: a context must contain every operate chunk and Y level before a future read tool may use it.
- Bound caller-supplied context margins and materialized chunk count; larger observation needs the later paged read design.

## Next working day — explicit operate and context selection interaction

- Add a torch target toggle between **OPERATE** and **CONTEXT**. The HUD must render the active target and show the operate region in blue, the read-only context in a visually distinct color, and drafts in orange.
- Keep the default context derived until the player explicitly chooses CONTEXT; when a context draft is confirmed, reject it with actionable feedback unless it contains the full operate region.
- Use a dedicated, rebindable key rather than overloading the existing operation/shape controls. The HUD hint must state the key and whether the displayed volume is writable or read-only.
- Do not change command authorization in this UI change: only the operate region remains a write guard.

## Then — safe world observation for the agent

- Add a live-world read tool that returns a compact, bounded block palette and block-entity summary from the context region.
- Enforce a token/volume cap and report truncation rather than silently dropping data.
- Keep chunk reads server-thread-only and do not modify region files while a world is open.
- Do not make the agent download or execute arbitrary web content; any future build-reference importer must use an allowlisted, user-visible source and deserialize untrusted data defensively.

## Later — build verification loop

- Split large edits into approval-gated batches and collect a screenshot or compact directional block-view summary after each batch.
- Let the agent compare the observed result with its stated plan before proposing a repair batch.
- Add structure-detail tools for inventories, block entities, mobs, and equipment only after block edits, undo, and context reads are stable.

## Future UI direction

The existing settings and command controls remain sufficient for today because this change has no new player decision. The operate/context milestone should add a small mode indicator and explicit toggle in the selection UI; the verification milestone should add a read-only activity card (step, observed result, pending next action) before any image-comparison controls.
