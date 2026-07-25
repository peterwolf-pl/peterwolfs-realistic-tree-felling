# Changelog

## 1.0.7 - 2026-07-25

- Added mod icon (`icon.png`).

## 1.0.6 - 2026-07-25

- Landed canopy stays in the tree crown (far end of the fallen trunk): the fall transform no longer smears leaves along the whole log by keeping the original fall-axis offset.
- Preserved leaves prefer a flat free cell beside the crown; they only rest one block on the trunk as a last resort, never as a tall leaf mound. Half the crown still shatters into leaf blocks / sticks / branch logs.

## 1.0.5 - 2026-07-25

- Impact canopy debris is now a random mix of leaf blocks, sticks and matching branch logs (konary).

## 1.0.4 - 2026-07-25

- Leaves no longer form an elevated mound after landing: the canopy lies flat with the trunk.
- On impact, half of the leaves break into leaf-block items; the other half is placed and slowly disappears like canopy left without a trunk.
- When a tree falls into a chasm (no terrain at or below the cut stump height along the fall path), the whole tree shatters into block items at the last solid edge.

## 1.0.3 - 2026-07-21

- Prevent a felled tree from taking leaves from a neighboring tree when their crowns touch.

## 1.0.2 - 2026-07-21

- Render the exact captured trunk, branch and leaf-block layout during the fall, preserving the original tree's length and crown shape.

## 1.0.1 - 2026-07-21

- Capture the complete connected leaf canopy of a felled tree instead of only the leaves immediately beside its logs.
- Keep the falling tree's detected trunk and leaf species in the animation, with all log segments rotated as one tree.

## 1.0.0 - 2026-07-21

- Initial release of PeterWolf's Realistic Tree Felling for Minecraft 26.2 and Fabric.
