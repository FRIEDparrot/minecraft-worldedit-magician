# Development plan — 2026-09-10

## Today — directional observation for local shape understanding

Completed one bounded, read-only vertical slice:

1. Add `inspect_directional_view` as a second provider-independent `wemc-tool` request.
2. Capture the player's current block position and horizontal facing on the client; the model supplies only bounded view dimensions.
3. Render a compact front elevation as per-lateral lanes and Y rows, with a deterministic palette legend.
4. Keep every sampled block inside the immutable context region; report unloaded, out-of-scope, and unsampled cells instead of inventing them.
5. Run integrated-server scans on the server thread and remote-server scans on the client-visible level, matching the existing read-only inspection rule.
6. Feed the result back through the existing FLOW continuation budget and scope-change guard.
7. Add unit coverage for layout projection, deterministic rendering, strict limits, parser integration, and prompt instructions.

No new UI is needed today: this is an agent read tool, and the existing selection HUD continues to show the player-controlled operate/context boundary.

## Tomorrow — post-edit verification boundary

1. Add a post-edit observation checkpoint that records the completed batch and the bounded observation used to evaluate it.
2. Require a fresh observation before the agent can propose a repair batch; do not infer success from command-send acknowledgements alone.
3. Add coordinate validation for generated block edits against the confirmed operate chunk and Y bounds.
4. Design the first approval-gated block-operation draft without enabling broad autonomous construction yet.

## Later

- Add opt-in screenshots as a separate visual context path after the textual observation contract is stable.
- Add structure-detail tools for inventories, block entities, mobs, and equipment after block edits and rollback are stable.
- Add offline chunk deserialization and remote reference material only behind explicit file/network boundaries.
- Add local image import and image comparison after screenshot capture and observation checkpoints are reliable.
