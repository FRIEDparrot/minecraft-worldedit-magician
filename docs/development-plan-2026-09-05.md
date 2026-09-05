# Development plan — 2026-09-05

## Today: FLOW reliability repair — complete

1. Treat a command batch with no observed game chat response by its query deadline as an explicit observation, then request the next FLOW step instead of failing the operation.
2. Make `FIRST_STEP_ONLY` extended thinking apply only to the initial provider request. Plan approval, command continuations, and WCL repair requests use normal reasoning.
3. Cover the silent-response path and all three later-request paths in state-machine tests.
4. Run the focused FLOW tests and the production build; inspect the diff for state-machine and credential regressions.

No new screen is needed. The existing FLOW UI continues to expose its settings; this change makes its behavior match the setting labels and avoids aborting valid command batches that produce no player-visible feedback.

## Tomorrow: read-only context inspection tool (one vertical slice)

Implement the first live-world agent tool against the confirmed **context** region only:

1. Define a compact `inspect_region` tool request/result schema with a strict size cap.
2. Read a bounded live-world snapshot on the server thread and return block palette counts, height-range summaries, and block-entity type/position summaries — never raw unrestricted NBT.
3. Bind the tool to `AgentRegionScope`: operate chunks remain writable only through the existing approval/command path; neighboring context stays read-only.
4. Add unit tests for schema limits and deterministic summaries, then test the live command path in the dev client before exposing it to FLOW.

The selection torch, HUD, and world overlay already show the default one-chunk/five-block context expansion. Do not add a separate context-selection screen until the inspection result proves what additional control is needed.

## After tomorrow

1. Add staged construction observations: directional block summaries first; screenshots only after explicit user consent and bounded image handling.
2. Extend the draft/approval/undo pipeline for inventories, mobs, and equipment before detailed decoration or redstone automation.
3. Design local-reference-image import and visual comparison with explicit image permission, size limits, and provenance.
4. Treat remote chunk downloads as untrusted input: no automatic execution or self-training. Add source selection, size/type validation, and isolated deserialization before any optional import workflow.
