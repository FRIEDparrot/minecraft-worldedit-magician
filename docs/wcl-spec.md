# WEMC Command Language (WCL) — Specification

## Overview

WCL is a domain-specific language for expressing Minecraft WorldEdit commands that involve repetition, parameterization, or conditional logic. Instead of the agent generating `setblock` 100 times, it writes a WCL program that the WCL pipeline compiles into concrete Minecraft commands.

**Pipeline:** `wemc block` → Lexer → Parser → TypeChecker → Compiler → Minecraft commands → Executor

**Execution flow:**
```
Agent response (```wemc ... ```)
  → FlowResponseParser.extracts wclSource
  → WclPipeline.run() → WclLexer → WclParser → WclTypeChecker → WclCompiler
  → Success: List<String> of MC commands → MinecraftCommandExecutor
  → Failure: WclError list → sent back to agent for correction
```

---

## WCL Grammar

### Lexical Structure

```
Identifiers:  [a-zA-Z_][a-zA-Z0-9_]*   (case-sensitive)
Variables:   $name or ${name}            (prefixed with $)
Numbers:     -?[0-9]+                   (Long)
Strings:     "..."                       (double-quoted)
Comments:    // ... or # ...
Newlines:    significant (separate statements)
```

### Keywords

`in`, `from`, `to`, `step`, `echo`, `probe`, `seed`, `random`, `volume`, `shell`, `line`, `if`, `else`, `true`, `false`, `int`, `str`, `block`

### Built-in Variables (resolve at compile time from player position)

| Variable | Value |
|---|---|
| `X` | Player block X |
| `Y` | Player block Y |
| `Z` | Player block Z |
| `Y_MIN` | World minimum Y |
| `Y_MAX` | World maximum Y |
| `CHUNK_X` | Player's chunk X (playerX >> 4) |
| `CHUNK_Z` | Player's chunk Z (playerZ >> 4) |

### Expression Operators

**Integer:** `+`, `-`, `*`, `/`, `%`
**Comparison:** `==`, `!=`, `<`, `>`, `<=`, `>=`
**String:** only `+` (concatenation)

---

## Statements

### 1. Minecraft Command (most common)

Any line not recognized as a keyword is treated as a Minecraft command.

```
setblock ~0 ~0 ~0 stone
fill ~ ~ ~ ~10 ~5 ~10 stone hollow
```

Variables substitute with `$name` or `${name}`:
```
setblock ~$x ~$y ~$z stone
```

### 2. Loop — Range (`i from START to END [step STEP]`)

```wcl
y in [0..3], x in [0..10] {
    setblock x y z stone
}
```

Ranges: `start..end` (inclusive), `[start..end]` (inclusive), `[start..<end)` (exclusive end).
Loop variable is available as `$variable` inside the body.

### 3. Loop — Enumeration

```wcl
x in [0, 2, 5, 10] {
    setblock x 64 z stone
}
```

### 4. Variable Assignment

```wcl
n = 10
fill ~ ~64 ~ ~$n ~64 ~$n stone
```

### 5. Echo (for debugging / feedback)

```wcl
echo "Building wall with height: " + $h
```

Echo lines appear as warnings in the compile result and can be displayed in chat.

### 7. Seed (reproducible randomness)

```wcl
seed "mywall"
r = random(0, 255)
setblock ~$r ~64 ~ stone
```

Same seed string → same sequence of random values.

### 7b. Random Coordinate Offset

In Minecraft command coordinates, use `~<random(LO, HI)>` to generate a random integer offset at compile time:

```
~<random(-5, 5)>   → e.g. ~3  (a random offset between -5 and +5)
```

**Examples:**

```wcl
// Summon a pig at a random position within ±5 blocks horizontally
summon minecraft:pig ~<random(-5,5)> ~ ~<random(-5,5)>

// Fill a 10x10 area with random stone variants
fill ~<random(-5,5)> ~64 ~<random(-5,5)> ~<random(5,15)> ~68 ~<random(5,15)> stone
```

**Rules:**
- `~<random(LO, HI)>` must be used INSIDE a coordinate position (starts with `~`)
- Both `LO` and `HI` must be integer literals (positive or negative)
- The result is substituted at compile time (same random value every time for same seed)
- Use `seed "name"` before random calls to lock the sequence

### 7c. Random Item Selector

**Uniform random pick** — `random([a, b, c])` picks one item at random:

```wcl
fill ~ ~64 ~ ~10 ~64 ~10 stone,<random([cobblestone, mossy_cobblestone, andesite])>
```

**Weighted random** — `random({a: 60, b: 40})` picks with probability proportional to weight:

```wcl
setblock ~ ~64 ~ <random({stone: 70, dirt: 20, gravel: 10})>
```

**Random mobs** (summoning):

```wcl
// Summon a random hostile mob at spread-out position
summon <random([minecraft:zombie, minecraft:skeleton, minecraft:spider])> ~<random(-5,5)> ~ ~<random(-5,5)>
```

**Rules:**
- `random([...])` — uniform probability
- `random({...})` — weights are integers; sum should be > 0
- Items can be block IDs, entity IDs, or any quoted string
- The selected value is substituted at compile time (deterministic per seed)

### 7. Probe (position query without counting toward command limit)

```wcl
probe tp @s ~ ~ ~      // sends /tp @s ~ ~ ~ but does NOT count toward MAX_COMMANDS
```

### 8. Pattern Definition and Call

```wcl
pattern wall(h: int, len: int) {
    y in [0..$h] {
        x in [0..$len] {
            setblock x y 0 stone
        }
    }
}

// Call it:
wall(h=5, len=20)
```

Parameters are bound by name (preferred) or by position.

### 9. Conditional (`if`)

```wcl
if $count > 10 {
    echo "Large operation!"
}
else {
    echo "Small operation"
}
```

Condition must be a comparison (==, !=, <, >, <=, >=).

### 10. Shape Helpers

#### volume(cx, cy, cz, w, h, d, block)
Filled rectangular prism centered at (cx, cy, cz).

```wcl
volume($X, 64, $Z, 10, 5, 10, stone)
```

#### shell(cx, cy, cz, w, h, d, block)
Hollow shell (only the surface blocks).

```wcl
shell($X, 64, $Z, 8, 8, 8, stone hollow)
```

#### line(x1, y1, z1, x2, y2, z2, block)
Bresenham's 3D line from (x1,y1,z1) to (x2,y2,z2).

```wcl
line(0, 64, 0, 20, 64, 20, stone)
```

---

## Error Handling

| Error Type | Cause | Agent Action |
|---|---|---|
| `Syntax` | Lexer/parser failure | Fix WCL syntax and retry |
| `Safety` | Exceeds MAX_COMMANDS (1000) or MAX_FILL_VOLUME (32768) | Rewrite with smaller ranges |
| `Unknown` | Undefined variable, unknown pattern/function | Define the missing item |

Errors are returned as `WclPipeline.Result.Failure` and sent back to the agent in the next prompt for correction.

---

## Chat Interaction

The in-game chat messages support:

### Right-Click to Copy

Chat messages that contain commands or WCL code display a `© Copy` hover tooltip.
When the player clicks the message, the content is copied to clipboard.

Implementation: Fabric `Text` components with `hoverEvent` showing a "Copy" tooltip,
and `clickEvent` with `COPY` action containing the text. This is applied to:
- Each displayed Minecraft command
- The full generated command list (in debug mode)
- WCL source code (when shown)

### Debug Mode Display

When `AgentOperationSettings.debugMode = true`:
1. **Pre-execution**: Shows "WCL compiled to N command(s)" + first 10 commands
2. **Echo lines**: `[ECHO] ...` messages from `echo` statements
3. **Post-execution**: Summary of executed commands

Example debug output:
```
[WEMC DEBUG] WCL compiled to 44 command(s):
  /setblock ~0 ~0 ~0 stone
  /setblock ~1 ~0 ~0 stone
  ... and 42 more
[WEMC] Step 1: 44 WCL command(s) sent; monitoring...
```

### WCL Source Display

When WCL is compiled, the original WCL source is stored and can be displayed
on request (e.g., via a chat command like `/wemc last wcl`).

---

## Safety Limits

| Limit | Value | Trigger |
|---|---|---|
| `MAX_COMMANDS` | 1000 | Abort if generated command list exceeds this |
| `MAX_FILL_VOLUME` | 32768 (32³) | Abort if a `volume()` or `shell()` would exceed this |

---

## Debug Mode

When `AgentOperationSettings.debugMode = true`:
- Shows compiled WCL command count before execution
- Shows first 10 generated commands
- Shows echo output (`[ECHO] ...`) lines

---

## Protocol Integration

In FLOW mode, the agent returns WCL code in a `wemc` block:

```
I'm building a wall.

```wemc
y in [0..4], x in [0..9] {
    setblock ~x ~y ~ stone
}
```

<eof>
```

The WCL compiler generates concrete commands, then `MinecraftCommandExecutor.execute()` sends them to the server.

If WCL has errors: error report is sent back to the agent, which should respond with corrected WCL code.

---

## WCL vs Legacy `wemc-commands`

`wemc` (WCL) is the **preferred** format. The old `wemc-commands` format (raw one-per-line commands) is still supported for backward compatibility but will eventually be phased out.

The `FlowResponseParser` checks for `wemc` blocks **first**, then falls back to `wemc-commands` for legacy responses.
