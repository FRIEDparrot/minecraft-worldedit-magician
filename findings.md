# Findings — 2026-09-15 daily development

## Restored repository state
- Repository: `D:/Parrot works/worldedit-magician-1.21.11`
- Active branch: `fix/flow-terminal-verification`, HEAD `6fbc7ac fix(flow): verify terminal batches before completion`.
- It has no configured upstream.
- The only pre-existing worktree modification is `img/tnt-duper.drawio` (4 insertions, 1 deletion); it is out of scope and must remain untouched.
- No prior planning files were present.

## Decision evidence
- Read-only FLOW audit found a controller ordering defect: `completeStepIfReady()` rejects when the AI request limit is reached before issuing the mandatory post-edit observation. This can make terminal WCL batches fail without verification, contradicting the preceding terminal-verification repair.
- A minimal controller-only fix is feasible and testable in `AgentFlowTest`: remove the premature completion-time AI-cap failure while retaining the existing post-observation cap check for non-terminal continuation.
- OpenAI official API-key `/responses` integration already exists. A browser/session/OAuth path is not documented or safely inferable, so it is explicitly excluded from today’s work.

## Acceptance proof targets
1. Terminal `maxAiRequests = 1`: WCL dispatch -> `RequestPostEditObservation` -> valid observation -> `FlowEnded`.
2. Non-terminal `maxAiRequests = 1`: WCL dispatch -> `RequestPostEditObservation` -> valid observation -> `Failed(AI request limit reached)` rather than a further provider request.
3. Existing test suite and build remain green.

## TDD evidence
- Added `AgentFlowTest.AI request limit waits for mandatory post-edit observation` before modifying production code.
- Ran `./gradlew test --tests 'com.magician.worldedit.client.command.AgentFlowTest' --no-daemon`: 41 tests completed; exactly the new regression test failed. The failure is expected because `completeStepIfReady()` returns `Failed` at the current AI-cap check instead of `RequestPostEditObservation`.
- Removed only the premature AI-cap check in `completeStepIfReady()` and preserved the cap check before a non-terminal continuation. Re-ran the focused class: it passed.
- Full `./gradlew test build --no-daemon` succeeded; independent review reported no security or logic issue.
- Committed the two implementation files as `535e3b7 fix(flow): observe batches before enforcing request cap`.

## Delivery blocker
- The required remote push/PR could not be completed: `git push -u origin HEAD` waited for unavailable interactive GitHub authentication and timed out. The remote branch was still absent afterward; GitHub CLI, token environment variables, token file, and noninteractive credential lookup were unavailable/blocked. No retry was attempted with credentials guessed or fabricated.
- The project convention requires appending this lesson to `AGENTS.md`, but the protected-file gate denied the write in this unattended job. The lesson is recorded below instead: do not apply the AI-request cap to a dispatched batch's mandatory post-edit observation; enforce it only for actual provider requests.
