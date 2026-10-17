package com.peterwolf.realtreefelling.cutting;

import com.peterwolf.realtreefelling.RealTreeFellingMod;
import com.peterwolf.realtreefelling.config.TreeFellingConfig;
import com.peterwolf.realtreefelling.detection.DetectedTree;
import com.peterwolf.realtreefelling.detection.TreeDetector;
import com.peterwolf.realtreefelling.entity.FallingTreeEntity;
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
import net.minecraft.world.item.AxeItem;
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
		if (c.requireAxe && !(tool.getItem() instanceof AxeItem)) {
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
		FallingTreeEntity entity = new FallingTreeEntity(RealTreeFellingMod.FALLING_TREE.get(), level);
		entity.configure(new DetectedTree(tree.base(), entityBlocks, tree.height()), direction);
		entity.setPos(tree.base().getX() + 0.5D, tree.base().getY(), tree.base().getZ() + 0.5D);
		level.addFreshEntity(entity);

		if (player != null) {
			hint(player, "message.peterwolfs_realistic_tree_felling.falling");
		}
		if (TreeFellingConfig.INSTANCE.enableCreakingSounds) {
			level.playSound(null, tree.base(), RealTreeFellingMod.TREE_FALL_START.get(), SoundSource.BLOCKS, 1.5F, 1.0F);
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
	 * After the captured structure is removed, sweep the original crown volume and delete leaves
	 * (and rare missed log tips) that would only have been supported by this tree. Neighboring
	 * trees keep their canopy when a closer remaining trunk still supports those leaves.
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

		int pad = 3;
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
				continue;
			}

			// Rare missed branch tips still connected to cleared wood: remove floating log stubs.
			if (state.is(BlockTags.LOGS) && !pos.equals(base) && isFloatingMissedTip(level, pos, remainingLogs, removedLogSupport)) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				alreadyCleared.add(pos.immutable());
				remainingLogs.remove(pos);
			}
		}

		// Attached vines / cocoa / similar hangers on cleared logs would also float.
		clearHangingAttachments(level, alreadyCleared, minX, maxX, minY, maxY, minZ, maxZ);
	}

	/**
	 * A small log stub is a "missed tip" when it sits next to cleared wood, has no solid ground
	 * under a short column, and is closer to removed support than to any other remaining trunk mass.
	 */
	private static boolean isFloatingMissedTip(
		ServerLevel level,
		BlockPos pos,
		Set<BlockPos> remainingLogs,
		Set<BlockPos> removedLogSupport
	) {
		boolean touchesRemoved = false;
		for (Direction direction : Direction.values()) {
			if (removedLogSupport.contains(pos.relative(direction))) {
				touchesRemoved = true;
				break;
			}
		}
		if (!touchesRemoved) return false;
		// Exclude self — this stub is still in remainingLogs until we delete it.
		int toOtherRemaining = nearestDistanceSquaredExcluding(pos, remainingLogs, pos);
		if (nearestDistanceSquared(pos, removedLogSupport) > toOtherRemaining) return false;
		// Grounded logs (on solid block or another log) stay; floating mid-air stubs go.
		BlockPos below = pos.below();
		BlockState belowState = level.getBlockState(below);
		if (belowState.isFaceSturdy(level, below, Direction.UP) || belowState.is(BlockTags.LOGS)) {
			return false;
		}
		return true;
	}

	private static int nearestDistanceSquaredExcluding(BlockPos position, Set<BlockPos> sources, BlockPos exclude) {
		if (sources.isEmpty()) return Integer.MAX_VALUE;
		int nearest = Integer.MAX_VALUE;
		for (BlockPos source : sources) {
			if (source.equals(exclude)) continue;
			int x = position.getX() - source.getX();
			int y = position.getY() - source.getY();
			int z = position.getZ() - source.getZ();
			nearest = Math.min(nearest, x * x + y * y + z * z);
		}
		return nearest;
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
		level.playSound(null, pos, SoundEvents.AXE_STRIP, SoundSource.BLOCKS, 0.9F, 1.0F);
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
