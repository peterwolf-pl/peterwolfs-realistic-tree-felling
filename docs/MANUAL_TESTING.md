# Manual Phase 1 checklist

- [ ] Launch an integrated or dedicated Fabric 26.2 server with the built JAR and Fabric API.
- [ ] On an oak tree, break any trunk block once with an axe; verify the stump remains and the tree lands away from the player.
- [ ] Repeat with birch, spruce, acacia, mangrove, cherry and a branchy tree.
- [ ] Sneak-break a log and verify normal vanilla destruction still works.
- [ ] Try a horizontal log wall and verify it is rejected without removing logs.
- [ ] Lower `maximumTreeBlocks`, try a larger tree and verify it remains intact.
- [ ] Stand on each cardinal side of a tree and verify it always falls in the opposite direction.
- [ ] Verify connected leaves fall with the trunk. On landing, verify the remaining canopy sits at the far/crown end of the fallen trunk (not smeared along the whole log), half drop as a mix of leaf blocks / sticks / branch logs, the other half are placed flat (not piled upward) and slowly disappear within ~5–20s.
- [ ] Fell large oak/dark oak/jungle trees and confirm no leaf clumps, branch tips, vines or cocoa stay floating in the original crown after the fall starts. Neighbor tree canopies that touch should stay in place.
- [ ] Fell a tree toward a cliff/void with no ground at stump height along the fall path; verify the tree shatters into block items at the edge instead of floating logs.
- [ ] Chop landed logs: each must take two or three axe strikes and then enter the player's inventory (or drop only if full).
- [ ] Use `/treefelling inspect` and `/treefelling forcefall` while targeting a tree base.
- [ ] Restart the server during the visible fall and verify the saved entity completes its landing.
- [ ] Check a dedicated-server log for no client-class loading errors.
