# World Block Operations & Litematica Integration Plan

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Turn the mod from a chat-only AI client into an agent that can read, modify, build, save, and load structures in the Minecraft world — including Litematica `.litematica` file support.

**Architecture:** A tool-calling agent loop (`AgentTool` registry + per-provider function calling) where every world mutation goes through a server-thread executor with a draft → approval → apply → undo pipeline. Structure operations (primitives + Litematica load/save) are implemented mod-side as deterministic generators that produce block-op lists, so the AI composes high-level calls instead of emitting thousands of block placements.

**Tech Stack:** Kotlin, Fabric API (client + server events), Minecraft 1.21.11 official mappings, Gson, existing `java.net.http.HttpClient` AI stack, `NbtIo` for Litematica NBT parsing.

---

## Current Context

- Mod = AI chat client only. `/wemc msg` sends a prompt and prints text. No world reads, no writes, no tool calling.
- `OpenAiSettings` already has `approvalMode` (ASK/APPROVE) — the approval gate exists as a setting but nothing consumes it.
- `AiChatClient` supports 6 providers but uses plain text prompts only (no tools/functions).
- Dev environment ships WorldEdit 7.4.2 + WorldEditCUI in `run/`; `WorldEditInstallationChecker` detects presence only.
- No test source set exists. Build via `gradlew build`, run via `gradlew runClient`.
- All AI calls follow the pattern: `CompletableFuture<SealedResult>` + re-dispatch to client thread via `Minecraft.getInstance().execute {}`.

## Key Architecture Decisions

### D1. Interface: GUI agent panel + tools, NOT a chat-command DSL
- The agent's "command language" is **structured tool calls** through the LLM API (JSON function calling), executed directly by the mod. Chat commands stay a thin human control surface (`/wemc status`, `/wemc undo`, `/wemc approval ...`, `/wemc build`).
- Add an in-game **Agent Panel screen** (goal input + activity transcript + Approve/Deny buttons) as the primary human interface. Long DSL-style commands are dropped — a DSL duplicates what function-calling already provides and is worse for the agent to emit correctly.

### D2. Everything is a block-op list (draft → apply → undo)
- All writes (primitives, fills, Litematica paste) produce a `List<BlockOp>` (position + block state + previous state). Apply = one atomic pass on the server thread; every apply pushes a snapshot onto an undo stack (`/wemc undo`, or button in panel).
- This gives verification ("checking and fixing the building it generated") and safety for free.

### D3. Mod-side primitive library ("small lib"), agent composes it
- Deterministic mod-side generators: `box`, `floor`, `wall` (with window/door cutouts), `stairs`, `roof` variants, `sphere`, `cylinder`, `clear`, `replace`.
- The agent calls these as tools with small parameter sets (origin, size, material). For a 5×5 house the agent emits ~5–10 calls, not hundreds of blocks. Every primitive returns block-ops through the D2 pipeline.
- Litematica load: mod decodes `.litematica` NBT directly (palette + bit-packed block states + block entities) into block-ops — no hard dependency on Litematica itself.

### D4. Transferability: canonical artifacts + documented protocol + session log
- **Design artifacts** persist as `.litematica` NBT files (portable, openable in Litematica/WorldEdit/other tools) plus a JSON "design spec" (block-op list with palette). This is the transfer medium to other applications.
- **Behavior protocol**: the tool-call JSON schema (names, parameters, results) is documented in `docs/tools.md` — any application (web viewer, server bot, another MC version) that implements the same schema can drive the same agent.
- **Process log**: every agent run writes a JSONL session trace (goal, tool calls, params, results, approvals, undo events). Another app can replay, audit, or continue a design session.

### D5. Threading model
- Client-side mod + integrated server in singleplayer: world reads/writes must happen on the **server thread**. Execute via `Minecraft.getInstance().getSingleplayerServer()?.execute {}` (or server tick events), never directly from the client thread. Reads return futures resolved on the client thread for the AI caller.

### D6. Approval gate
- ASK mode: writes show a GUI dialog (operation summary: N ops, volume, affected blocks preview) before applying. APPROVE mode: auto-apply. All writes respect this; reads never require approval.

### D7. Dual world backend: in-game interface (live) + file interface (offline)
- **Rule: world open → in-game tool interface only; world closed → file interface.** Never mix: the server caches chunks in memory and writes them on save, so editing region files while the world runs corrupts or is overwritten (`session.lock` exists for this).
- Minecraft's on-disk layout (verified against `run/saves/New World`): per-dimension `region/r.X.Z.mca` (blocks + block entities, Anvil region → NBT chunks with palette + packed block states), separate `entities/r.X.Z.mca` (entity NBT), `poi/r.X.Z.mca`, gzip-NBT `level.dat`, `playerdata/<uuid>.dat`. Nether/End mirror under `DIM-1/`, `DIM1/`.
- Define `WorldBackend` interface (read/write block ops, entities, player) with two implementations:
  - `LiveWorldBackend` — marshals to the server thread via `getSingleplayerServer()` (primary; Tasks 2–8).
  - `OfflineWorldBackend` — opens a world through `LevelStorage`/`RegionFileStorage`/`ChunkSerializer` when no game is running: bulk generation into unlaunched worlds, reading other worlds, batch repairs. Light data pitfall: mark edited chunks unlit (`isLightOn=false`) and let the game relight on load; never touch heightmaps manually.
- The `AgentTool` schema is backend-agnostic (D4 contract): tools operate on `WorldBackend`, never on `ServerLevel` directly.

### D8. Chunk-based selection — the only addressing mode for bulk ops
- **Selection = `Set<ChunkPos>`**, the single shared object mutated by BOTH the human torch UI (D9) and agent tools (`select_square`, `select_chunks`, `clear_selection`). Chunk is the I/O unit everywhere: no partial-chunk writes, same code path for live and offline backends, chunk-granularity undo (snapshot edited chunks before apply, restore after — WorldEdit's model).
- **Rules:**
  1. All bulk ops (fill, primitives, Litematica paste) are clipped to the selection; the tool reports the clipped volume.
  2. Live path: before applying, every chunk in the op's bounding set must be **loaded** (`ServerLevel.isLoaded`). Unloaded → refuse and list the chunks, unless the tool explicitly passes `allow_load=true`. Never silently generate terrain.
  3. Offline path: selection maps straight to region files; absent chunks are never created (terrain generation is a separate, explicit future op).
- **In-memory modification patterns:** live = `Level.setBlock` (game's own path: heightmaps, block entities, light, dirty-flag all handled; no file I/O). Offline = pure-NBT: `RegionFileStorage.readChunk` → mutate `sections[]` `palette`/`data` → `isLightOn=false` → `writeChunk`. Palette re-packing (bits = `max(4, ceil(log2(size)))`) is shared with the Litematica writer via one `BlockStatePacking` utility (unit-testable, no ServerLevel needed).

### D9. Torch selection tool (human input device for the shared selection)
- Right-click with a torch while "selection mode" is active selects chunks; intercepted via Fabric `UseBlockCallback` (return `SUCCESS` to cancel placement). Mode toggled by keybind or `/wemc select ...`; item configurable (default torch).
- **Modes:** `square` (corner A → corner B = bounding rectangle), `onebyone` (each click adds), add/delete toggle, `clear`.
- Feedback: selected-chunk border overlay via `WorldRenderEvents` + action-bar line (mode, chunk count).
- Agent-facing equivalent tools mutate the same `ChunkSelection`.

---

## Step-by-Step Plan

### Task 1: AgentTool registry + tool-call JSON schema

**Objective:** Define the `AgentTool` abstraction and the tool list that will be advertised to LLMs.

**Files:**
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/AgentTool.kt`
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/ToolResult.kt`
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/ToolRegistry.kt`
- Create: `docs/tools.md` (protocol documentation, per D4)

**Step 1:** Define `AgentTool`:
```kotlin
interface AgentTool {
    val name: String                       // snake_case, e.g. "get_block"
    val description: String
    val parameters: JsonObject             // JSON Schema object for the LLM
    fun execute(args: JsonObject, context: AgentContext): ToolResult
}
sealed interface ToolResult { data class Success(val output: JsonObject); data class Failure(val message: String) }
```

**Step 2:** `ToolRegistry` holds tools by name; `listForLlm()` returns name/description/parameters for the API request.

**Step 3:** Write `docs/tools.md` capturing the schema contract (D4).

**Step 4:** Commit: `git commit -m "feat(agent): tool registry and schema"`

### Task 2: Server-thread world executor (read + write core)

**Objective:** Safe world access from the client mod: reads on server thread, writes as atomic block-op application with undo.

**Files:**
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/world/WorldBackend.kt` — interface: block read/write ops, entities, player (D7; tools depend on this, never on `ServerLevel`)
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/world/LiveWorldBackend.kt` — `runOnServer { }` wrapper marshaling to `getSingleplayerServer().execute` (D5)
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/world/BlockOps.kt` — `BlockOp` data class (pos, newState, previousState), `BlockOpList`, `apply()`
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/world/UndoStack.kt` — bounded stack of applied op-lists
- Modify: `src/client/kotlin/com/magician/worldedit/client/WorldeditMagicianClient.kt` — register `/wemc undo`

**Step 1:** Define `WorldBackend` (getBlock, setBlock ops, getPlayer, listEntities) and `LiveWorldBackend` — marshals to `getSingleplayerServer().execute`, returns `CompletableFuture`.
**Step 2:** `BlockOpList.apply(level)` — capture previous state per op (for undo), set new state.
**Step 3:** UndoStack: push on apply (cap ~50 entries), pop restores previous states.
**Step 4:** Wire `/wemc undo`; verify in-game: place blocks via a temporary test command, undo restores them.
**Step 5:** Commit.

### Task 3: Read tools

**Objective:** Agent can observe the world: block lookups, region scans, player position, entities.

**Files:**
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/tools/ReadTools.kt` (get_block, get_blocks, get_player_pos, get_entities, raycast)

**Step 1:** `get_block(x,y,z)` → `{block: "minecraft:stone", state: {...}}`.
**Step 2:** `get_blocks(x1,y1,z1,x2,y2,z2)` with a volume cap (e.g. 4096 blocks) and compact JSON (relative coords, palette compression) to keep token usage sane. Return truncated flag when capped.
**Step 3:** `get_player_pos`, `get_entities` (type, pos, key NBT), `raycast` (from camera).
**Step 4:** Verify with `/wemc msg "what block is at my feet?"` — needs Task 5 (tool loop) or a temporary `debug_run_tool` command for manual testing.
**Step 5:** Commit.

### Task 4: Write tools + approval gate

**Objective:** Agent can modify the world, gated by ApprovalMode with a GUI dialog.

**Files:**
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/tools/WriteTools.kt` (set_block, fill_box, clear, replace)
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/ApprovalGate.kt` — ASK → player dialog; APPROVE → auto-apply
- Create: `src/client/kotlin/com/magician/worldedit/client/screen/ApprovalDialogScreen.kt`

**Step 1:** `set_block`, `fill_box` (with mask option), `clear` (fill air), `replace` (only within matching blocks) — all return block-ops, NOT applied directly (D2).
**Step 2:** `ApprovalGate.request(opList, context)` — in ASK mode opens the dialog (ops count, bounding box volume, sample of first blocks, Apply/Deny/Preview buttons); APPROVE mode applies immediately.
**Step 3:** Apply through `WorldAccess` + push undo.
**Step 4:** Verify manually via the panel/tool loop; confirm undo works after approval.
**Step 5:** Commit.

### Task 4b: Chunk selection state + torch tool

**Objective:** The shared `ChunkSelection` (D8) and its human input device (D9).

**Files:**
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/selection/ChunkSelection.kt` — `Set<ChunkPos>` wrapper: add, remove, clear, square(cornerA, cornerB), contains, size
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/selection/TorchSelector.kt` — selection-mode state machine (square/onebyone, add/delete), `UseBlockCallback` interception
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/selection/SelectionRenderer.kt` — chunk-border overlay via `WorldRenderEvents`
- Modify: `WorldeditMagicianClient.kt` — `/wemc select square|onebyone|add|delete|clear|info`, keybind to toggle selection mode
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/tools/SelectionTools.kt` — `select_square`, `select_chunks`, `clear_selection`, `get_selection` (agent-facing, same object)

**Step 1:** `ChunkSelection` with square/onebyone mutations and an event hook (selection-changed → renderer + action bar update).
**Step 2:** `TorchSelector`: while enabled, right-click on a chunk with the configured item (default torch) runs the mode logic and returns `SUCCESS` from `UseBlockCallback` so no torch is placed.
**Step 3:** Border overlay renders selected chunk outlines (F3+G-style lines, distinct color).
**Step 4:** Agent tools (D8) registered — verify `/wemc select square` then agent `get_selection` returns the same set.
**Step 5:** Commit.

### Task 5: Tool-calling agent loop in AiChatClient

**Objective:** Replace plain prompt chat with a function-calling loop for all providers.

**Files:**
- Modify: `src/client/kotlin/com/magician/worldedit/client/config/AiChatClient.kt`
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/AgentSession.kt` (loop: prompt → model → tool_calls → execute → results → … → final)
- Modify: `src/client/kotlin/com/magician/worldedit/client/config/OpenAiSettingsStore.kt` (system prompt config: agent name, world context)
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/SessionLog.kt` (JSONL trace, D4)

**Step 1:** Extend per-provider request builders to advertise `tools` (OpenAI/DeepSeek/Compatible: `tools` array; Claude: `tools`; Gemini: `functionDeclaration`; Ollama: `tools` where supported — degrade to chat-only when the provider/model doesn't support tools, with a clear message).
**Step 2:** `AgentSession` loop: send → parse `tool_calls` → execute sequentially (approval-gated) → append results → repeat; cap iterations (e.g. 30) and track tokens.
**Step 3:** System prompt: agent identity, world context (dimension, player pos, approval mode), tool usage guidance ("prefer primitives over raw fills").
**Step 4:** SessionLog writes every exchange + tool call + result + approval decision to `config/worldedit-magician/sessions/<timestamp>.jsonl`.
**Step 5:** Verify: `/wemc msg "build a 3x3 cobblestone platform in front of me"` (APPROVE mode) places blocks; ASK mode shows dialog.
**Step 6:** Commit.

### Task 6: Primitive library ("small lib")

**Objective:** High-level building generators the agent composes for small/medium structures.

**Files:**
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/primitives/StructurePrimitives.kt`
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/tools/PrimitiveTools.kt` (box, floor, wall, stairs, roof, sphere, cylinder, column)

**Step 1:** Implement generators returning `BlockOpList` (D3): parameterized by origin, size, material, orientation. `wall` supports window/door cutouts.
**Step 2:** Register as tools with compact parameter schemas.
**Step 3:** Verify: agent builds a small house (4 walls + floor + roof) in one prompt via `/wemc msg`.
**Step 4:** Commit.

### Task 7: Litematica read/write (`.litematica` NBT)

**Objective:** Save the agent's builds to `.litematica` files and paste saved structures into the world.

**Files:**
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/litematica/LitematicaFormat.kt` — NBT schema: `Version`, `Size`, `Palette` (block states), `BlockStates` (bit-packed longs), `BlockEntityData`, `Entities`, `Metadata`
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/litematica/LitematicaReader.kt`
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/litematica/LitematicaWriter.kt`
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/tools/LitematicaTools.kt` (save_structure, load_structure, list_structures)

**Step 1:** Reader: parse NBT → palette `List<BlockState>` + packed indices → `BlockOpList` (with block-entity data preserved).
**Step 2:** Writer: block-op list → palette + bit-packed `BlockStates` + metadata → `config/worldedit-magician/schematics/<name>.litematica` (default `run/schematics/` so Litematica users see them too, configurable).
**Step 3:** `load_structure` supports anchor point + optional rotation/mirror (90° steps); pastes through the approval/undo pipeline (D2/D6).
**Step 4:** Round-trip test: save a built house, load it elsewhere, compare via `get_blocks` (verification per IDEA.md "checking and fixing").
**Step 5:** Validate against real Litematica: create a schematic in the game with Litematica (if available in dev env) and read it; if not installed, validate NBT with an external Litematica tool or nbt dumper.
**Step 6:** Commit.

### Task 8: Agent Panel GUI

**Objective:** Primary human interface: goal input, live activity transcript, approval buttons, undo.

**Files:**
- Create: `src/client/kotlin/com/magician/worldedit/client/screen/AgentPanelScreen.kt`
- Modify: `src/client/kotlin/com/magician/worldedit/client/WorldeditMagicianClient.kt` — `/wemc panel` + keybinding

**Step 1:** Scrollable transcript of agent actions (tool calls rendered compactly, colored by type), text field for goals, Approve/Deny buttons when ASK gate is pending, Undo button.
**Step 2:** Keep the panel open while the agent works (async, non-blocking).
**Step 3:** Commit.

### Task 9: Stretch — redstone/tick control, screenshots, entity state

**Objective:** IDEA.md capabilities: tick-rate control, taking photos, entity state reading.

**Files:**
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/tools/TickTools.kt` (set_tick_speed, pause)
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/tools/PhotoTools.kt` (screenshot at position → PNG saved to screenshots dir, path returned to agent)

**Step 1:** Tick speed via server tick rate override; photo via client framebuffer capture on the render thread.
**Step 2:** Commit. (Can be deferred — YAGNI until core loop is solid.)

### Task 10: Offline file backend (`OfflineWorldBackend`)

**Objective:** Same tool schema against closed worlds — bulk generation into unlaunched worlds, reading other saves, batch repairs.

**Files:**
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/world/OfflineWorldBackend.kt` — pure-NBT chunk editing (D8): `RegionFileStorage.readChunk` → mutate sections → `isLightOn=false` → `writeChunk`
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/world/BlockStatePacking.kt` — palette bit-packing shared with the Litematica writer (bits = `max(4, ceil(log2(size)))`, little-endian long packing)
- Create: `src/client/kotlin/com/magician/worldedit/client/agent/tools/FileWorldTools.kt` (open_world_file, close_world_file, select_world)

**Step 1:** Open a world via `LevelStorage.create(levelDir)` when its `session.lock` is free (verify: no server running on it).
**Step 2:** `BlockStatePacking`: palette index → packed long array (and reverse) with re-pack on palette growth; unit tests round-trip random palettes.
**Step 3:** Implement the `WorldBackend` interface over region files with pure-NBT mutation (D8): reads/writes via `RegionFileStorage`, section `palette`/`data` mutation via `BlockStatePacking`, entity edits via the `entities/` region files. Selection-driven: absent chunks are never created.
**Step 4:** Light fixup: set edited chunks unlit (`isLightOn=false`) so the game relights on next load; never write heightmaps manually.
**Step 5:** Verify with the JVM unit tests (a scratch world dir in `build/test-worlds/`): write ops, close, reopen, assert block states match (round-trip through `BlockStatePacking`).
**Step 6:** Commit.

---

## Files Likely to Change (summary)

- Create: `src/client/kotlin/com/magician/worldedit/client/agent/**` (registry, session, approval, world executor, tools, primitives, litematica, session log)
- Create: `src/client/kotlin/com/magician/worldedit/client/screen/AgentPanelScreen.kt`, `ApprovalDialogScreen.kt`
- Modify: `WorldeditMagicianClient.kt` (commands: `panel`, `undo`), `AiChatClient.kt` (tools support), `OpenAiSettingsStore.kt` (system prompt), `fabric.mod.json` if new mixins needed (unlikely)
- Create: `docs/tools.md`, session logs under `config/worldedit-magician/sessions/`

## Testing / Validation

- Manual in-game: `gradlew runClient`, then via panel or `/wemc msg`:
  1. "What block am I looking at?" → read tool result printed.
  2. APPROVE mode: "build a 5×5 oak platform here" → blocks appear; `/wemc undo` restores.
  3. ASK mode: same prompt → dialog appears; Deny → nothing applied.
  4. "Save this as tower.litematica" → file exists; paste into new location.
  5. Primitive composition: "small house with door and windows" → correct layout.
- Add a JVM unit test source set for pure logic (primitive generation, Litematica bit-packing, JSON schema emission) — these are Minecraft-free and testable with plain JUnit.
- Litematica compatibility: inspect declared MC version constraint of any Litematica jar before claiming compatibility (Mistake Log rule).

## Risks & Open Questions

- **Provider tool support varies**: OpenAI/DeepSeek/Gemini/Claude support function calling; Ollama depends on model. Fallback: plain chat with a "tool request" text protocol (JSON in fenced block) — keep this as a compatibility layer.
- **Client vs server threading**: singleplayer integrated server is on the server thread; LAN/remote worlds are out of scope initially (client-side mod). Document this limitation.
- **Litematica format drift**: format is stable but versioned (`Version` field); support 3+ (current) and validate unknown versions loudly.
- **Token budgets**: region reads must be capped/compressed or the context window fills instantly (D3 mitigates by composing primitives).
- **Approval fatigue**: ASK mode on every write call may be annoying; consider "approve batch" in panel (approve all queued ops for this goal).
- Open question for user: should structures save to the standard `run/schematics/` folder (shared with Litematica) or a mod-private folder?
