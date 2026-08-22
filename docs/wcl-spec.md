# WEMC Command Language (WCL) — Specification

## Overview

WCL is a domain-specific language for expressing Minecraft WorldEdit commands that involve repetition, parameterization, or conditional execution. It compiles down to a sequence of concrete `/` commands.

WCL is **proposed by the agent** when a player request involves repetitive patterns, or **written directly by the player**. A WCL program must be reviewed by multiple plan agents before execution.

---

## Design Principles

1. **No repetition in source** — a loop replaces 100 `setblock` calls
2. **Explicit enumeration** — random parameters must be declared, seeded, bounded
3. **Safe by default** — agents review every WCL program before it runs
4. **Human-readable output** — the compiled command list is shown to the user before execution
5. **Chunk-aware** — selection mode and Y range are inherited from context

---

## Language Reference

### 1. Primitives

```wcl
// Single command (no loop)
setblock ~ ~ ~ stone

// Comment
// This places stone in a line
setblock ~+1 ~ ~ stone
```

### 2. Enumerated Values

```wcl
// Explicit list — expands to N concrete values
x in [0, 1, 2, 3] {
    setblock x ~ ~ stone
}
```

### 3. Range Loops

```wcl
// Integer range [start, end) — step defaults to 1
i from 0 to 10 {
    setblock ~+i ~ ~ stone
}

// Step can be specified
i from 0 to 10 step 2 {
    setblock ~+i ~ ~ stone
}

// Negative step
i from 10 to 0 step -1 {
    setblock ~+i ~ ~ stone
}
```

### 4. Chunk Iteration

```wcl
// Iterate over selected chunks
chunk {
    setblock ~0 ~0 ~0 stone
}

// Iterate over a sub-region within each chunk (16xYx16)
x in [0..16), z in [0..16) {
    setblock ~+x ~ ~+z stone
}
```

### 5. Y-Range Iteration

```wcl
// From bottom to top of current Y range
y from Y_MIN to Y_MAX {
    setblock ~0 ~+y ~0 stone
}
```

### 6. Dimension Iteration (nested)

```wcl
x in [0..5], z in [0..5], y in [0..3] {
    setblock ~+x ~+y ~+z stone
}
```

### 7. Parametric Patterns

```wcl
// Pattern with named parameters
pattern wall(height: int, length: int) {
    y in [0..height), x in [0..length) {
        setblock ~+x ~+y ~ stone
    }
}

// Instantiate with arguments
wall(height=5, length=10)
```

### 8. Conditionals

```wcl
// Only execute if condition is true
count > 10 {
    setblock ~ ~ ~ redstone_block
}

// Multiple branches
mode == "fill" {
    fill ~ ~ ~ ~10 ~10 ~10 stone
}
mode == "outline" {
    // hollow outline
    fill ~ ~ ~ ~10 ~10 ~10 stone hollow
}
```

### 9. Seeds and Randomness

```wcl
// Fixed seed for reproducibility
seed "mywall42"

// Random integer in [min, max] with optional seed
r = random(0, 255, seed="mywall42")

// Use random in block type
block = [stone, cobblestone, brick][r % 3]
setblock ~ ~ ~ block
```

### 10. Meta-commands

```wcl
// Print something during execution (not a Minecraft command)
echo "Placing stone at ~ ~ ~"
echo "Y range: ${Y_MIN} to ${Y_MAX}"

// Include a coordinate probe
probe tp @s ~ ~ ~   // result stored in last_tp_result
x = last_tp_result.x
```

### 11. Block Placement Styles

```wcl
// Fill a volume
volume(0, 0, 0, 10, 5, 10, stone)

// Hollow shell
shell(0, 0, 0, 10, 10, 10, stone)

// Sphere (approximated)
sphere(center_x, center_y, center_z, radius, stone)

// Line between two points
line(~0 ~0 ~0, ~10 ~10 ~10, stone)
```

### 12. Variables

```wcl
n = 10
height = 5
y in [0..height) {
    x in [0..n) {
        setblock ~+x ~+y ~ stone
    }
}
```

### 13. Named Commands (macros)

```wcl
// Define a reusable named command
cmd fill_column(x: int, z: int) {
    y in [Y_MIN..Y_MAX] {
        setblock x y z stone
    }
}

// Call it
fill_column(x=0, z=0)
fill_column(x=1, z=0)
```

---

## Built-in Variables

| Variable | Value |
|---|---|
| `X`, `Y`, `Z` | Player's current block position |
| `CHUNK_X`, `CHUNK_Z` | Player's current chunk coordinates |
| `Y_MIN` | Configured Y range lower bound |
| `Y_MAX` | Configured Y range upper bound |
| `SELECTION_SIZE` | Number of selected chunks |
| `last_tp_result` | Result of last `probe tp @s` |

---

## Compilation

A WCL program is compiled to a flat list of Minecraft commands:

```wcl
y in [0..3], x in [0..5] {
    setblock x y z stone
}
```

Compiles to:
```
setblock ~+0 ~+0 ~ stone
setblock ~+1 ~+0 ~ stone
setblock ~+2 ~+0 ~ stone
setblock ~+3 ~+0 ~ stone
setblock ~+4 ~+0 ~ stone
setblock ~+5 ~+0 ~ stone
setblock ~+0 ~+1 ~ stone
...
```

---

## Agent Review Protocol

When the agent proposes WCL (either proactively or after player request), a **multi-agent review** runs before the WCL is shown to the user:

### Reviewer Agents

1. **Architect Agent** — checks structural soundness: Are loops bounded? Are variables used before assignment? Are dimensions consistent?
2. **Safety Agent** — checks for dangerous patterns: Does the WCL potentially affect too many blocks? Are there `/fill` calls that could crash the server? Is `/setblock` being used with player-placed coordinates?
3. **Optimization Agent** — checks for redundancies: Can nested loops be flattened? Can the WCL be shortened? Are there duplicate commands that could be collapsed?

### Review Output

Each agent produces a short report:

```
ARCHITECT: OK — all loops bounded, 3 dimensions resolved
SAFETY: WARN — 14400 blocks affected by volume(0,0,0,10,10,10). Confirm intent?
OPTIMIZATION: SUGGEST — consider using shell() instead of volume() for hollow structure
```

The review is shown to the user **before execution** (or before plan approval in Flow mode).

---

## Protocol Integration

### Agent Proposes WCL

If the agent, while processing a prompt, detects that the task would require 5+ similar commands, it can propose:

```
I've identified a repetitive pattern. I can express this as a WCL program:

wcl
y in [0..3], x in [0..5] {
    setblock x y z stone
}
end_wcl

This will be reviewed by the planning agents before you approve.
```

### Player Writes WCL Directly

Player types:
```
/wemc exec
wcl
x in [0..10] {
    setblock ~+x ~ ~ stone
}
end_wcl
```

The WCL is intercepted by the client, routed to the multi-agent reviewer, then shown to the user for confirmation.

### Continuation Prompt for WCL

Only sent ONCE on first WCL encounter:

```
One-time context: WEMC Command Language (WCL) — brief reference
============================================================
WCL is a domain-specific language that compiles to Minecraft commands.

LOOPS:   x in [0..10) { ... }   or   i from 0 to 10 step 2 { ... }
RANGES:  [0, 1, 2] or [0..16) (exclusive end)
VARS:    n = 10, then use $n or ${n}
PATTERNS: pattern name(args) { }  then  name(args)
CONDITIONALS: count > 5 { ... }
SEEDS:   seed "myseed", r = random(0,255,seed="myseed")
VOLUME/SHELL: volume(x,y,z,w,h,d,block), shell(...)
PROBE:   probe tp @s  // stores result in last_tp_result

Compile with: /wemc compile <wcl_program>
Review with:  /wemc review <wcl_program>
```

---

## WCL Grammar (PEG)

```peg
Program       = Statement*
Statement     = Comment / Loop / PatternDef / PatternCall / Assign / Conditional / Meta / CommandBlock

Comment       = '//' (!'\n')* '\n'

Loop          = Identifier 'in' Range       '{' Program '}'
              / Identifier 'from' Number 'to' Number ('step' Number)? '{' Program '}'
Range         = '[' (Number ('..' Number | (',' Number)*)) ']'
              / '(' (Number ('..' Number | (',' Number)*)) ')'

PatternDef    = 'pattern' Identifier '(' ParamList? ')' '{' Program '}'
ParamList     = Identifier ':' Type (',' Identifier ':' Type)*
PatternCall   = Identifier '(' ArgList? ')'
ArgList       = Identifier '=' Expr (',' Identifier '=' Expr)*

Assign        = Identifier '=' Expr

Conditional   = Expr '{' Program '}' ('else' '{' Program '}')?

Meta          = 'echo' String
              / 'probe' MinecraftCommand

CommandBlock  = MinecraftCommand           // single line, no block markers needed

Type          = 'int' / 'str' / 'block'
Expr          = Identifier / Number / String / MathExpr / CallExpr
MathExpr      = Expr ('+' / '-' / '*' / '/' / '%') Expr
CallExpr      = Identifier '(' ArgList? ')'

Identifier    = [a-zA-Z_][a-zA-Z0-9_]*
Number        = [0-9]+
String        = '"' (!'"' .)* '"'
```

---

## Rejected Patterns (Safety)

The WCL compiler MUST reject programs that would generate more than `MAX_COMMANDS_PER_FLOW` (default: 1000) concrete commands. If a WCL program would generate more, it must be rejected with a clear error:

```
WCL ERROR: Program generates 10000 commands (limit: 1000).
Use a smaller range or chunk selection.
```

Additionally, these are rejected at compile time:
- `fill` with volume > 32³ (server-safe limit)
- `setblock` in coordinates outside player's known bounds without explicit confirmation
- `/gamerule` or `/difficulty` — blocked entirely
- `/stop`, `/kill @e` without confirmation

---

## Summary of Changes

1. **Agent** — detects repetitive patterns, proposes WCL instead of raw commands
2. **Multi-agent reviewer** — Architect + Safety + Optimization agents review WCL before execution
3. **WCL compiler** — compiles to flat command list, enforces safety limits
4. **User approval** — WCL review output shown before execution
5. **Grammar** — PEG-based, simple to parse
6. **First-use context** — WCL reference sent once, then cached
