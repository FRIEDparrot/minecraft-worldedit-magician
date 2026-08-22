# worldedit-magician

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## Chunk Selection

The standard torch is the chunk selection tool. While holding it, an in-world WEMC panel shows the active operation, shape, Y range, selection count, and controls.

- Press `Ctrl+C` to cycle the confirmed operation: Replace, Add, then Delete.
- Press `Ctrl+V` to switch between targeting one chunk and defining a rectangle with two corners.
- Hold Ctrl and left-click a block with a torch to target its chunk. In two-corner mode, left-click once to anchor the first corner; use the wheel to move the orange adjustable corner in the direction you face, or left-click another block to place it directly.
- Right-click with the torch to confirm the orange draft. Right-click never cancels.
- Press `Delete` to cancel the current draft and clear the confirmed selection.
- Hold Ctrl and use the scroll wheel to move the complete Y-range band up or down. The first selected block initializes the range from its Y level through Y+20.

The selected operation is captured when the area is prepared. Changing mode or shape discards an unfinished draft, so it cannot be committed under a different operation.

## AI chat and command execution

Use `/wemc chat <message>` for conversational requests. The agent may answer normally; it may request Minecraft execution only by placing commands in an explicit fenced block:

```wemc-commands
time set noon
```

For the time-setting smoke test:

1. Configure an AI provider and model with `/wemc config`.
2. Set `/wemc approval ask` to review generated commands, or `/wemc approval approve` to send validated commands automatically.
3. Run `/wemc chat set the world time to noon`.
4. Confirm the response contains `time set noon` in a `wemc-commands` block.
5. In approval mode, inspect with `/wemc command list`, then execute with `/wemc agent run`.
6. Use `/wemc command history` to review WEMC commands sent during this session.

### Operation modes

Open `/wemc config` → **Agent Operation** to select a mode:

- **Single** (default) sends exactly one AI request for each `/wemc chat` command.
- **Flow** permits a bounded follow-up AI request after a player-approved self-position probe. Enable **Self-position query** in the same page, then WEMC may send its fixed probe `tp @s ~ ~ ~`; it waits for the server's teleport feedback and provides the resolved coordinates only to the next flow step. Use `/wemc flow approve`, `/wemc flow status`, or `/wemc flow cancel` while a flow is active.

Flow does not allow raw `/tp`, `/teleport`, or `/execute` in agent command blocks. `/execute` remains unavailable because it can wrap and relocate block-changing commands outside WEMC's confirmed-chunk/Y-range guard.

Only command families listed by `/wemc command list` can be sent. A normal chat response without a `wemc-commands` block is never executed. Command transport blocks are hidden from the displayed AI reply. A batch is capped at 100 commands; larger work must be planned as a separate flow rather than automatically over-executed.

Block-changing commands (`setblock`, `fill`, `clone`, block-targeted `data` edits, and block-targeted `item` edits) require one or more confirmed chunks. The orange selection draft is not enough: confirm it with the torch first. Targets must remain in confirmed chunks and within the configured inclusive Y range.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
