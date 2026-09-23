# Plan — terminal FLOW verification

## Goal
Finish one narrow FLOW reliability slice: terminal `<eof>` WCL batches must receive the same bounded post-edit observation as non-terminal batches before the flow is reported complete. Do not combine retry policy or screenshots with this change.

## Today
- [complete] Confirm current terminal state transition and test baseline.
- [complete] Add a failing controller test proving terminal batches enter the verification checkpoint.
- [complete] Implement terminal verification while preserving non-terminal continuation behavior.
- [complete] Update the runtime handler and FLOW documentation.
- [complete] Run focused tests, full test suite, and build; review the diff without touching the user's unrelated drawio edit.
- [blocked] Push and create a PR against `main`; no GitHub token is available and the configured Git credential-manager push timed out.

## Tomorrow
- Design a bounded retry policy for transient observation failures (retry count, backoff, and request-budget interaction) as a separate slice.
- Add runtime/integrated-server coverage for delayed and failed observation reads.
- Do not add screenshots or structure-detail tools until verification retries are stable.

## Findings
- The controller previously marked WCL responses containing `<eof>` as `COMPLETED` immediately, and the client handler finished the flow immediately after dispatch.
- Non-terminal WCL batches already transition through `EXECUTING` → `AWAITING_POST_EDIT_OBSERVATION` → continuation.
- Terminal batches now use the same checkpoint and end only after a non-empty observation; no additional AI request is reserved.
- `img/tnt-duper.drawio` has a pre-existing user modification and remains unstaged.

## Errors encountered
| Error | Attempt | Resolution |
|---|---:|---|
| Gradle baseline test exceeded the tool runner timeout | 1 | Used the focused AgentFlowTest invocation, which completed successfully after the test-first change. |
| Initial multi-file documentation patch referenced a file that did not exist | 1 | Created the dated development-plan file, then applied the existing-file edits separately. |
| GitHub publish fallback had no available token; credential-manager push timed out | 1 | Kept the commit local and verified the branch is not present on `origin`; PR creation remains blocked by authentication. |

## Verification record
- Baseline: initial all-tests invocation timed out in the runner; focused test then passed.
- Focused tests: `./gradlew test --tests com.magician.worldedit.client.command.AgentFlowTest --no-daemon --console=plain` — passed.
- Full tests: `./gradlew test --no-daemon --console=plain` — passed.
- Build: `./gradlew build --no-daemon --console=plain` — passed.
- Diff check: `git diff --check` — passed.
- PR: blocked — branch `fix/flow-terminal-verification` is local at commit `fb64cac`; `origin` has no matching branch.
