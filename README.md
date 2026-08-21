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
5. In approval mode, inspect with `/wemc agent commands`, then execute with `/wemc agent run`.

Only command families listed by `/wemc agent commands` can be sent. A normal chat response without a `wemc-commands` block is never executed. A batch is capped at 100 commands; larger work must be planned as a separate flow rather than automatically over-executed.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
