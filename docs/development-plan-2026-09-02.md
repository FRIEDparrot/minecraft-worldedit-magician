# Development plan — 2026-09-02

## Today — FLOW thinking-policy repair

Delivered a contained FLOW reliability fix:

1. `FIRST_STEP_ONLY` now enables extended thinking only for the initial agent request.
2. The plan-approval prompt, server-response continuation, and WCL-compilation repair request now send `OFF` rather than charging/latency-expanding every follow-up.
3. Unit coverage exercises all four request paths, including a simulated server-response quiet period.
4. This requires no player-facing UI change: the existing **[STEP1] First step only** setting now matches its label.

## Tomorrow — explicit operate/context torch selection UI

Implement the next player decision point without changing write authorization.

### Interaction design

- Add a rebindable **Context Selection Mode** key. Pressing it switches the torch target between **OPERATE** and **CONTEXT** and prints one concise chat confirmation.
- Keep normal torch use unchanged: it creates/edits a blue writable operate draft in **OPERATE** mode, or a visually distinct read-only context draft in **CONTEXT** mode.
- The HUD must always show: active target, configured key hint, operate chunk/Y bounds, context chunk/Y bounds, and whether the current draft is writable or read-only.
- On context confirmation, validate that the full confirmed operate region is contained before accepting it. If invalid, retain the draft, reject it with the missing-boundary explanation, and offer the default expanded context as the safe fallback.
- The default context remains operate bounds expanded one chunk in X/Z and five blocks above/below. Context controls can never grant write scope; only the confirmed operate region reaches command authorization.

### Acceptance checks

1. Pure tests cover target toggling, default expansion, explicit-context containment rejection, and preserved operate write scope.
2. Compile against 1.21.11 mappings and run the client long enough to verify the key, HUD, blue/orange/read-only colors, and confirmation text.
3. No player input is silently repurposed and no context-only selection can authorize `setblock`, `fill`, or `clone`.

## Following working day — bounded read-only world tool

Add the first structured agent tool: read a live context region on the server thread and return a compact block palette, dimensions, and block-entity summary. Enforce a token/volume budget, state truncation explicitly, and do not expose raw world files or execute downloaded content.

## Later, deliberately separate milestones

1. **Staged construction verification:** split edits into approval-gated batches and emit a compact directional block-view summary after each batch. Screenshot capture remains opt-in and visibly indicated.
2. **Detailed structure data:** add inventories, loot, entities, equipment, and redstone observations only after the read tool, approval gate, and undo trace are stable.
3. **Reference images:** design a local-image picker with explicit file/size consent, then compare images only through a bounded provider request. Do not read arbitrary local paths or fetch/deserialize untrusted schematics automatically.
