# Development plan — 2026-08-31

## Today: official OpenAI request path

Deliver a narrow, independently testable connection improvement:

1. Make the built-in official OpenAI provider use the current Responses endpoint for ordinary chat, not only when hosted web search or image input is enabled.
2. Preserve OpenAI-compatible Chat Completions behavior for Custom and the other compatible providers.
3. Cover the exact endpoint, auth header, request shape, and response-decoder flag with unit tests.
4. Run the focused test, full test suite, and a production build; then inspect the diff for credential leakage and provider regressions.

No new screen is required: this is a transparent correction to the existing **OpenAI** provider. The existing API-key and model fields remain its interaction surface.

## Tomorrow: FLOW reliability repair

Fix the `FIRST_STEP_ONLY` thinking policy. `AgentFlowController.thinkingModeForStep()` currently returns the configured mode unchanged, so follow-up requests still ask the provider for extended thinking. Add state-machine tests for the first request, continuations, plan approval, and WCL-repair requests before changing the controller.

## Next: context-region interaction

Build on the merged bounded-region foundation:

1. Add torch-driven context-region selection controls.
2. Default the context region to the operating region expanded by one chunk on X/Z and five blocks vertically on each side.
3. Enforce that every operate chunk is included in context, show both boundaries in the existing overlay, and add limit/boundary tests.

## Later milestones

1. Implement a read-only chunk/world tool over the live-world backend, with structured summaries rather than raw unrestricted NBT.
2. Add staged construction observations (directional block summary first, screenshot capture after explicit user consent) so FLOW can verify builds safely.
3. Add structured inventories, mobs, and equipment to the draft/approval/undo pipeline before allowing detailed decoration or redstone automation.
4. Design local-reference-image import and visual-comparison tools only after the image-data permission and size limits are explicit.
