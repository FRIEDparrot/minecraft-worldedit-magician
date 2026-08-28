# worldedit-magician — Agent-Driven Minecraft World Editing

A Fabric client mod (Kotlin) for Minecraft 1.21.11 that connects the game world to an LLM agent. The long-term goal (see `IDEA.md`): the agent designs and builds structures, tests redstone machines, reads world/entity state, controls tick speed, and verifies its own builds — like a WorldEdit-powered "builder magician" living inside the game.

## Current State

The mod today is an **AI chat client with provider management** — it has **no world interaction yet**:

- Talks to 6 LLM providers (OpenAI, Ollama, Claude, Gemini, DeepSeek, Copilot) over plain HTTP (no SDKs).
- Persists provider credentials/models to `config/worldedit-magician.json` (keys stored locally, never in URLs).
- Chat UI: `/wemc msg <prompt>` command, an in-game settings screen, and an `O` keybinding.
- Tracks an `ApprovalMode` (ASK / APPROVE) setting — the future gate for agent-initiated world writes.
- Detects whether the WorldEdit mod is installed (status screen + startup check) — only loader-level detection, not API integration.

World interaction (block reads/writes, Litematica, tool-calling agent loop) is the next milestone — see the plan in the repo discussion / next session.

## Tech Stack

| Thing | Value |
|---|---|
| Minecraft | 1.21.11 (official Mojang mappings) |
| Loader | Fabric 0.19.3 |
| Language | Kotlin 2.4.10 (fabric-language-kotlin), Java 21 target |
| Fabric API | 0.141.5+1.21.11 |
| Build | Gradle 9.5.1 + fabric-loom 1.17-SNAPSHOT (`gradlew`) |
| JSON | Gson (settings/config) + `java.net.http.HttpClient` for AI calls |

## Build & Run

- `gradlew build` — compile + remap.
- `gradlew runClient` — launches the dev client; dev environment has WorldEdit 7.4.2 + WorldEditCUI preloaded in `run/`.
- `gradlew runDatagen` — fabric datagen (client-side generator registered).
- No test source set exists yet.

## Source Layout

```
src/
├── main/kotlin/com/magician/worldedit/
│   └── WorldeditMagician.kt          # ModInitializer entry; MOD_ID + Identifier helper only (no logic yet)
├── main/resources/
│   ├── fabric.mod.json               # entrypoints (main/client/datagen), mixins, deps
│   └── worldedit-magician.mixins.json
├── client/kotlin/com/magician/worldedit/client/
│   ├── WorldeditMagicianClient.kt    # Client entry: /wemc command tree, keybinding, screen openers
│   ├── WorldeditMagicianDataGenerator.kt
│   ├── config/
│   │   ├── OpenAiSettingsStore.kt    # OpenAiSettings data class + AiProvider/ApprovalMode enums + JSON persistence
│   │   ├── AiChatClient.kt           # Async chat call per provider → AiChatResult (Success/Failure)
│   │   ├── AiModelCatalog.kt         # Async /models listing per provider → AiModel list
│   │   ├── AiConnectionTester.kt     # Async connection test per provider
│   │   ├── CopilotProviderSupport.kt # Copilot guidance/fallback behavior
│   │   ├── WorldEditInstallation.kt  # WorldEdit mod presence + version detection
│   │   └── OpenAiConnectionTester.kt # Legacy tester (see AiConnectionTester)
│   └── screen/
│       ├── OpenAiSettingsScreen.kt           # Main agent/provider settings GUI (269 lines)
│       ├── WorldEditConfigurationScreen.kt   # WorldEdit install status display
│       ├── OpenAiConnectionTestScreen.kt     # Connection test UI
│       └── ConfigurationScreen.kt            # Legacy settings screen
└── client/resources/                  # lang file, client mixins json
```

## Key Conventions

- **Commands**: everything hangs off `/wemc` (alias `/worldeditmagician`): `config`, `status`, `provider list|use`, `model list|use`, `msg`, `approval ask|approve`.
- **Async pattern**: every AI call returns `CompletableFuture<SealedResult>` (Success/Failure); UI/command callbacks re-dispatch to the Minecraft client thread via `Minecraft.getInstance().execute {}`.
- **Provider dispatch**: exhaustive `when (provider)` over `AiProvider.entries` everywhere — adding an enum value means updating every `when` in the same change (see Mistake Log).
- **Models are provider-scoped**: each provider keeps its own selected model; switching providers loads that provider's model or an empty selection.
- **Credentials**: stored locally in `run/config/worldedit-magician.json`; sent only via auth headers, never query strings.
- **Rendering API**: GUI text colors must be opaque ARGB (`0xFFRRGGBB.toInt()`); plain RGB is fully transparent here.

## Documentation

The important functions, interfaces and core classes that have relatively packaged functions should all be documented (KDoc on public API, doc comments on non-trivial helpers).

## Mistake Log

- Minecraft GUI text colors must use opaque ARGB values. RGB values such as `0xFFFFFF` have zero alpha in this rendering API and make text invisible; use `0xFFFFFFFF.toInt()` for white and equivalent `0xFFRRGGBB.toInt()` values for colored text.
- When adding an `AiProvider` enum value, update every exhaustive `when` expression in the client in the same integration change before running the build.
- Verify Minecraft widget APIs against the target mappings before relying on a control pattern. The `CycleButton` builder signature in this target differed from the assumed overload; use a simple paged or direct-button control when the mapped API is uncertain, then compile immediately.
- The target Minecraft version uses the four-argument `mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)` callback. Do not assume the legacy one-argument scroll override; compile UI overrides against the current mappings immediately.
- `CompletableFuture` is invariant in Kotlin. When a branch returns a subtype such as `Failure`, explicitly type immediate futures and `runCatching` results as the sealed result interface to preserve the declared API return type.
- Keep selected models provider-scoped. Never use one provider's model as another provider's fallback; a provider switch must load that provider's saved model or an empty selection.
- `EditBox` defaults to a 32-character maximum. Set a credential field's maximum length before assigning its saved value; assigning first silently truncates the value and can persist the damaged credential.
- Never put API keys or access tokens in URL query strings. Use the provider's supported authentication header so credentials are not retained by URLs, proxy logs, or diagnostics.
- An asynchronously loaded UI result must be applied only to the current draft state; do not reopen a screen from the stale snapshot captured before the request started, because that discards concurrent player edits.
- Do not label a loaded dependency as compatible solely because its mod ID is present. Inspect its declared Minecraft version constraint, or state that only loader compatibility has been confirmed.
- Whenever an implementation mistake is identified, record the cause and the preventative rule in this file before completing the task.
- A malformed nested KDoc comment can comment out declarations and surface as unrelated unresolved references. Keep KDoc delimiters balanced, and compile the owning source file immediately after editing documentation around declarations.
- Chunk block estimates must include the full 16x16 horizontal footprint as well as the configured Y range; counting only Y levels underreports the operated volume by a factor of 256.
- Fabric interaction callbacks pass an `ItemStack` for the held item. Keep helpers aligned with the callback type, then inspect the stack's `item` when matching a tool.
- Official Mojang mappings use `Player`, not `PlayerEntity`; in this target `Level.getEntities` requires an entity to exclude before the bounds and predicate.
- Do not declare a `setX` function beside a mutable Kotlin property named `x`; both emit the same JVM setter signature. Use a distinct verb such as `changeX` when state-transition behavior is required.
- `KeyMapping.Category.register` rejects duplicate identifiers during class initialization. Register each category once and reuse the returned category object for every keybinding in it.
- In Minecraft 1.21.11, `LevelRenderer.collectPerFrameGizmos()` installs the per-frame collector by calling `Gizmos.withCollector()` before returning. Emit custom gizmos from a `RETURN` injection while that temporary collection is still open; a `HEAD` injection runs before collector installation and crashes as soon as any gizmo is submitted.
- World-changing commands must be sent through the active server connection after whitelist validation; do not mutate client `Level` state through reflection, and never claim a server command is reversible without a server-side rollback mechanism.
- Before adding, changing, or documenting any vanilla Minecraft command for WEMC, look up its current Java Edition syntax on the Minecraft Wiki (`minecraft.wiki`) first. Do not invent command roots or incomplete forms: `/entity query` is not a vanilla command, `/time query` requires a concrete query argument (for example `daytime` or `gametime`), and entity NBT lookup uses `/data get entity <single-target> [path]`. Record any command-syntax mistake and its prevention here before finishing the change.


## Creations 
- Since it's a mod fully developed by agent, for the functions I ask to tell you. If you come up with the new functions, you should consider if adding it is worth (If it's worth.
You can add it without my permission (but please discuss and search some infos first)).
- For the functions, I may ask you to add one function that serve for a specific purpose. But maybe there are many related functions, we need to add more considerations here. If it's really worth, don't be afraid to modify the code directly. 
