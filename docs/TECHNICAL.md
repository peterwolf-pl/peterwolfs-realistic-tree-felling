# Technical design

## Authority and state flow

`PlayerBlockBreakEvents.BEFORE` runs on the server immediately before a log break. `TreeCutManager` rejects client intent that is not an axe cut on a valid bounded tree, cancels the final vanilla destruction, then removes the captured blocks and creates `FallingTreeEntity` in the direction opposite the player's horizontal facing. There is no cut session, notch, back-cut, or virtual hinge.

`FallingTreeEntity` synchronizes age, height and fall direction for rendering. It persists the anchor and each captured relative block so a saved entity can finish safely after restart. At landing, logs are rotated to the actual fall axis and registered as fallen-log sections. `FallenLogManager` gives each registered section a deterministic two- or three-strike axe requirement, then inserts its block item into the cutter's inventory. Blocked placements become normal item drops when configured. No client packet can select a tree or choose its fall direction.

## Detection

The detector descends to the trunk's bottom, then performs a six-neighbour flood fill of blocks in `#minecraft:logs`. After the logs pass validation, bounded nearby `#minecraft:leaves` are captured into the same falling structure. On landing, `FallTransform` maps original height to distance along the fall and keeps only the lateral axis perpendicular to the fall, so the canopy stays a compact crown at the far end of the trunk (no smear along the logs, no elevated mound). Half of the canopy becomes random debris (leaf block, stick, or matching branch log); the other half is placed flat in that crown zone and is removed over a few seconds by `CanopyDecayManager` (vanilla leaf loot on removal), so nearby fallen logs cannot keep the canopy forever. If more than half of the stump-height fall path has no solid ground within a few blocks below, the whole tree shatters into block items at the last supported edge. Search is bounded in all three dimensions and by block count. Acceptance requires at least two logs, two levels of height, and sufficient logs sharing the base X/Z column.

## Rendering

The client entrypoint registers an entity renderer and a deliberately small placeholder model. Entity interpolation is supplied by `InterpolationHandler`; rendering cannot alter physics. Its yaw mapping deliberately rotates the model's local tilt vector into the same cardinal direction used by server landing placement, and the angle uses an accelerating quadratic fall curve. The model is a resource-pack replaceable visual surface until the grouped block-state renderer is added.

## Extension points

`TreeFellingConfig` already exposes the requested policy switches. Phase 2 extends the detector for two-by-two bases and detailed branch metrics. Phase 3 adds client previews and a grouped block-state renderer. Phase 4 introduces swept collision and claimed-block adapters; no protected block must ever be overwritten without an explicit integration check.
