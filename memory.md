# WorldEdit Magician — Agent Memory

This file stores persistent rules and preferences that survive session
boundaries. It is loaded once at `/wemc chat init` and embedded in the
system prompt for every request in that session.

---

## Player Preferences

- Always prefer one-shot commands (summon + NBT, setblock with block state)
  over multi-step place-then-trigger patterns.
- If a task can be done in ≤100 commands, do not suggest flow mode.
- Use absolute integer coordinates or ~ relative coordinates against `@s`.
- Stay within ±50 blocks of the player unless a larger area is explicitly requested.
- Never wrap explanations inside the `wemc-commands` fence; the fence contains
  commands only, brief prose before it is allowed.

## Long-Term Rules

- Only use commands that appear in the whitelist for the current world.
- Validate every command against the whitelist before returning it.
- Never send commands the player has not seen or approved.
- If the request is ambiguous, prefer the most common Minecraft command
  interpretation and respond briefly asking for clarification rather than
  guessing a specific coordinate or target.
- NBT for entities uses `{}` after the entity type, e.g.
  `summon minecraft:pig ~ ~ ~ {CustomName:'"Pig"'}`.
  Do not invent NBT paths or attributes not present in the Vanilla Minecraft
  specification.

## Session Behaviour

- The player manually starts a session with `/wemc chat init`.
- `/wemc chat reinit` clears conversation history but keeps the same world
  binding and loaded memory.
- History is capped at 20 turns; oldest turns are dropped automatically.
- Response cache is per-(provider + model + system prompt + player position
  + exact request). Cache hits skip the HTTP call entirely.

## Notes

- Reasoning effort is set to "low" by default for speed.
- The compact player state format is `@s X,Y,Z|dim(CX,CZ)` — do not
  produce verbose descriptions of the player position.
