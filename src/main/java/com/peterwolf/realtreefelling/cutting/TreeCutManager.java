package com.peterwolf.realtreefelling.cutting;

import com.peterwolf.realtreefelling.RealTreeFellingMod;
import com.peterwolf.realtreefelling.config.TreeFellingConfig;
import com.peterwolf.realtreefelling.detection.DetectedTree;
import com.peterwolf.realtreefelling.detection.TreeDetector;
import com.peterwolf.realtreefelling.entity.FallingTreeEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** The server detects the tree and starts a fall away from the player in one axe break. */
public final class TreeCutManager {
	/**
	 * Four bytes per visual block are synchronized to clients. Keep geometry well below the
	 * entity-data ceiling while still animating large vanilla crowns.
	 */
	private static final int MAX_ENTITY_VISUAL_BLOCKS = 5_500;

	private TreeCutManager() {}

	public static boolean onLogBreakAttempt(ServerLevel level, ServerPlayer player, BlockPos hit) {
		TreeFellingConfig c = TreeFellingConfig.INSTANCE;
		if (!c.enableRealisticFelling || !level.getBlockState(hit).is(BlockTags.LOGS) || (player.isShiftKeyDown() && c.allowSneakVanillaBreaking)) {
			return false;
		}
		ItemStack tool = player.getMainHandItem();
		if (FallenLogManager.onBreakAttempt(level, player, hit, tool)) return true;
		if (c.requireAxe && !tool.is(ItemTags.AXES)) {
			hint(player, "message.peterwolfs_realistic_tree_felling.require_axe");
			return true;
		}
		Optional<DetectedTree> detected = TreeDetector.detect(level, hit);
		if (detected.isEmpty()) {
			hint(player, "message.peterwolfs_realistic_tree_felling.invalid_tree");
			return true;
		}
		cutSound(level, hit);
		damageAxe(player, tool);
		// getDirection points from the player toward the trunk. Its opposite is the safe, away-from-player fall direction.
		startFall(level, player, detected.get(), FallDirection.from(player.getDirection().getOpposite()));
		return true;
	}

	public static void cancel(ServerLevel level, BlockPos pos) { /* Single-cut felling has no staged session to cancel. */ }

	public static boolean forceFall(ServerLevel level, ServerPlayer player, BlockPos pos) {
		Optional<DetectedTree> tree = TreeDetector.detect(level, pos);
		if (tree.isEmpty()) return false;
		startFall(level, player, tree.get(), FallDirection.from(player.getDirection().getOpposite()));
		return true;
	}

	/**
	 * Public API: fell a tree at {@code pos} in {@code direction} without a player (fire, commands, other mods).
	 * @return true when a valid tree was detected and the fall entity was spawned
	 */
	public static boolean forceFallInDirection(ServerLevel level, BlockPos pos, Direction direction) {
		Optional<DetectedTree> tree = TreeDetector.detect(level, pos);
		if (tree.isEmpty()) {
			return false;
		}
		startFall(level, null, tree.get(), FallDirection.from(direction));
		return true;
	}

	public static void startFallWithDirection(ServerLevel level, ServerPlayer player, DetectedTree tree, Direction direction) {
		startFall(level, player, tree, FallDirection.from(direction));
	}

	private static void startFall(ServerLevel level, ServerPlayer player, DetectedTree tree, FallDirection direction) {
		// Keep the base block as a clean stump; it is not a virtual hinge and never controls the fall.
		List<DetectedTree.TreeBlockData> fallingBlocks = tree.blocks().stream()
			.filter(block -> !block.relative().equals(BlockPos.ZERO))
			.toList();

		// Clear every captured block from the world first so nothing is left floating mid-air.
		Set<BlockPos> cleared = new HashSet<>();
		for (DetectedTree.TreeBlockData block : fallingBlocks) {
			BlockPos worldPos = tree.base().offset(block.relative());
			level.setBlock(worldPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			cleared.add(worldPos.immutable());
		}

		// Safety net: any leftover canopy / tip logs that detection missed would float after the trunk is gone.
		clearFloatingRemnants(level, tree, fallingBlocks, cleared);

		// Entity geometry is capped for network safety; overflow leaves were already deleted above.
		List<DetectedTree.TreeBlockData> entityBlocks = limitEntityBlocks(fallingBlocks);
		FallingTreeEntity entity = new FallingTreeEntity(RealTreeFellingMod.FALLING_TREE, level);
		entity.configure(new DetectedTree(tree.base(), entityBlocks, tree.height()), direction);
		entity.setPos(tree.base().getX() + 0.5D, tree.base().getY(), tree.base().getZ() + 0.5D);
		level.addFreshEntity(entity);

		if (player != null) {
			hint(player, "message.peterwolfs_realistic_tree_felling.falling");
		}
		if (TreeFellingConfig.INSTANCE.enableCreakingSounds) {
			level.playSound(null, tree.base(), RealTreeFellingMod.TREE_FALL_START, SoundSource.BLOCKS, 1.5F, 1.0F);
		}
	}

	/**
	 * Prefer all logs in the falling entity (landing/loot correctness), then as many leaves as fit.
	 * Blocks omitted here are already air in the world and simply do not reappear on landing.
	 */
	private static List<DetectedTree.TreeBlockData> limitEntityBlocks(List<DetectedTree.TreeBlockData> fallingBlocks) {
		if (fallingBlocks.size() <= MAX_ENTITY_VISUAL_BLOCKS) {
			return fallingBlocks;
		}
		List<DetectedTree.TreeBlockData> logs = new ArrayList<>();
		List<DetectedTree.TreeBlockData> leaves = new ArrayList<>();
		for (DetectedTree.TreeBlockData block : fallingBlocks) {
			if (block.state().is(BlockTags.LEAVES)) leaves.add(block);
			else logs.add(block);
		}
		List<DetectedTree.TreeBlockData> limited = new ArrayList<>(Math.min(fallingBlocks.size(), MAX_ENTITY_VISUAL_BLOCKS));
		limited.addAll(logs);
		int leafBudget = Math.max(0, MAX_ENTITY_VISUAL_BLOCKS - limited.size());
		if (leafBudget > 0) {
			if (leaves.size() <= leafBudget) {
				limited.addAll(leaves);
			} else {
				// Evenly subsample so the animated crown still looks full rather than only the first flood-fill wave.
				for (int i = 0; i < leafBudget; i++) {
					int index = (int) ((long) i * leaves.size() / leafBudget);
					limited.add(leaves.get(index));
				}
			}
		}
		return List.copyOf(limited);
	}

	/**
	 * After the captured structure is removed, delete leftover floating log clusters (acacia
	 * konars, missed diagonal forks) then sweep the original crown volume for orphan leaves
	 * that would only have been supported by this tree. Neighboring trees keep their canopy
	 * when a closer remaining trunk still supports those leaves.
	 */
	private static void clearFloatingRemnants(
		ServerLevel level,
		DetectedTree tree,
		List<DetectedTree.TreeBlockData> fallingBlocks,
		Set<BlockPos> alreadyCleared
	) {
		BlockPos base = tree.base();
		Set<BlockPos> removedLogSupport = new HashSet<>();
		Set<Block> ownLeafTypes = new HashSet<>();
		int minX = base.getX();
		int maxX = base.getX();
		int minY = base.getY();
		int maxY = base.getY() + Math.max(2, tree.height());
		int minZ = base.getZ();
		int maxZ = base.getZ();

		for (DetectedTree.TreeBlockData block : fallingBlocks) {
			BlockPos world = base.offset(block.relative()).immutable();
			minX = Math.min(minX, world.getX());
			maxX = Math.max(maxX, world.getX());
			minY = Math.min(minY, world.getY());
			maxY = Math.max(maxY, world.getY());
			minZ = Math.min(minZ, world.getZ());
			maxZ = Math.max(maxZ, world.getZ());
			if (block.state().is(BlockTags.LOGS)) {
				removedLogSupport.add(world);
			} else if (block.state().is(BlockTags.LEAVES)) {
				ownLeafTypes.add(block.state().getBlock());
			}
		}
		// Stump stays in the world and belongs only to remainingLogs — never treat it as removed support,
		// or low leaves beside the stump would be wiped incorrectly.

		int pad = 8;
		minX -= pad;
		maxX += pad;
		minY = Math.max(level.getMinY(), minY - 1);
		maxY += pad;
		minZ -= pad;
		maxZ += pad;

		// Remaining vertical trunks in/near the volume (other trees + stump).
		Set<BlockPos> remainingLogs = new HashSet<>();
		for (BlockPos pos : BlockPos.betweenClosed(minX - 2, minY - 2, minZ - 2, maxX + 2, maxY + 2, maxZ + 2)) {
			if (!level.isLoaded(pos)) continue;
			BlockState state = level.getBlockState(pos);
			if (state.is(BlockTags.LOGS)) {
				remainingLogs.add(pos.immutable());
			}
		}

		// Diagonal forks (acacia konars, cherry limbs) can sit a 26-neighbour away from captured
		// wood, so a single-block "tip" check never sees them. Remove whole floating clusters
		// before the leaf sweep, otherwise leftover konars keep their canopy in the air.
		clearFloatingLogClusters(level, base, remainingLogs, removedLogSupport, alreadyCleared);

		for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
			if (!level.isLoaded(pos) || alreadyCleared.contains(pos)) continue;
			BlockState state = level.getBlockState(pos);
			if (state.isAir()) continue;

			if (state.is(BlockTags.LEAVES)) {
				if (!ownLeafTypes.isEmpty() && !ownLeafTypes.contains(state.getBlock())) continue;
				int toRemoved = nearestDistanceSquared(pos, removedLogSupport);
				int toRemaining = nearestDistanceSquared(pos, remainingLogs);
				// Strict < keeps boundary leaves on a living neighbor tree; == favors the remaining trunk (stump).
				if (toRemoved < toRemaining) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
					alreadyCleared.add(pos.immutable());
				}
			}
		}

		// Attached vines / cocoa / similar hangers on cleared logs would also float.
		clearHangingAttachments(level, alreadyCleared, minX, maxX, minY, maxY, minZ, maxZ);
	}

	/**
	 * Delete remaining log clusters that are in the air, belonged to the felled tree, and are not
	 * sitting on terrain or joined to another living trunk. Grounded neighbor trees, the stump,
	 * and previously fallen logs on the ground are left alone.
	 */
	private static void clearFloatingLogClusters(
		ServerLevel level,
		BlockPos stump,
		Set<BlockPos> remainingLogs,
		Set<BlockPos> removedLogSupport,
		Set<BlockPos> alreadyCleared
	) {
		Set<BlockPos> visited = new HashSet<>();
		for (BlockPos seed : List.copyOf(remainingLogs)) {
			if (!remainingLogs.contains(seed) || !visited.add(seed)) continue;
			List<BlockPos> cluster = new ArrayList<>();
			ArrayDeque<BlockPos> queue = new ArrayDeque<>();
			queue.add(seed);
			boolean supported = false;
			while (!queue.isEmpty()) {
				BlockPos here = queue.removeFirst();
				cluster.add(here);
				if (here.equals(stump) || isExternallySupportedLog(level, here, remainingLogs)) {
					supported = true;
				}
				TreeDetector.forEachNeighbor26(here, next -> {
					BlockPos neighbor = next.immutable();
					if (remainingLogs.contains(neighbor) && visited.add(neighbor)) {
						queue.addLast(neighbor);
					}
				});
			}
			if (supported) continue;
			boolean belongedToFelledTree = false;
			for (BlockPos pos : cluster) {
				if (nearestDistanceSquared(pos, removedLogSupport) <= 12) {
					belongedToFelledTree = true;
					break;
				}
			}
			if (!belongedToFelledTree) continue;
			for (BlockPos pos : cluster) {
				if (pos.equals(stump)) continue;
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				alreadyCleared.add(pos);
				remainingLogs.remove(pos);
			}
		}
	}

	/**
	 * A log is independently supported when it sits on solid ground, or on a log outside this
	 * sweep (another tree just beyond the scan). Leaves do not count — a konar resting in its
	 * own canopy is still floating wood. Logs sitting on other remaining logs are resolved by
	 * the cluster walk, not here.
	 */
	private static boolean isExternallySupportedLog(ServerLevel level, BlockPos pos, Set<BlockPos> remainingLogs) {
		BlockPos below = pos.below();
		if (!level.isLoaded(below)) return true;
		BlockState belowState = level.getBlockState(below);
		if (belowState.is(BlockTags.LOGS)) {
			return !remainingLogs.contains(below.immutable());
		}
		if (belowState.is(BlockTags.LEAVES) || belowState.isAir() || belowState.canBeReplaced()) {
			return false;
		}
		return belowState.isFaceSturdy(level, below, Direction.UP);
	}

	private static void clearHangingAttachments(
		ServerLevel level,
		Set<BlockPos> cleared,
		int minX, int maxX, int minY, int maxY, int minZ, int maxZ
	) {
		for (BlockPos pos : BlockPos.betweenClosed(minX - 1, minY - 1, minZ - 1, maxX + 1, maxY + 1, maxZ + 1)) {
			if (!level.isLoaded(pos) || cleared.contains(pos)) continue;
			BlockState state = level.getBlockState(pos);
			if (state.isAir()) continue;
			// Vines, cocoa, moss carpet-like hangers that only make sense attached to the felled canopy/trunk.
			if (isHangingAttachment(state) && touchesClearedSupport(pos, cleared)) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
	}

	private static boolean isHangingAttachment(BlockState state) {
		Block block = state.getBlock();
		return block == Blocks.VINE
			|| block == Blocks.COCOA
			|| block == Blocks.GLOW_LICHEN
			|| block == Blocks.MOSS_CARPET
			|| block == Blocks.PALE_HANGING_MOSS
			|| block == Blocks.HANGING_ROOTS
			|| block == Blocks.CAVE_VINES
			|| block == Blocks.CAVE_VINES_PLANT;
	}

	private static boolean touchesClearedSupport(BlockPos pos, Set<BlockPos> cleared) {
		for (Direction direction : Direction.values()) {
			if (cleared.contains(pos.relative(direction))) return true;
		}
		return false;
	}

	private static int nearestDistanceSquared(BlockPos position, Set<BlockPos> sources) {
		if (sources.isEmpty()) return Integer.MAX_VALUE;
		int nearest = Integer.MAX_VALUE;
		for (BlockPos source : sources) {
			int x = position.getX() - source.getX();
			int y = position.getY() - source.getY();
			int z = position.getZ() - source.getZ();
			nearest = Math.min(nearest, x * x + y * y + z * z);
		}
		return nearest;
	}

	private static void cutSound(ServerLevel level, BlockPos pos) {
		level.playSound(null, pos, SoundEvents.AXE_STRIP.value(), SoundSource.BLOCKS, 0.9F, 1.0F);
	}

	private static void damageAxe(ServerPlayer player, ItemStack stack) {
		if (!player.getAbilities().instabuild && stack.isDamageableItem()) {
			stack.hurtAndBreak(1, player, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
		}
	}

	private static void hint(ServerPlayer player, String key) {
		if (TreeFellingConfig.INSTANCE.enableActionBarHints) {
			player.sendSystemMessage(Component.translatable(key));
		}
	}
}
