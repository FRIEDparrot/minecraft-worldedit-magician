# Development plan — 2026-09-20

## Today — make mandatory FLOW verification resilient

Completed one bounded reliability slice:

1. Retry one failed **post-edit read** after a fixed 1-second, non-blocking delay.
2. Keep the controller at the mandatory post-edit checkpoint while waiting; do not resend WCL commands, consume an AI request, or consume another server step.
3. Keep selection/dimension changes and blank inspection results fatal so that a stale or empty observation never unlocks a repair command.
4. Stop the flow after the single retry fails; no unbounded retry loop is allowed.
5. Keep the UI unchanged: this is an automatic, safe read retry, with concise chat feedback rather than a new screen or approval prompt.
6. Add controller regression coverage and run focused, full, and build verification before review/PR publication.

## Tomorrow — test the runtime boundary

1. Run the Fabric development client against an integrated server with a confirmed region.
2. Exercise terminal and non-terminal one-block FLOW batches; confirm the completed edit is observed before final completion or continuation.
3. Induce or simulate a failed inspection read and confirm exactly one retry, no duplicate command dispatch, and safe flow termination after the second failure.
4. Exercise a selection or dimension change during verification and confirm it remains fatal without retrying against a changed scope.

## Next bounded slice — official OpenAI transport verification

The official OpenAI Responses path is already present. Add a hermetic local HTTP-fixture test for its `/responses` request construction, bearer authorization, success decoding, non-2xx presentation, and timeout handling. Keep this separate from FLOW retry behavior and do not send a real request or require a player API key.

## Later

- Expand context-region controls from the existing default one-chunk horizontal / five-block vertical margin only after runtime scope tests are stable.
- Add screenshot or directional-view checkpoints after textual post-edit verification is proven in the development client.
- Add detailed structures, inventories, mobs, equipment, redstone I/O, and tick-control tools behind structured calls and approval/undo boundaries.
- Add offline chunk deserialization and local image comparison only behind explicit file/network and world-open/world-closed backend boundaries.
