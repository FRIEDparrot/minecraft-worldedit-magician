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

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
