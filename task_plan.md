# Daily development plan — FLOW verification guard

## Objective
Ship one small, independently testable FLOW reliability repair: a dispatched WCL batch must receive its required post-edit observation even when the AI-request budget is exhausted. This preserves the terminal-batch verification contract and does not add UI.

## Definition of done
- A terminal batch at `maxAiRequests = 1` requests a post-edit observation and ends only after a valid observation.
- A non-terminal batch at the same budget is observed before failing the next-model-request limit.
- Existing request-cap behavior remains enforced before any additional model request.
- Focused and full Gradle tests plus build pass.
- Independent review finds no blocking issue.
- Only intentional files are committed; the pre-existing `img/tnt-duper.drawio` modification is not staged or changed.
- Commit is pushed and a PR against `main` is created or the existing PR is updated and verified.

## Work graph
1. [complete] Restore state and confirm active-branch/PR ownership (active branch is one commit ahead of `origin/main`; GitHub CLI is unavailable, so PR handling will use a git/API fallback).
2. [complete] Read controller and tests; codify a failing regression contract covering terminal and non-terminal AI-cap behavior.
3. [complete] Make the minimal controller change; focused FLOW regression suite is green.
4. [complete] Run full verification and independent code review (full Gradle test/build succeeded; reviewer found no blocking issue).
5. [blocked] Commit only the repair, push, create/update PR, and read it back. Local commit `535e3b7` exists, but GitHub authentication is unavailable in this unattended cron environment.

## Scope boundaries
- Do not implement unsupported ChatGPT website-session/OAuth authentication; official OpenAI API-key support already exists and must not be guessed.
- Do not change context-region UX in this slice; current default expansion is a separate, broader design task.
- Do not alter unrelated working-tree content.

## Errors encountered
| Error | Resolution |
|---|---|
| None yet | — |
