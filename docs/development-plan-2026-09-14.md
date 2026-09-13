# Development plan — 2026-09-14

## Today — verify terminal FLOW batches

Completed one narrow reliability slice:

1. Keep terminal `<eof>` WCL responses in the `EXECUTING` state after dispatch.
2. Route terminal and non-terminal batches through the same fresh bounded post-edit observation.
3. End a terminal flow only after the observation succeeds; failed or empty verification remains fatal.
4. Preserve the AI request budget because terminal verification does not ask the model for another response.
5. Keep the existing UI and add only a status distinction for final-result monitoring; no new screen or player decision is needed.
6. Add controller coverage for terminal verification and run the full test/build checks.

## Tomorrow — transient observation retry policy

1. Specify which observation failures are transient versus scope/safety failures.
2. Add a small bounded retry/backoff policy without allowing unbounded game-thread work.
3. Define whether retries consume observation attempts, AI requests, or only wall-clock timeout budget.
4. Test delayed and failed reads, including terminal batches, before enabling the policy in the runtime handler.

## Later

- Add opt-in screenshots after textual verification and retries are stable.
- Add inventories, block entities, mobs, and equipment after block edits and rollback are stable.
- Add offline chunk deserialization and remote reference material behind explicit file/network boundaries.
- Add local image import and image comparison after screenshot capture is reliable.
