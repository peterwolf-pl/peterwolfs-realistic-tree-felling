# PeterWolf's Realistic Tree Felling

Fabric 26.2 mod that turns one axe break on a natural tree into a server-authoritative fall away from the player.

## Phase 1 gameplay

1. Find a natural vertical tree and hold an axe.
2. Break any trunk block normally.
3. The tree immediately rotates as one `FallingTreeEntity` and falls away from you (opposite your horizontal facing direction).
4. Its base block remains as a normal stump; the logs and connected canopy land together.

On impact, half of the captured leaves break into random debris: leaf blocks, sticks or matching branch logs. The other half stays in the fallen tree's crown zone (the far end of the trunk), placed flat without an elevated leaf mound, and then slowly disappears the way canopy does when a trunk is gone. If the tree falls into a chasm — no solid ground at or below the cut stump height along the fall path — the entire tree shatters into block items at the last solid edge.

Every landed log is marked as a fallen-tree section and is rotated along the actual fall direction. Chop one with an axe two or three times (position-determined); the final strike adds its log block directly to your inventory, dropping it beside you only when the inventory is full.

The falling animation uses the same direction vector as final placement and accelerates smoothly from the upright position into the landing direction. Its local origin is the cut/base block, so the tree grows above ground and rotates from that point rather than its middle. It submits the exact captured trunk, branch and leaf coordinates rather than a placeholder shape, while the whole local tree rotates together so individual log sections never turn sideways independently.

Sneak while breaking to preserve normal vanilla breaking (enabled by default). The initial vanilla axe break keeps its normal speed and durability behavior; the mod does not add a second durability loss. There is no notch, back-cut, or virtual hinge.

## Install

Place `build/libs/peterwolfs-realistic-tree-felling-1.0.8.jar` in both the client and dedicated-server `mods` directories with Fabric Loader 0.19.3+ and Fabric API 0.153.0+26.2. Use Java 25.

The first launch creates `config/peterwolfs-realistic-tree-felling.json`. Every setting requested for gameplay tuning is present there, including detection bounds, damage policy, preview toggles, landing behavior and impact rules.

## Commands

All commands require the game-master permission level:

- `/treefelling reload` reloads JSON configuration.
- `/treefelling config` shows the configuration location.
- `/treefelling debug on|off` enables or disables debug mode.
- `/treefelling inspect` reports the targeted tree's mass, height and away-from-player fall direction.
- `/treefelling preview` currently reports the same server-authoritative prediction data.
- `/treefelling cancel` is retained for command compatibility; single-cut felling has no staged state to cancel.
- `/treefelling forcefall` immediately fells a valid targeted tree away from you.

## Safety model

Tree discovery is a bounded breadth-first search over `#minecraft:logs`: it cannot scan more than `maximumTreeBlocks`, extends no higher than `maximumTreeHeight`, and rejects structures without a sufficient vertical trunk. After validation it flood-fills the complete connected `#minecraft:leaves` canopy from the trunk. The canopy boundary uses leaf type and the closest vertical trunk, so touching leaves from a neighboring tree remain in place. It treats all tag-based mod logs as logs. Before felling it accepts only a natural-looking, connected vertical trunk of at least two blocks, avoiding horizontal log walls by design.

The server detects the tree itself, chooses the opposite-to-player direction, removes blocks, ticks the falling entity and runs final placement. The client merely renders interpolated entity state. The falling entity saves its age, anchor, direction and every captured block, so a server restart resumes the transaction instead of silently losing a tree.

## Current Phase 1 limits

- The visual is a placeholder trunk-and-branch model with the vanilla oak-log texture; a face-specific wedge overlay and ghost landing preview are Phase 3 work.
- Falling currently completes after a short deterministic rotation; terrain ray collision, impact damage, protection-mod hooks and fragile-block destruction are configuration-ready but intentionally not active in Phase 1.
- Exact log state properties (axis/waterlogging) are retained during an uninterrupted fall. Restart recovery restores the registered block type with its default state, a conservative Phase 1 recovery fallback.
- The fall direction is based on the player's horizontal facing at the moment the axe break completes; it deliberately does not use a notch or hinge.

See `docs/TECHNICAL.md` and `docs/MANUAL_TESTING.md` for the design and test matrix.
