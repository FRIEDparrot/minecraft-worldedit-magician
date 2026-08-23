# worldedit-magician — Documentation

> A Fabric client-side mod (Minecraft 1.21.11, Fabric Loader 0.19.x, Java 21, Kotlin 2.4.10) that turns a torch into a chunk-selection tool and pipes your natural-language requests to a configurable AI agent that replies with curated, vanilla-Minecraft commands. The mod sends every accepted command through your active server connection after category-permission and chunk/Y-range validation.

| Topic | Where to look |
|---|---|
| Selection torch controls | [§1 Selection tool](#1-selection-tool) |
| All `/wemc …` and `/worldeditmagician …` subcommands | [§2 Commands](#2-commands) |
| The agent protocol: `wemc-commands`, `wemc-plan`, `<eof>`, WCL | [§3 Agent protocol](#3-agent-protocol) |
| Operation modes (SINGLE vs FLOW) | [§4 Operation modes](#4-operation-modes) |
| Approval modes and the pending queue | [§5 Approval & execution](#5-approval--execution) |
| The whitelisted command manifest | [§6 Whitelisted commands](#6-whitelisted-commands) |
| Chunk / Y-range guard | [§7 Selection guard](#7-selection-guard) |
| Settings UI (`/wemc config`) | [§8 Settings screens](#8-settings-screens) |
| AI providers & models | [§9 Providers & models](#9-providers--models) |
| **Debugging command responses** | [§10 Debugging](#10-debugging) |
| Persistence / config files | [§11 Persistence](#11-persistence) |
| Keybindings | [§12 Keybindings](#12-keybindings) |
| Compatibility / WorldEdit | [§13 Compatibility](#13-compatibility) |

---

## 1. Selection tool

The standard **torch** in your main hand is the chunk-selection tool. While you hold it:

- A small WEMC panel is rendered on the HUD (top-left, above the hotbar) with the current operation, shape, Y range, and chunk count.
- The world chunk under your cursor is highlighted with a colored outline (blue = confirmed, orange = pending draft).
- A mixin on `MouseHandler.onScroll` lets the wheel drive selection corner movement and Y-range adjustments while you hold the torch.

### 1.1 Default keybindings

> All selection keybindings require **Ctrl** as a modifier. Holding the torch alone is not enough.

| Default key | Action | See |
|---|---|---|
| `Ctrl + C` | Cycle the **operation** mode: `REPLACE` → `ADD` → `REMOVE` → `REPLACE` | [§1.2](#12-operation-modes) |
| `Ctrl + V` | Toggle the **shape** between `SINGLE` chunk and `AREA` (two corners) | [§1.3](#13-shape-modes) |
| `Delete` (Ctrl-only) | Cancel the current draft only | [§1.4](#14-cancel--clear) |
| `Ctrl + Shift + Delete` | Cancel the draft **and** clear every confirmed chunk | [§1.4](#14-cancel--clear) |
| `Ctrl + Mouse Wheel` | Move the complete Y-range band up/down | [§1.5](#15-y-range-adjustments) |
| `Ctrl + Shift + Mouse Wheel` | Move the upper bound of the Y range | [§1.5](#15-y-range-adjustments) |
| `Ctrl + Alt + Mouse Wheel` | Move the lower bound of the Y range | [§1.5](#15-y-range-adjustments) |

### 1.2 Operation modes

```
REPLACE  ─►  ADD  ─►  REMOVE  ─►  REPLACE
```

- **REPLACE** — confirming a draft replaces the entire current selection.
- **ADD** — confirming a draft **adds** those chunks to the current selection.
- **REMOVE** — confirming a draft **removes** those chunks from the current selection.

Changing the operation (Ctrl+C) discards any unfinished draft. The operation is captured at confirmation time.

### 1.3 Shape modes

- **SINGLE** — every `Ctrl+left-click` directly stages that chunk.
- **AREA** — the first `Ctrl+left-click` anchors corner 1; the second `Ctrl+left-click` anchors corner 2 and computes the bounding rectangle. While awaiting the second corner, the mouse wheel moves corner 2 one chunk at a time in the direction you are facing.

Changing the shape (Ctrl+V) also discards any unfinished draft.

### 1.4 Cancel & clear

| Action | Effect |
|---|---|
| `Delete` (Ctrl-only) | Discard the in-progress draft (orange chunks). Confirmed chunks are kept. |
| `Ctrl + Shift + Delete` | Discard the draft **and** clear every confirmed chunk (returns to zero selection). |

Right-click with the torch is the **confirm** action — it never cancels. Pressing `Delete` is the only way to cancel a draft.

### 1.5 Y-range adjustments

The Y range controls which Y levels are part of the selection volume for block-count estimation and the chunk guard. Defaults come from the first selected block: `minY = block Y`, `maxY = block Y + 20`, clamped to world build limits.

- `Ctrl + Wheel` → shift the whole band up/down by 1 block at a time.
- `Ctrl + Shift + Wheel` → upper bound only.
- `Ctrl + Alt + Wheel` → lower bound only.

The range is inclusive on both ends. Volume = `chunks × 16 × 16 × (maxY − minY + 1)`. Block-editing commands must fall inside this range.

---

## 2. Commands

All commands are registered via Fabric's client-command API (`ClientCommandRegistrationCallback`). They are **client-side only**; they do not need server permissions to type.

### 2.1 `/wemc …`

> `/wemc` with no arguments opens the settings panel — same as `/wemc config`.

#### Configuration & status

| Command | Effect |
|---|---|
| `/wemc` or `/wemc config` | Open the unified settings panel (`WemcConfigPanelScreen`) |
| `/wemc status` | Print active provider, model, reasoning effort, context window, max-output tokens, and approval mode |
| `/wemc operation single` | Switch the agent to **SINGLE** mode (one AI request per `/wemc chat`) |
| `/wemc operation flow` | Switch the agent to **FLOW** mode (default; bounded multi-step loop) |
| `/wemc operation status` | Show operation mode and current limits |
| `/wemc approval ask` | Approval mode = **ASK** (commands are queued, you run them manually) |
| `/wemc approval approve` | Approval mode = **APPROVE** (commands execute as soon as the agent emits them) |

#### AI provider & model

| Command | Effect |
|---|---|
| `/wemc provider list` | List every `AiProvider`, marking the active one |
| `/wemc provider use <id>` | Activate a provider (`openai`, `ollama`, `claude`, `gemini`, `deepseek`, `minimax`, `minimax_cn`, `xai`, `mistral`, `cohere`, `perplexity`, `azure`, `custom`, `copilot`). The saved model for that provider is restored, never another provider's. |
| `/wemc model list` | Fetch the active provider's model catalog from its API and print it |
| `/wemc model use <provider:model>` | Pick a model from the catalog. The argument is a single `provider:model` token (the model field is greedy, so quote it if it contains spaces). |

#### Chat

| Command | Effect |
|---|---|
| `/wemc chat <prompt…>` | Send `<prompt>` to the active provider. In FLOW mode this **starts** a bounded multi-step flow; in SINGLE mode it sends exactly one request. |

#### Debug commands (`/wemc command …`, `/wemc agent …`, `/wemc query …`)

| Command | Effect |
|---|---|
| `/wemc command list` | List the page-1 whitelist (10 enabled commands per page) |
| `/wemc command list <n>` | Jump to page *n* of the whitelist |
| `/wemc command history` | Print every command WEMC has actually sent to the server this session, newest first |
| `/wemc agent commands` | List the whitelist plus any pending batch queued for approval |
| `/wemc agent run` | Send the queued (pending) batch through the executor now |
| `/wemc agent discard` | Throw the pending batch away |
| `/wemc query time` | Print a hint message about the vanilla `/time query` syntax. The actual query logic (`TimeQueryHandler`) is not wired to this literal — use `/time query daytime` yourself. |
| `/wemc query entity` | Print a hint message pointing you to `/data get entity …` (vanilla Java has no `/entity query`). The actual query logic (`EntityQueryHandler`) is not wired to this literal. |

> `/wemc run` is registered as a branch literal but has **no** `.executes` handler. Typing it currently does nothing. Use `/wemc agent run` instead.

#### FLOW-mode controls

| Command | Effect |
|---|---|
| `/wemc flow approve` | Accept the plan the agent proposed; the controller now prompts the agent for its first executable step |
| `/wemc flow cancel` | Abort the current flow (also fires if no flow is active — prints "No active flow.") |
| `/wemc flow status` | Print whether a flow is active and how to interact with it |

### 2.2 `/worldeditmagician …`

A legacy alias kept for backward compatibility:

| Command | Effect |
|---|---|
| `/worldeditmagician config` | Same as `/wemc` |
| `/worldeditmagician worldedit` | Open the WorldEdit installation status screen |

---

## 3. Agent protocol

WEMC does not free-form the model. Every prompt the agent sees already contains:

1. The full whitelisted command manifest, grouped by category.
2. A `Player state:` block with the player's position, rotation, looking-at block, current chunk, selection mode/operation, Y range, and selected-chunk count (`PlayerStateContext.currentPlayerState()`).
3. Mode-specific instructions (`AgentStepPlanningPrompt.instructions`).

The agent replies in one of the formats below.

### 3.1 `wemc-commands` (raw command list)

The transport used by the live client. The agent emits **one Minecraft command per line**:

````
```wemc-commands
time set noon
```
````

- The block is parsed by `MinecraftCommandWhitelist.extractAgentSequence`.
- Every command is validated against the manifest, normalized (no leading `/`, no embedded newlines), and sent through `MinecraftCommandExecutor.execute`.
- A batch is capped at **100 commands** (`MAX_SEQUENCE_LENGTH`); larger work must be planned as a separate flow.
- Block-changing commands (`setblock`, `fill`, `clone`, block-targeted `data`/`item` edits) must pass the [chunk/Y-range guard](#7-selection-guard).
- The transport block itself is stripped from the chat message the player sees — `AgentResponsePresentation.displayText`.

### 3.2 `wemc-plan` (FLOW mode)

When a flow needs more than one server step, the agent first declares the plan. The block uses `key: value` lines:

````
```wemc-plan
steps: 3
requires-flow: true
reason: Need to clear the area before building the foundation.
current-step: 1
```
````

- `steps` — total number of agent turns the flow will need (must be ≥ 1).
- `requires-flow` — must be `true` if `steps > 1`, `false` if `steps == 1`.
- `reason` — required when `requires-flow: true`.
- `current-step` — optional, defaults to `1`; must be in `1..steps`.

The parser is in `AgentStepPlanParser`. A plan-only response transitions the controller to `AWAITING_PLAN_APPROVAL`; you accept with `/wemc flow approve` or reject with `/wemc flow cancel`.

**Plan with bundled commands** — If the agent's response contains both `wemc-plan` AND `wemc-commands` (the first step's commands), the commands are **held pending** until you approve. On approval the first batch executes immediately without waiting for another agent round-trip. If you reject, the held commands are discarded.

### 3.3 `<eof>` (end of flow)

A line containing **only** `<eof>` (optional whitespace) tells the FLOW controller that the next `wemc-commands` block is the last one. The flow ends after it executes.

In **plan mode** (`wemc-plan`): the controller strips `<eof>` before displaying the plan text, so you only see the plan description without the `<eof>` marker.

### 3.4 `wemc` (WCL — partial implementation)

````
```wemc
// WCL source code here
```
````

The FLOW parser recognises this block as `FlowParseResult.WclSource` and the controller maps it to `AgentFlowAction.WclReady` / `WclCompilationFailed`, but **the live client (`WorldeditMagicianClient.handleFlowAction`) does not yet wire a compiler** — it falls through the `else -> { }` branch. The grammar, lexer, parser, type-checker, and compiler are described in `docs/wcl-spec.md` and the test `AgentFlowTest.`FlowResponseParser parses wemc block as WclSource`` proves the parser path works. Treat the `wemc` block as **planned but not yet executable** in the live client; use `wemc-commands` for now.

### 3.5 Plain text / empty

Anything else ends the flow. In SINGLE mode a plain-text reply never executes anything — only `wemc-commands` blocks can ship commands.

### 3.6 Hard limits (FLOW mode)

| Limit | Default | Cap |
|---|---|---|
| Max AI requests per flow | 30 | 30 |
| Max server steps per flow | 50 | 50 |
| Query timeout per step | 8 s (× 2 with extended thinking) | 20 s |
| Response quiet window | 500 ms after last game message | — |

If a limit is exceeded the flow fails with a chat message naming the limit.

---

## 4. Operation modes

### 4.1 SINGLE

- Exactly one AI request per `/wemc chat`.
- The reply can include `wemc-commands`; they go straight to [§5 Approval & execution](#5-approval--execution).
- No automatic continuation: if the agent needs the result of its commands to plan the next step, you must switch to FLOW mode. The agent is told this in its mode-specific instructions.

### 4.2 FLOW (default)

- The agent controls how many turns it takes (`wemc-plan` declares the step count; up to 30).
- Plans are gated by **your** `/wemc flow approve` (the only approval gate).
- If the plan response includes the first step's commands, they execute **immediately on approval** — no extra agent round-trip needed to start.
- After each step the controller waits for server messages (500 ms quiet window), feeds them back to the agent, and asks for the next step.
- A single, plan-free `wemc-commands` block in FLOW mode executes immediately without a plan-approval gate.

---

## 5. Approval & execution

`ApprovalMode` lives in `OpenAiSettings`:

|| Value | Behavior when the agent emits `wemc-commands` ||
|---|---|---|
| `ASK` (default) | Commands are validated, then parked in `MinecraftCommandExecutor.pendingCommands`. You review with `/wemc agent commands`, then either `/wemc agent run` (send them all) or `/wemc agent discard` (drop them). ||
| `APPROVE` | Commands are validated and sent immediately. Nothing is queued. ||

**Plan-bundled commands** (FLOW mode only): when `wemc-plan` and `wemc-commands` appear together, the commands are held in the controller as `pendingPlanCommands` and execute immediately on `/wemc flow approve` — they **do not** enter the ASK approval queue and do not need a separate `/wemc agent run` step.

Either way the executor applies the same checks before the command is delivered:

1. **`MinecraftCommandWhitelist.validateSequence`** — root verb must be on the whitelist, must match an enabled category's validator, must not exceed 100 commands per batch.
2. **`ChunkSelectionCommandGuard.validate`** — block-changing commands are checked against the confirmed chunk set and the configured Y range.
3. The command is sent through `Player.connection.sendCommand` (the active server connection). WEMC never mutates the client `Level` directly.
4. The executed command is appended to `ExecutedCommandHistory` and is visible via `/wemc command history`.

If any check fails the executor returns a one-line human-readable reason (the exact wording you see in chat), and **no command is sent**.

---

## 6. Whitelisted commands

The whitelist is a closed list (`MinecraftCommandWhitelist.definitions`) grouped into seven categories, each toggleable in **Config → Commands**. Every entry was checked against the Minecraft Wiki before inclusion; `wikiSource` is kept on the definition for traceability.

### 6.1 Categories

| Category | What it does | Default |
|---|---|---|
| **Query** | Read-only information | Enabled |
| **World state** | Time, weather | Enabled |
| **Inventory** | `give`/`clear`/`item` | Enabled |
| **World edit** | `setblock`/`fill`/`clone`/block-`data` | Enabled |
| **Entity** | `summon`/`kill`/`tag`/`tp @s ~ ~ ~` | Enabled |
| **Player state** | `effect`/`experience`/`gamemode` | Enabled |
| **Presentation** | `particle`/`playsound`/`title` | Enabled |

### 6.2 The manifest (as exposed by `/wemc command list`)

| # | Category | Syntax | Example |
|---|---|---|---|
| 1 | Query | `time query <daytime\|gametime\|day>` | `time query daytime` |
| 2 | Query | `data get <entity\|block\|storage> <target> [path] [scale]` | `data get entity @s` |
| 3 | Query | `clear [targets] [item] 0` | `clear @s minecraft:diamond 0` |
| 4 | World state | `time set <time\|day\|night\|noon\|midnight>` | `time set noon` |
| 5 | World state | `time add <time>` | `time add 1200t` |
| 6 | World state | `weather <clear\|rain\|thunder> [duration]` | `weather clear` |
| 7 | Inventory | `give <targets> <item> [count]` | `give @s minecraft:stone 64` |
| 8 | Inventory | `clear [targets] [item] [maxCount]` | `clear @s minecraft:cobblestone` |
| 9 | Inventory | `item <replace\|modify> <entity\|block> ...` | `item replace entity @s hotbar.0 with minecraft:stone` |
| 10 | World edit | `setblock <x> <y> <z> <block> [mode]` | `setblock ~ ~ ~ minecraft:stone` |
| 11 | World edit | `fill <from> <to> <block> [mode]` | `fill 0 64 0 15 64 15 minecraft:stone` |
| 12 | World edit | `clone <from> <to> <destination> [mask] [mode]` | `clone 0 64 0 15 80 15 32 64 32` |
| 13 | World edit | `data <merge\|modify\|remove> <block\|storage> ...` | `data merge block ~ ~ ~ {CustomName:'{"text":"WEMC"}'}` |
| 14 | Entity | `tp @s ~ ~ ~` | `tp @s ~ ~ ~` |
| 15 | Entity | `summon <entity> [position] [nbt]` | `summon minecraft:armor_stand ~ ~ ~` |
| 16 | Entity | `kill [targets]` | `kill @e[type=minecraft:zombie,distance=..16]` |
| 17 | Entity | `tag <targets> <add\|remove> <name>` | `tag @e[type=minecraft:zombie,distance=..16] add wemc_target` |
| 18 | Player state | `effect <give\|clear> <targets> ...` | `effect give @s minecraft:night_vision 60` |
| 19 | Player state | `experience <add\|set> <targets> <amount> [levels\|points]` | `experience add @s 5 levels` |
| 20 | Player state | `gamemode <mode> [targets]` | `gamemode creative @s` |
| 21 | Presentation | `particle <particle> [position] [delta] [speed] [count] [force\|normal] [viewers]` | `particle minecraft:happy_villager ~ ~1 ~ 0.3 0.3 0.3 0.01 8` |
| 22 | Presentation | `playsound <sound> <source> <targets> [position] [volume] [pitch]` | `playsound minecraft:block.note_block.pling master @s` |
| 23 | Presentation | `title <targets> <title\|subtitle\|actionbar> <text>` | `title @s actionbar {"text":"Ready"}` |

> Notes
> - `/execute`, `/teleport`, and any root verb not in the list are rejected at validation time.
> - `/teleport` is rejected with the explicit hint "Use the Flow context form /tp @s ~ ~ ~; /teleport is not enabled."
> - `/execute` is rejected with "`execute` is not allowed because it can bypass WEMC command and chunk-selection safeguards."
> - The tp form for Flow position context is **only** `tp @s ~ ~ ~` — anything else fails the `tp` validator.
> - A `clear` query ending in `0` is the read-only form; any other trailing integer is treated as a real clear and falls under Inventory.

### 6.3 Disabling a category

Open `/wemc config` → **Commands** tab. Click a category to flip it between ON/OFF. Disabled categories are removed from the agent's prompt **and** are rejected before reaching the server. The full list always appears in `/wemc command list` with a footer naming the disabled categories.

Persistence file: `<config>/worldedit-magician-command-permissions.json`.

---

## 7. Selection guard

`ChunkSelectionCommandGuard.validate` runs after the whitelist check, for every command that changes a block or block container:

| Command | What is checked |
|---|---|
| `setblock <x> <y> <z> …` | The single target position must be inside the configured Y range and inside a **confirmed** chunk |
| `fill <from> <to> …` | The full AABB must be inside the Y range; every chunk the AABB touches must be confirmed |
| `clone <from> <to> <destination>` | The destination AABB must be inside the Y range and inside confirmed chunks |
| `data <merge\|modify\|remove> block <x> <y> <z>` | The single target position must be inside a confirmed chunk and Y range |
| `item <replace\|modify> block <x> <y> <z>` | Same as the block `data` rules |

- Coordinates may be absolute integers or `~` / `~<delta>` relative to the player's current block position. `^`-style local coords are rejected.
- The guard reads the **confirmed** selection snapshot (`selectedChunks.toSet()`). The pending draft is ignored — you must right-click to confirm before commands can use those chunks.
- Failures return a one-line reason such as `"Blocked /setblock: confirm one or more chunks with the selection torch first."` or `"Blocked /fill: target Y range 80–120 is outside the confirmed Y range 0–64."`.

---

## 8. Settings screens

`/wemc` opens the unified `WemcConfigPanelScreen`. It is a 4-tab panel:

### 8.1 Tab 1 — AI Model

- **Provider** — choose from the 14 supported `AiProvider`s. Switching providers **automatically** loads that provider's saved model (`OpenAiSettingsStore.withSelectedProvider`); it never falls back to another provider's model.
- **Model** — type a model id, or click "Load models" to fetch the catalog from the provider's API. **API key** and **Base URL** are edited in their own fields. The credential field is a 256-character max `EditBox` (raised above the default 32) and the saved value is set **after** the limit, not before, so credentials are not truncated.
- **Context window** and **Max output tokens** — clamped to provider-sane ranges on save.
- **Show/Hide secrets** toggle.
- **Approval mode** — Ask / Approve.
- **Save** persists to `<config>/worldedit-magician.json`.

### 8.2 Tab 2 — Agent

- **Thinking mode** — `OFF` (default), `FIRST_STEP_ONLY`, `ON`.
- **Reasoning effort** — `low` / `medium` / `high` (forwarded to providers that support it).
- **Max AI requests** (1–30), **Max server steps** (0–50), **Query timeout** (3–20 s).
- **Reset to Defaults** button.

### 8.3 Tab 3 — Commands

- One `[ON]/[OFF]` button per `MinecraftCommandCategory`. Click to toggle; reloads the screen so the labels stay accurate.
- **Reset to Defaults** button (all categories enabled).

### 8.4 Tab 4 — WorldEdit

- Detects the **WorldEdit** mod via Fabric's `ModContainer`. Shows installed version + Minecraft version if present, a red "not found" message otherwise.

### 8.5 Legacy screens

`ConfigurationScreen`, `OpenAiSettingsScreen`, `CommandPermissionsScreen`, `AgentOperationScreen`, `OpenAiConnectionTestScreen`, and `WorldEditConfigurationScreen` still exist in the source tree but are no longer reachable from the live command dispatcher — the unified panel supersedes them. They are kept as building blocks and may be wired back in for advanced flows.

---

## 9. Providers & models

| Provider id | Base URL | Auth | Notes |
|---|---|---|---|
| `openai` | `https://api.openai.com/v1` | Bearer `apiKey` | Reasoning effort honored |
| `ollama` | `http://127.0.0.1:<port>` | none | Default port `11434`; `/api/chat` |
| `claude` | `https://api.anthropic.com/v1` | `x-api-key` header + `anthropic-version` | |
| `gemini` | `https://generativelanguage.googleapis.com/v1beta` | `x-goog-api-key` header | Model in URL path |
| `deepseek` | `https://api.deepseek.com/v1` | Bearer | |
| `minimax` | `https://api.minimax.io/v1` | Bearer | |
| `minimax_cn` | `https://api.minimaxi.com/v1` | Bearer | |
| `xai` | `https://api.x.ai/v1` | Bearer | |
| `mistral` | `https://api.mistral.ai/v1` | Bearer | |
| `cohere` | `https://api.cohere.ai/v1` | Bearer | `/v1/chat` |
| `perplexity` | `https://api.perplexity.ai` | Bearer | |
| `azure` | user-provided | `api-key` header | API version `2024-10-01-preview` default |
| `custom` | user-provided | Bearer if `customApiKey` set | Fully OpenAI-compatible payload |
| `copilot` | GitHub device-token endpoint | Access token | Token-based, see `CopilotProviderSupport` |

> API keys are sent in headers, **never** in URL query strings.

Each provider has its own dedicated settings slot (e.g. `claudeApiKey`, `ollamaBaseUrl`, `ollamaPort`) so credentials never leak across providers.

---

## 10. Debugging

This is the section that matters when something "doesn't work". Everything below is wired to live code; nothing here requires recompiling.

### 10.1 Three things to check first

1. **`/wemc status`** — confirms the active provider, model, effort, and approval mode. If the provider or model is missing, the chat request will fail at the HTTP layer with the provider's error message.
2. **`/wemc command list`** — shows the exact set of verbs the agent is allowed to use. If the command you expected is missing, the category is disabled in **Config → Commands**.
3. **`/wemc command history`** — the canonical "did it actually run" log: every command WEMC delivered to the server this session, newest first. If a command is here, the server received it; if it is not, the executor rejected it before send.

### 10.2 The full debug surface

| What you want to know | Where to look |
|---|---|
| Which provider / model / approval mode is in effect | `/wemc status` |
| Which command families are enabled | `/wemc command list` (the page footer lists the disabled ones) |
| What commands the agent **emitted** in its reply | `/wemc agent commands` — prints the current pending batch plus the whitelist |
| What the executor **rejected** | The chat line starting with `Agent command request rejected: …` or `Command sequence rejected: …` or `Blocked /<cmd>: …` |
| What commands **reached the server** | `/wemc command history` |
| Whether the executor is waiting on you (ASK mode) | `/wemc agent commands` shows the pending batch |
| Whether a flow is running | `/wemc flow status` |
| Current chunk / Y range / selection mode / facing block | Look at the WEMC HUD card (top-left, torch in main hand) |

### 10.3 The "approval loop" debug recipe

When the agent seems to ignore you, the cause is almost always approval mode or a disabled category. Walk through this:

1. `/wemc status` — note the approval mode (`Ask for approval` vs `Approve for me`).
2. If `Ask` (SINGLE mode):
   - Run `/wemc chat "your prompt"` again.
   - **The agent's text is stripped — you see nothing except the executor result.**
   - If execution succeeded: `Sent N command(s) to the server.` — the only chat output.
   - If execution failed: the rejection reason (`Command sequence rejected: …` or `Blocked /…`).
   - Run `/wemc command history` to confirm the command reached the server.
3. If `Approve` (SINGLE mode):
   - The commands are sent **immediately** after the agent reply. Same output as above.
4. In **FLOW mode with a plan** (`wemc-plan`):
   - The agent's reply shows: plan description (with `<eof>` stripped), then `[WEMC] Plan proposed (N steps): …`, then:
     - If commands are bundled: `[WEMC] First batch (N commands) will execute on approval.`
     - `[WEMC] Use /wemc flow approve to accept, /wemc flow cancel to reject.`
   - Run `/wemc flow approve` → the bundled commands execute **immediately** (no extra round-trip).
   - Run `/wemc command history` to confirm commands reached the server.
5. If the executor returned a "Blocked" message, the cause is the [selection guard](#7-selection-guard): confirm chunks with the torch, or expand the Y range with the wheel.

### 10.4 The "flow stuck" debug recipe

In FLOW mode:

1. `/wemc flow status` — confirms whether a flow is active and which state it is in.
2. If the chat shows `[WEMC] Plan proposed (N steps): <reason>`, the agent is **waiting on your approval**. Run `/wemc flow approve` to start step 1, or `/wemc flow cancel` to abort.
3. If you see `[WEMC] Step N sent K command(s); monitoring server responses…`, the controller is waiting for game messages. It will time out after `queryTimeoutSeconds` (default 8 s, × 2 with extended thinking).
4. Failure messages from the controller look like `Step N timed out without a server response.`, `Server step limit reached (X / 50).`, or `AI request limit reached (30).`. Raise the corresponding limit in **Config → Agent** if you genuinely need more.

### 10.5 Provider-side failures

`AiChatClient.send` returns an `AiChatResult.Failure(message)` for every provider error. The message is the provider's raw error string (trimmed). Common ones:

| Symptom | Cause | Fix |
|---|---|---|
| `401 … Incorrect API key` | Wrong key, or key copied with stray whitespace | Re-paste the key in **Config → AI Model**; the field strips whitespace before sending |
| `404 model not found` | Model id wrong for the provider | `/wemc model list` to fetch the actual catalog, then `/wemc model use <provider:model>` |
| `Network is unreachable` / `Connection refused` | Base URL wrong or local server down | For Ollama, check `ollamaBaseUrl` + `ollamaPort` and that `ollama serve` is running |
| `400 thinking_mode not supported` | You set thinking to `ON` for a provider that doesn't have it | Switch thinking mode to `OFF` in **Config → Agent** |

### 10.6 Inspecting persisted state

| File | What is in it |
|---|---|
| `<config>/worldedit-magician.json` | All `OpenAiSettings` — provider, model, API key, base URL, approval mode, reasoning effort, context window, agent name, max output tokens. **Contains API keys in plaintext.** |
| `<config>/worldedit-magician-command-permissions.json` | Enabled/disabled state for each `MinecraftCommandCategory`. |

`<config>` is the standard Fabric config directory (`configDir` from `FabricLoader.getInstance()`).

### 10.7 Log files

The mod uses SLF4J under the namespace `worldedit-magician` for mod-init logging. Game logs at `<run>/logs/latest.log` contain anything logged through SLF4J. Client-command errors (e.g. invalid arguments) are routed through the same logger and surface in chat at the same time.

### 10.8 Common false-alarm causes

- "The agent said nothing" — it returned plain text with no `wemc-commands` block. That is **not** an error; only `wemc-commands` blocks can produce commands. Re-prompt with a more concrete request.
- "The agent's command was rejected" — the command was on a category that is disabled, or the verb is not on the whitelist. `/wemc command list` is the source of truth.
- "My chunk selection was ignored" — the orange draft is not enough; you must right-click with the torch to confirm. Pending drafts do not authorize commands.
- "I configured a custom base URL but credentials show up in the URL" — credentials are sent in headers, but the URL itself is logged by the provider. The mod never puts credentials in the URL.

---

## 11. Persistence

| File | Owner | Format | Loaded by | Saved by |
|---|---|---|---|---|
| `<config>/worldedit-magician.json` | `OpenAiSettingsStore` | JSON, pretty-printed | `load()` | `save()` |
| `<config>/worldedit-magician-command-permissions.json` | `CommandPermissionsStore` | JSON, one boolean per `MinecraftCommandCategory` | `load()` | `save()` |

All saved values are trimmed, normalized, and clamped to sensible ranges (e.g. context window 1 024 – 2 000 000; max output tokens 256 – 128 000; Ollama port 1 – 65 535). Provider URLs are normalized to a canonical `scheme://host:port/path` form so trailing slashes / mixed casing cannot cause accidental dual-config.

---

## 12. Keybindings

Categories are registered once at startup (`WorldeditMagicianClient.generalCategory`, `…worldeditCategory`) and reused for every binding, so duplicate IDs are not an issue.

| Key | Category | Translation key | Default | Bound to |
|---|---|---|---|---|
| `O` | WorldEdit Magician | `key.worldedit-magician.openai_settings` | Yes | Open `/wemc` panel |
| `C` | WorldEdit | `key.worldedit-magician.selection_operation` | Yes | (with Ctrl) cycle operation |
| `V` | WorldEdit | `key.worldedit-magician.selection_shape` | Yes | (with Ctrl) toggle shape |
| `Delete` | WorldEdit | `key.worldedit-magician.selection_cancel` | Yes | Cancel draft / clear selection |

All bindings are also reachable via the in-game Controls menu.

---

## 13. Compatibility

- **Minecraft** 1.21.11 (`minecraft_version=1.21.11`).
- **Fabric Loader** 0.19.3, **Fabric API** `0.141.5+1.21.11`, **Fabric Language Kotlin** `1.13.13+kotlin.2.4.10`.
- **Java 21**, **Kotlin 2.4.10**.
- **WorldEdit** mod is **detected** at startup but **not required**. WEMC never imports WorldEdit APIs; it only uses the mod's presence as a status indicator in the WorldEdit tab of the settings panel. `WorldEditInstallationChecker.checkAtStartup()` reads `FabricLoader.getModContainer("worldedit")` and stores the version + detected Minecraft version.
- WEMC is **client-side only**. It registers no server-side commands; commands are sent through the player's active client connection.

### 13.1 What it does **not** do

- No `/execute`, `/teleport`, `/function`, `/schedule`, `/command`, or any command-block/admin/server-lifecycle commands.
- No client-side world mutation. Every block change is sent through the server.
- No undo system. There is a `CommandHistory` data structure in the source (`CommandHistory.kt`) but it is **not** wired to any user-facing command. The README's earlier `undo/redo` references are leftovers from the deprecated agent-commands design — none of them are reachable in the live client.

---

## Appendix A — All `/wemc …` and `/worldeditmagician …` cheat sheet

```
/wemc                                          open settings panel
/wemc config                                   open settings panel
/wemc status                                   print active provider/model/effort/approval
/wemc operation single                         switch to SINGLE mode
/wemc operation flow                           switch to FLOW mode
/wemc operation status                         show operation mode + limits
/wemc approval ask                             commands queue until /wemc agent run
/wemc approval approve                         commands run immediately
/wemc provider list                            list providers
/wemc provider use <id>                        activate provider (openai|ollama|claude|gemini|deepseek|minimax|minimax_cn|xai|mistral|cohere|perplexity|azure|custom|copilot)
/wemc model list                               fetch + print provider's model catalog
/wemc model use <provider:model>               pick a model (use quotes if the model id has spaces)
/wemc chat <prompt…>                           send a prompt; starts a flow in FLOW mode
/wemc command list                             show enabled whitelist page 1
/wemc command list <n>                         show whitelist page n
/wemc command history                          print every command WEMC has sent this session
/wemc agent commands                           print whitelist + pending batch
/wemc agent run                                send the pending batch now
/wemc agent discard                            drop the pending batch
/wemc query time                               hint message: use /time query daytime|gametime|day
/wemc query entity                             hint message: use /data get entity …  (vanilla has no /entity query)
/wemc flow approve                             accept the agent's plan
/wemc flow cancel                              abort the active flow
/wemc flow status                              print flow state
/worldeditmagician config                      same as /wemc
/worldeditmagician worldedit                   open WorldEdit installation screen
```

> `/wemc run` is registered but has no handler — typing it currently does nothing.

---

## Appendix B — Worked examples

### B.1 Single-shot time set (SINGLE mode)

```
/wemc operation single
/wemc approval approve
/wemc chat set the world time to noon
```

What happens:

1. The agent receives your prompt, the player-state block, the whitelist, and the SINGLE-mode instructions.
2. The reply contains `\```wemc-commands\ntime set noon\n\```\` and plain text ("Set time to noon.").
3. The `wemc-commands` block is stripped before display — **no agent text appears in chat**.
4. The executor validates `time set noon` against the whitelist (`WORLD_STATE`, `time set …`), the chunk guard ignores it (not a block-edit), and `connection.sendCommand("time set noon")` is called.
5. The executor prints `Sent 1 command(s).` — **this is the only chat message** (unless execution fails, in which case the rejection reason is shown).
6. `ExecutedCommandHistory.record("time set noon")`. `/wemc command history` now shows it.

### B.2 Multi-step build (FLOW mode, plan + bundled commands)

```
/wemc approval approve
/wemc chat build a 5x5 stone platform centered on me, two blocks above the ground
```

What happens:

1. The agent returns a `wemc-plan` block with `steps: 3`, a reason, AND a bundled `wemc-commands` block containing step 1's commands.
2. Chat shows the plan description (plain text, `<eof>` stripped), then:
   - `[WEMC] Plan proposed (3 steps): …`
   - `[WEMC] First batch (N commands) will execute on approval.`
   - `[WEMC] Use /wemc flow approve to accept, /wemc flow cancel to reject.`
3. You run `/wemc flow approve`. The controller executes the bundled step-1 commands **immediately** — no extra agent round-trip.
4. Server responses are collected; after the 500 ms quiet window they are sent back to the agent as context.
5. The agent returns step 2 (`\```wemc-commands\n…\n\```\`). Commands execute. Same monitoring loop.
6. After the last step it includes `<eof>` on its own line. The flow ends with `[WEMC] Flow finished — N command(s) executed.`

### B.3 Disabled category rejection

You disabled the **Entity** category in **Config → Commands**. You ask the agent to `summon a zombie`. The agent emits a `wemc-commands` block with `summon minecraft:zombie ~ ~ ~`. The whitelist rejects it because no `summon` definition is enabled. Chat shows `Command sequence rejected: Command 1: 'summon' is disabled by command permissions (Entity). Open /wemc config to enable its category.` Re-enable Entity and try again.

### B.4 Block-edit blocked by selection guard

You confirm a single chunk with the torch (corner-mode draft confirmed). The Y range is `0–20`. The agent emits `fill 0 64 0 15 64 15 minecraft:stone`. The chunk guard rejects it: `Blocked /fill: target Y range 64–64 is outside the confirmed Y range 0–20.` Adjust the Y range with `Ctrl + Wheel`, then re-prompt.
