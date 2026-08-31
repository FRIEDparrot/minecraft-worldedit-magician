# WorldEdit Magician (WEMC)

**WorldEdit Magician** is a Fabric client mod for Minecraft **1.21.11** that turns natural-language requests into a controlled pipeline of vanilla Minecraft commands.

It combines three currently implemented pieces:

1. A **torch-driven chunk selection** UI with an in-world HUD and 3D previews.
2. A configurable **AI chat client** that asks a selected model to produce WEMC Command Language (**WCL**).
3. A client-side **WCL compiler and command blacklist gate** that blocks sensitive server-control commands before sending generated commands through the active player's normal server connection.

WEMC is not a replacement for the WorldEdit mod and it does not call WorldEdit APIs. WorldEdit is detected only for an installation-status screen.

> **Status:** this README describes the code currently wired into the client. Ordinary vanilla and modded commands are accepted by default after WCL compilation; only the blacklist gate blocks sensitive server-control commands. Selected chunks and the Y range are **not yet connected to command execution as a safety guard**. See [Current limitations](#current-limitations) before using AI-generated block-edit commands.

For the deeper protocol and implementation reference, see [the command reference](docs/commands.md), [the full documentation](docs/documentation.md), and [the WCL specification](docs/wcl-spec.md).

## What the mod can do today

| Area | Current capability |
|---|---|
| Chunk selection | Select one chunk or a rectangle of chunks with a torch; preview pending chunks in orange and confirmed chunks in blue; adjust the selection Y bounds. |
| AI configuration | Configure a provider, endpoint, key/token, model, reasoning effort, Flow on/off, token/context limits, approval mode, and catalog categories in the in-game settings panel. |
| Chat sessions | Maintain a manually initialized, rolling chat session for Single mode; inspect its status/history and control the local response cache. |
| AI command generation | Ask an AI to return a fenced `wcl` program. WEMC compiles its supported WCL subset into concrete vanilla commands. |
| Command control | Block sensitive server/admin command families, keep a curated command catalog for agent guidance, queue Single-mode output for review, and retain session histories of generated WCL and commands actually sent. |
| Flow execution | Run bounded multi-step AI workflows that can wait for server chat/game responses before asking the model for the next WCL step. |
| Direct commands | Send one manually written command through the blacklist gate with `/wemc command run <command>`. |

## How it works

```text
Player prompt (/wemc chat ...)
        |
        v
Configured AI provider + player-state context + command guidance
        |
        v
AI response containing one fenced WCL program
        |
        v
WCL compiler (loops / substitutions / random helpers expand to commands)
        |
        v
Post-compile command blacklist gate
        |
        +--> Single / Ask: queue for /wemc agent run or /wemc agent discard
        |
        +--> Single / Approve or eligible Flow step: send commands now
        |
        v
Player.connection.sendCommand(...) -> active Minecraft server
```

The server remains authoritative. WEMC does not mutate the client world and it does not grant permissions. A command only has an in-game effect if the connected server accepts it and the player has the required permission/game-rule access.

## Requirements

- Minecraft **1.21.11**
- Fabric Loader **0.19.3 or newer**
- Fabric API
- Fabric Language Kotlin
- Java **21** for development/building

The project currently builds version `1.0.0`. The Fabric metadata declares a client entrypoint for WEMC's interactive behavior; no WEMC server-side command handler exists.

## Quick start

1. Install the built mod JAR together with the required Fabric dependencies, then launch Minecraft.
2. Open settings with `O`, `/wemc`, or `/wemc config`.
3. Choose a provider, configure its credentials/endpoint if required, select a model, and save.
4. In the **Agent** tab, use the **Flow Mode** switch:
   - **[ON] Multi-step flow enabled** — the default; starts a bounded multi-step workflow directly from `/wemc chat <prompt>`.
   - **[OFF] Single-request mode** — one AI request per prompt; use `/wemc chat init` before the first prompt to create its chat session.
5. Start conservatively with Single mode and manual approval:

   ```mcfunction
   /wemc operation single
   /wemc approval ask
   /wemc chat init
   /wemc chat set the world time to noon
   ```

6. If the model returns valid WCL, review the pending result and either execute or discard it:

   ```mcfunction
   /wemc agent commands
   /wemc agent run
   # or: /wemc agent discard
   ```

7. Confirm what reached the server with:

   ```mcfunction
   /wemc command history
   ```

## Torch selection tool

Hold a standard **torch** in the main hand to show the WEMC HUD. It displays the operation mode, selection shape, player/chunk position, pending or confirmed chunk count, and current inclusive Y range. World-space prisms show confirmed chunks in blue and pending drafts in orange.

### Controls

| Input | Current behavior |
|---|---|
| `Ctrl + C` (selection tool active) | Cycle the operation for the next draft: **Replace → Add → Remove**. Changing it discards an unfinished draft. |
| `Ctrl + V` (selection tool active) | Toggle between **Single** chunk mode and **Area** (two-corner rectangle) mode. Changing it discards an unfinished draft. |
| `Ctrl + Left-click` a block | Stage the block's chunk. In Area mode, the first click anchors the first corner and a later click sets the second corner. |
| Mouse wheel in an unfinished Area draft | Move the second corner by one chunk in the direction the player faces. |
| Right-click with a torch | Confirm the staged draft. This is never a cancel action. |
| `Delete` | Cancel only the current draft. |
| `Ctrl + Shift + Delete` | Cancel the draft and clear all confirmed chunks. |
| `Ctrl + Shift + mouse wheel` | Adjust the upper Y bound by one block. |
| `Ctrl + Alt + mouse wheel` | Adjust the lower Y bound by one block. |

The first staged block initializes the inclusive vertical range from its Y level through 20 blocks above it, clamped to the world's build height.

### What selection currently means

The selection state, HUD, preview renderer, Replace/Add/Remove operations, and Y-bound controls are functional. However, the current `MinecraftCommandExecutor` only performs WCL compilation and blacklist validation before calling the server connection. It does **not** inspect the confirmed chunk set or Y range when sending `/setblock`, `/fill`, `/clone`, block `data`, or block `item` commands.

Treat selection as a planning/visualization tool in this version—not as an execution boundary. Verify coordinates and use approval mode before allowing block-edit commands to run.

## AI chat modes

### Single mode

Single mode sends exactly one AI request for each `/wemc chat <prompt>` call. It requires an active session created by `/wemc chat init`.

Only a response containing a fenced `wcl` block can produce executable commands. The pipeline compiles that WCL, applies the blacklist gate to its output, and then follows the selected approval policy:

- **Ask** (default): validated commands are queued. Use `/wemc agent commands`, `/wemc agent run`, or `/wemc agent discard`.
- **Approve**: validated commands are sent immediately.

Useful session commands:

```mcfunction
/wemc chat init
/wemc chat reinit
/wemc chat status
/wemc chat history
/wemc chat cache status
/wemc chat cache on
/wemc chat cache off
/wemc chat cache clear
```

### Flow mode

Flow mode is the default. It starts a bounded state machine that can execute a WCL step, collect server chat/game messages, and send that result back to the model for the next step.

- A `wemc-plan` response pauses for `/wemc flow approve` or `/wemc flow cancel`.
- A Flow response containing WCL but no plan compiles and executes immediately; it does not use the Single-mode approval queue.
- A line containing only `<eof>` marks the last WCL step.
- Defaults are capped at **30 AI requests**, **50 server steps**, and an **8-second** per-step response timeout (configurable within fixed limits).

```mcfunction
/wemc operation flow
/wemc chat build a small stone platform near me
/wemc flow status
/wemc flow approve
/wemc flow cancel
```

## WEMC Command Language (WCL)

AI-executable output is WCL, not a raw command transport. A WCL program must be inside a multi-line fenced block:

````markdown
```wcl
i in [0..4] {
  setblock ~$i ~ ~ minecraft:stone
}
```
````

The current line-preserving compiler supports:

- Vanilla Minecraft command lines (the `/` prefix is optional and removed before sending).
- Native inclusive loops: `i in [START..END] { ... }`.
- Loop-variable substitution with `$i` or `${i}`.
- `//` and `#` comments.
- `echo <text>` compile-time messages.
- Random substitutions: `~<random(min,max)>`, `<random([a,b,c])>`, and `<random({a:weight,b:weight})>`.
- A maximum of **1,000** loop iterations and **1,000** compiled commands per WCL program.

Do **not** use `for`, `repeat`, `while`, or `#for` as WCL syntax; only the native range-loop grammar is supported. Raw command-block syntax is also not supported. Ordinary `/execute` command lines can be compiled and pass through the blacklist unless they invoke a blocked nested control; `/function` and `/schedule` are blocked by the execution gate.

`seed "..."` is accepted by the parser, but it does not currently reseed the compiler's random generator after compilation begins. Do not rely on it for reproducible random output in this version.

## Command blacklist

WEMC uses a **blacklist**, not a closed allow-list. After WCL compilation, ordinary vanilla and modded command roots pass through to the active server. The blacklist blocks server administration, persistence, scheduled/function execution, command-block, and network-management controls.

The catalog in the **Commands** tab and `/wemc command list` remains useful as agent guidance, but it is not an execution allow-list. Turning a catalog category off removes that category from the displayed guidance; it does not block a matching compiled command under the current blacklist gate.

| Curated catalog category | Example guidance commands |
|---|---|
| Query | `time query`, `data get`, read-only `clear ... 0` |
| World state | `time set`, `time add`, `weather` |
| Inventory | `give`, clearing `clear`, `item replace` / `item modify` |
| World edit | `setblock`, `fill`, `clone`, block/storage `data` mutations |
| Entity | Exact `tp @s ~ ~ ~`, `summon`, `kill`, `tag` |
| Player state | `effect`, `experience`, `gamemode` |
| Presentation | `particle`, `playsound`, `title` |

Inspect the live command catalog with:

```mcfunction
/wemc command list
/wemc command list 2
```

Commands such as `/gamerule`, general `/tp` forms, `/execute ... run ...`, and modded command roots are not blocked by WEMC itself; the connected server remains authoritative. The blacklist blocks roots including `op`, `deop`, `ban`, `banlist`, `pardon`, `whitelist`, `kick`, `stop`, `reload`, `restart`, `save-all`, `save-off`, `save-on`, `publish`, `transfer`, `function`, `schedule`, `return`, `datapack`, `forceload`, `jfr`, `perf`, and `debug`.

`execute ... run ...` is allowed unless it attempts to invoke a blocked nested control such as `function`, `schedule`, `return`, `datapack`, `command`, or `commandblock`.

You can also issue one manual command through the blacklist gate without AI:

```mcfunction
/wemc command run time set noon
```

This direct path still uses the blacklist gate, but intentionally bypasses the AI approval queue.

## Settings and providers

`/wemc config` opens the in-game configuration panel. The current implementation supports these provider IDs:

`openai`, `ollama`, `claude`, `gemini`, `deepseek`, `minimax`, `minimax_cn`, `xai`, `mistral`, `cohere`, `perplexity`, `azure`, `custom`, and `copilot`.

Provider and model commands are also available in chat:

```mcfunction
/wemc provider list
/wemc provider use custom
/wemc model list
/wemc model use custom:your-model-id
/wemc status
```

Each provider keeps a separate selected-model setting. Configuration is saved in Fabric's config directory as `worldedit-magician.json`; it can contain API keys/access tokens in plaintext, so protect that file accordingly. Command-category permissions are saved separately in `worldedit-magician-command-permissions.json`.

The built-in `openai` provider sends ordinary chat through OpenAI's `/responses` endpoint and requires an API project key. It does not use a ChatGPT website session or subscription as an API credential.

## Command reference

| Command | Purpose |
|---|---|
| `/wemc` or `/wemc config` | Open the WEMC settings panel. |
| `/wemc status` | Show active provider/model, reasoning effort, output/context limits, and approval mode. |
| `/wemc operation single\|flow\|status` | Change or inspect AI operation mode. |
| `/wemc approval ask\|approve` | Queue Single-mode commands for review or send them immediately. |
| `/wemc chat <prompt>` | Send a prompt; the Flow mode is multi-step, while Single mode needs `/wemc chat init` first. |
| `/wemc command list [page]` | Show the curated command catalog used for agent guidance. |
| `/wemc command history` | Show commands WEMC actually sent during this game session. |
| `/wemc command wcl-history` | Show generated WCL and compiled command counts from this session. |
| `/wemc agent commands\|run\|discard` | Inspect, send, or discard the pending Single-mode command batch. |
| `/wemc flow approve\|cancel\|status` | Control an active Flow workflow. |
| `/wemc command run <command>` | Send one manual command after blacklist validation. |
| `/worldeditmagician config` | Legacy alias for the WEMC settings panel. |
| `/worldeditmagician worldedit` | Open the WorldEdit detection/status screen. |

`/wemc query time` and `/wemc query entity` currently print vanilla-syntax hints only; they do not run an in-game query on the player's behalf.

## Current limitations

- **No selection enforcement yet.** Confirmed chunks and the Y range are not used to block out-of-selection block edits at execution time.
- **No undo/redo.** Command history is informational; it does not implement server rollback.
- **No WorldEdit integration.** WorldEdit is detected but not required, invoked, or used to edit the world.
- **No server-side authority.** WEMC only sends vanilla commands through the normal client connection. Server permissions, game rules, plugins, and command support decide the real outcome.
- **Not an allow-list.** The blacklist intentionally permits ordinary vanilla and modded commands; use approval mode and server permissions as your primary safety boundary.
- **Blocked command families.** Server/admin, persistence, function/scheduling, command-block, and network-management controls are rejected by the blacklist gate.
- **WCL is a compact, limited language.** Only the implementation-listed loop/substitution/random helpers are supported; do not assume generic scripting, conditionals, assignments, patterns, or shape helpers are executable.
- **Random seeds are not yet reproducible.** As noted above, `seed` is parsed but does not reset the active random source.

## Building from source

Use Java 21 and the Gradle wrapper:

```bash
./gradlew remapJar
```

The remapped Fabric JAR is produced under `build/libs/`. For development, use the Gradle run configurations supplied by Fabric Loom.

## License

This project currently declares the [CC0-1.0](LICENSE) license.