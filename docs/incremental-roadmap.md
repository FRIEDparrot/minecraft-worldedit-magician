# Incremental agent roadmap

This roadmap intentionally keeps each milestone small enough to compile, test, and review independently. It is ordered around safe world interaction rather than attempting all builder features in one change.

## Today — FLOW reliability

- Fix plan-only approval so the prompt sent after approval can be received and compiled as the next WCL step.
- Count only dispatched AI requests against the configured request limit.
- Preserve those guarantees with state-machine tests and correct the command reference.

## Next working day — explicit operate and context regions

- Introduce pure, testable `OperateRegion` and `ContextRegion` value types.
- Require every operate region to be contained in its context region.
- Default a context region to the operate chunks expanded by one chunk in X/Z and by five blocks below and above the selected Y range.
- Extend the torch selection interaction to choose whether the player is editing the operate region or the wider context region, with clear action-bar feedback before changing any command guard.

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
