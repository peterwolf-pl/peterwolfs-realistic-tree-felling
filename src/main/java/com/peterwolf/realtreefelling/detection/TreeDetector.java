package com.peterwolf.realtreefelling.detection;

import com.peterwolf.realtreefelling.config.TreeFellingConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Bounded flood fill: no chunk-wide scans and no acceptance of flat log walls. */
public final class TreeDetector {
	private TreeDetector() {}
	public static Optional<DetectedTree> detect(ServerLevel level, BlockPos requestedBase) {
		TreeFellingConfig c = TreeFellingConfig.INSTANCE;
		if (!level.getBlockState(requestedBase).is(BlockTags.LOGS)) return Optional.empty();
		BlockPos base = descendTrunk(level, requestedBase, c.maximumTreeHeight);
		Set<BlockPos> found = new HashSet<>(); ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		found.add(base); queue.add(base);
		int highest = base.getY(); int vertical = 0;
		while (!queue.isEmpty() && found.size() <= c.maximumTreeBlocks) {
			BlockPos here = queue.removeFirst(); highest = Math.max(highest, here.getY());
			if (here.getX() == base.getX() && here.getZ() == base.getZ()) vertical++;
			for (var direction : net.minecraft.core.Direction.values()) {
				BlockPos next = here.relative(direction);
				if (next.getY() < base.getY() || next.getY() > base.getY() + c.maximumTreeHeight) continue;
				if (Math.abs(next.getX() - base.getX()) > c.maximumBranchDistance || Math.abs(next.getZ() - base.getZ()) > c.maximumBranchDistance) continue;
				if (found.contains(next)) continue;
				if (level.getBlockState(next).is(BlockTags.LOGS)) { found.add(next); queue.addLast(next); }
			}
		}
		if (found.size() > c.maximumTreeBlocks) return Optional.empty();
		int height = highest - base.getY() + 1;
		if (found.size() < 2 || height < 2 || vertical * 100 < Math.max(35, height * 45) || (c.requireLeavesForTreeDetection && !hasLeaves(level, found))) return Optional.empty();
		List<DetectedTree.TreeBlockData> blocks = new ArrayList<>();
		for (BlockPos pos : found) blocks.add(new DetectedTree.TreeBlockData(pos.subtract(base), level.getBlockState(pos)));
		// Leaves are captured after log validation, never included in the trunk flood-fill.
		// This keeps an adjacent canopy from making a structure look like a valid tree.
		for (BlockPos leaf : connectedLeaves(level, found, base, highest, c)) blocks.add(new DetectedTree.TreeBlockData(leaf.subtract(base), level.getBlockState(leaf)));
		return Optional.of(new DetectedTree(base, List.copyOf(blocks), height));
	}
	private static BlockPos descendTrunk(ServerLevel level, BlockPos pos, int limit) { for (int i = 0; i < limit && level.getBlockState(pos.below()).is(BlockTags.LOGS); i++) pos = pos.below(); return pos; }
	private static boolean hasLeaves(ServerLevel level, Set<BlockPos> logs) { for (BlockPos log : logs) for (BlockPos p : BlockPos.betweenClosed(log.offset(-2, -2, -2), log.offset(2, 2, 2))) if (level.getBlockState(p).is(BlockTags.LEAVES)) return true; return false; }
	private static Set<BlockPos> connectedLeaves(ServerLevel level, Set<BlockPos> logs, BlockPos base, int highest, TreeFellingConfig config) {
		// Seed from leaves touching the validated trunk, then flood-fill the whole connected canopy.
		// The old fixed 5x5x5 scan stopped in the middle of broad vanilla and custom crowns.
		int crownPadding = Math.max(6, Math.min(12, config.maximumBranchDistance));
		int minX = logs.stream().mapToInt(BlockPos::getX).min().orElse(base.getX()) - crownPadding;
		int maxX = logs.stream().mapToInt(BlockPos::getX).max().orElse(base.getX()) + crownPadding;
		int minZ = logs.stream().mapToInt(BlockPos::getZ).min().orElse(base.getZ()) - crownPadding;
		int maxZ = logs.stream().mapToInt(BlockPos::getZ).max().orElse(base.getZ()) + crownPadding;
		int minY = base.getY(); int maxY = highest + crownPadding;
		// Four bytes per visual block are synchronized to clients. Keep every default tree well below the 32 KiB entity-data string ceiling.
		int geometryBudget = Math.max(0, 6_000 - config.maximumTreeBlocks);
		int limit = Math.min(Math.min(4_096, Math.max(1_024, config.maximumTreeBlocks * 12)), geometryBudget);
		Set<Block> ownLeafTypes = leafTypesAttachedToLogs(level, logs);
		Set<BlockPos> foreignTrunks = nearbyForeignTrunks(level, logs, minX, maxX, minY, maxY, minZ, maxZ, crownPadding);
		Set<BlockPos> leaves = new HashSet<>(); ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		seedLeaves:
		for (BlockPos log : logs) for (BlockPos candidate : BlockPos.betweenClosed(log.offset(-1, -1, -1), log.offset(1, 1, 1))) {
			if (leaves.size() >= limit) break seedLeaves;
			addLeaf(level, candidate, minX, maxX, minY, maxY, minZ, maxZ, ownLeafTypes, logs, foreignTrunks, leaves, queue);
		}
		while (!queue.isEmpty() && leaves.size() < limit) {
			BlockPos here = queue.removeFirst();
			for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
				if (x != 0 || y != 0 || z != 0) addLeaf(level, here.offset(x, y, z), minX, maxX, minY, maxY, minZ, maxZ, ownLeafTypes, logs, foreignTrunks, leaves, queue);
			}
		}
		return leaves;
	}
	private static Set<Block> leafTypesAttachedToLogs(ServerLevel level, Set<BlockPos> logs) {
		Set<Block> leafTypes = new HashSet<>();
		for (BlockPos log : logs) for (BlockPos candidate : BlockPos.betweenClosed(log.offset(-1, -1, -1), log.offset(1, 1, 1))) {
			BlockState state = level.getBlockState(candidate); if (state.is(BlockTags.LEAVES)) leafTypes.add(state.getBlock());
		}
		return leafTypes;
	}
	private static Set<BlockPos> nearbyForeignTrunks(ServerLevel level, Set<BlockPos> ownLogs, int minX, int maxX, int minY, int maxY, int minZ, int maxZ, int padding) {
		Set<BlockPos> trunks = new HashSet<>();
		for (BlockPos candidate : BlockPos.betweenClosed(minX - padding, minY - padding, minZ - padding, maxX + padding, maxY, maxZ + padding)) {
			if (ownLogs.contains(candidate) || !level.getBlockState(candidate).is(BlockTags.LOGS)) continue;
			if (level.getBlockState(candidate.above()).is(BlockTags.LOGS) || level.getBlockState(candidate.below()).is(BlockTags.LOGS)) trunks.add(candidate.immutable());
		}
		return trunks;
	}
	private static void addLeaf(ServerLevel level, BlockPos candidate, int minX, int maxX, int minY, int maxY, int minZ, int maxZ, Set<Block> ownLeafTypes, Set<BlockPos> ownLogs, Set<BlockPos> foreignTrunks, Set<BlockPos> leaves, ArrayDeque<BlockPos> queue) {
		if (candidate.getX() < minX || candidate.getX() > maxX || candidate.getY() < minY || candidate.getY() > maxY || candidate.getZ() < minZ || candidate.getZ() > maxZ) return;
		BlockState state = level.getBlockState(candidate);
		if (!state.is(BlockTags.LEAVES) || (!ownLeafTypes.isEmpty() && !ownLeafTypes.contains(state.getBlock())) || nearestDistanceSquared(candidate, ownLogs) > nearestDistanceSquared(candidate, foreignTrunks)) return;
		BlockPos leaf = candidate.immutable(); if (leaves.add(leaf)) queue.addLast(leaf);
	}
	private static int nearestDistanceSquared(BlockPos position, Set<BlockPos> sources) {
		if (sources.isEmpty()) return Integer.MAX_VALUE;
		int nearest = Integer.MAX_VALUE;
		for (BlockPos source : sources) {
			int x = position.getX() - source.getX(), y = position.getY() - source.getY(), z = position.getZ() - source.getZ();
			nearest = Math.min(nearest, x * x + y * y + z * z);
		}
		return nearest;
	}
}
