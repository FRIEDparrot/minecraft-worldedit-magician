This should be a plugin that full developed by Agent

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
