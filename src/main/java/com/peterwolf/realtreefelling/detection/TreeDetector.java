package com.peterwolf.realtreefelling.detection;

import com.peterwolf.realtreefelling.config.TreeFellingConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Bounded flood fill: no chunk-wide scans and no acceptance of flat log walls. */
public final class TreeDetector {
	/** Absolute horizontal reach for tip expansion past {@code maximumBranchDistance} (natural branch ends). */
	private static final int BRANCH_TIP_EXTRA = 5;
	/**
	 * Vanilla acacia/cherry/fancy oak step the trunk sideways as they rise, so a log on the
	 * original column is not required at every height. Count Y-levels with a log in this Chebyshev
	 * radius around the stump as "trunk" for the vertical-shape check.
	 */
	private static final int LEANING_TRUNK_RADIUS = 3;
	/**
	 * Leaves must be cleared from the world even when they cannot all fit in the falling entity's
	 * synchronized geometry. This cap is for world-removal completeness, not client animation.
	 */
	private static final int MAX_WORLD_LEAVES = 8_192;

	private TreeDetector() {}

	public static Optional<DetectedTree> detect(ServerLevel level, BlockPos requestedBase) {
		TreeFellingConfig c = TreeFellingConfig.INSTANCE;
		if (!level.getBlockState(requestedBase).is(BlockTags.LOGS)) return Optional.empty();
		BlockPos start = requestedBase.immutable();
		BlockPos base = findStump(level, start, c);
		Set<BlockPos> found = floodFillLogs(level, base, c);
		if (found.size() > c.maximumTreeBlocks) return Optional.empty();
		int highest = base.getY();
		Set<Integer> trunkLevels = new HashSet<>();
		for (BlockPos pos : found) {
			highest = Math.max(highest, pos.getY());
			if (Math.abs(pos.getX() - base.getX()) <= LEANING_TRUNK_RADIUS
				&& Math.abs(pos.getZ() - base.getZ()) <= LEANING_TRUNK_RADIUS) {
				trunkLevels.add(pos.getY());
			}
		}
		int height = highest - base.getY() + 1;
		if (found.size() < 2 || height < 2 || trunkLevels.size() * 100 < Math.max(35, height * 45)
			|| (c.requireLeavesForTreeDetection && !hasLeaves(level, found))) {
			return Optional.empty();
		}
		List<DetectedTree.TreeBlockData> blocks = new ArrayList<>();
		for (BlockPos pos : found) {
			blocks.add(new DetectedTree.TreeBlockData(pos.subtract(base), level.getBlockState(pos)));
		}
		// Leaves are captured after log validation, never included in the trunk flood-fill.
		// This keeps an adjacent canopy from making a structure look like a valid tree.
		for (BlockPos leaf : connectedLeaves(level, found, base, highest, c)) {
			blocks.add(new DetectedTree.TreeBlockData(leaf.subtract(base), level.getBlockState(leaf)));
		}
		return Optional.of(new DetectedTree(base, List.copyOf(blocks), height));
	}

	/**
	 * Walk down to the real grounded stump from any cut log, including an acacia konar.
	 * Same-Y steps stay six-neighbour (horizontal branches); downward steps use 26-neighbour
	 * so a ForkingTrunkPlacer staircase (±1, -1, 0) is followed to dirt.
	 */
	private static BlockPos findStump(ServerLevel level, BlockPos start, TreeFellingConfig config) {
		int limit = config.maximumTreeHeight;
		int branchLimit = config.maximumBranchDistance + BRANCH_TIP_EXTRA;
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		seen.add(start);
		queue.add(start);
		BlockPos lowest = start;
		BlockPos bestGroundedOnStart = isGroundedLog(level, start) ? start : null;
		BlockPos bestGrounded = bestGroundedOnStart;
		while (!queue.isEmpty()) {
			BlockPos here = queue.removeFirst();
			if (here.getY() < lowest.getY()) lowest = here;
			if (isGroundedLog(level, here)) {
				if (bestGrounded == null || here.getY() < bestGrounded.getY()) bestGrounded = here;
				if (here.getX() == start.getX() && here.getZ() == start.getZ()
					&& (bestGroundedOnStart == null || here.getY() < bestGroundedOnStart.getY())) {
					bestGroundedOnStart = here;
				}
			}
			offerStumpStep(level, here, Direction.DOWN, start, limit, branchLimit, seen, queue);
			offerStumpStep(level, here, Direction.NORTH, start, limit, branchLimit, seen, queue);
			offerStumpStep(level, here, Direction.SOUTH, start, limit, branchLimit, seen, queue);
			offerStumpStep(level, here, Direction.WEST, start, limit, branchLimit, seen, queue);
			offerStumpStep(level, here, Direction.EAST, start, limit, branchLimit, seen, queue);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dz == 0) continue;
					offerStumpCandidate(level, here.offset(dx, -1, dz), start, limit, branchLimit, seen, queue);
				}
			}
		}
		if (bestGroundedOnStart != null) return bestGroundedOnStart;
		if (bestGrounded != null) return bestGrounded;
		return lowest;
	}

	private static void offerStumpStep(
		ServerLevel level,
		BlockPos here,
		Direction direction,
		BlockPos start,
		int limit,
		int branchLimit,
		Set<BlockPos> seen,
		ArrayDeque<BlockPos> queue
	) {
		offerStumpCandidate(level, here.relative(direction), start, limit, branchLimit, seen, queue);
	}

	private static void offerStumpCandidate(
		ServerLevel level,
		BlockPos next,
		BlockPos start,
		int limit,
		int branchLimit,
		Set<BlockPos> seen,
		ArrayDeque<BlockPos> queue
	) {
		if (next.getY() > start.getY() || next.getY() < start.getY() - limit) return;
		if (Math.abs(next.getX() - start.getX()) > branchLimit || Math.abs(next.getZ() - start.getZ()) > branchLimit) {
			return;
		}
		if (!level.getBlockState(next).is(BlockTags.LOGS)) return;
		BlockPos stored = next.immutable();
		if (seen.add(stored)) queue.addLast(stored);
	}

	/**
	 * 26-neighbour log flood fill from the stump. Vanilla acacia ({@code ForkingTrunkPlacer})
	 * places the next trunk block at (dx, dy, dz) = (±1, 1, 0) — edge-adjacent, not a
	 * six-neighbour of the previous log — so a six-way fill leaves the leaning konar floating.
	 */
	private static Set<BlockPos> floodFillLogs(ServerLevel level, BlockPos base, TreeFellingConfig config) {
		Set<BlockPos> found = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		found.add(base);
		queue.add(base);
		int branchLimit = config.maximumBranchDistance + BRANCH_TIP_EXTRA;
		int maxY = base.getY() + config.maximumTreeHeight;
		while (!queue.isEmpty() && found.size() <= config.maximumTreeBlocks) {
			BlockPos here = queue.removeFirst();
			forEachNeighbor26(here, next -> {
				if (found.size() > config.maximumTreeBlocks) return;
				if (next.getY() < base.getY() || next.getY() > maxY) return;
				if (Math.abs(next.getX() - base.getX()) > branchLimit
					|| Math.abs(next.getZ() - base.getZ()) > branchLimit) {
					return;
				}
				if (found.contains(next)) return;
				if (!level.getBlockState(next).is(BlockTags.LOGS)) return;
				BlockPos stored = next.immutable();
				found.add(stored);
				queue.addLast(stored);
			});
		}
		return found;
	}

	private static boolean isGroundedLog(ServerLevel level, BlockPos pos) {
		BlockPos below = pos.below();
		BlockState belowState = level.getBlockState(below);
		if (belowState.is(BlockTags.LOGS) || belowState.is(BlockTags.LEAVES)) return false;
		return belowState.isFaceSturdy(level, below, Direction.UP);
	}

	public static void forEachNeighbor26(BlockPos pos, Consumer<BlockPos> consumer) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx != 0 || dy != 0 || dz != 0) consumer.accept(pos.offset(dx, dy, dz));
				}
			}
		}
	}

	private static boolean hasLeaves(ServerLevel level, Set<BlockPos> logs) {
		for (BlockPos log : logs) {
			for (BlockPos p : BlockPos.betweenClosed(log.offset(-2, -2, -2), log.offset(2, 2, 2))) {
				if (level.getBlockState(p).is(BlockTags.LEAVES)) return true;
			}
		}
		return false;
	}

	private static Set<BlockPos> connectedLeaves(ServerLevel level, Set<BlockPos> logs, BlockPos base, int highest, TreeFellingConfig config) {
		// Seed from leaves touching the validated trunk, then flood-fill the whole connected canopy.
		// The old fixed 5x5x5 scan stopped in the middle of broad vanilla and custom crowns.
		int crownPadding = Math.max(8, Math.min(14, config.maximumBranchDistance + BRANCH_TIP_EXTRA));
		int minX = logs.stream().mapToInt(BlockPos::getX).min().orElse(base.getX()) - crownPadding;
		int maxX = logs.stream().mapToInt(BlockPos::getX).max().orElse(base.getX()) + crownPadding;
		int minZ = logs.stream().mapToInt(BlockPos::getZ).min().orElse(base.getZ()) - crownPadding;
		int maxZ = logs.stream().mapToInt(BlockPos::getZ).max().orElse(base.getZ()) + crownPadding;
		int minY = base.getY();
		int maxY = highest + crownPadding;
		// Prefer clearing the full canopy from the world. Visual sync is capped separately when the entity is built.
		int limit = Math.min(MAX_WORLD_LEAVES, Math.max(2_048, config.maximumTreeBlocks * 24));
		Set<Block> ownLeafTypes = leafTypesAttachedToLogs(level, logs);
		Set<BlockPos> foreignTrunks = nearbyForeignTrunks(level, logs, minX, maxX, minY, maxY, minZ, maxZ, crownPadding);
		Set<BlockPos> leaves = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		seedLeaves:
		for (BlockPos log : logs) {
			for (BlockPos candidate : BlockPos.betweenClosed(log.offset(-1, -1, -1), log.offset(1, 1, 1))) {
				if (leaves.size() >= limit) break seedLeaves;
				addLeaf(level, candidate, minX, maxX, minY, maxY, minZ, maxZ, ownLeafTypes, logs, foreignTrunks, leaves, queue);
			}
		}
		while (!queue.isEmpty() && leaves.size() < limit) {
			BlockPos here = queue.removeFirst();
			for (int x = -1; x <= 1; x++) {
				for (int y = -1; y <= 1; y++) {
					for (int z = -1; z <= 1; z++) {
						if (x != 0 || y != 0 || z != 0) {
							addLeaf(level, here.offset(x, y, z), minX, maxX, minY, maxY, minZ, maxZ, ownLeafTypes, logs, foreignTrunks, leaves, queue);
						}
					}
				}
			}
		}
		return leaves;
	}

	private static Set<Block> leafTypesAttachedToLogs(ServerLevel level, Set<BlockPos> logs) {
		Set<Block> leafTypes = new HashSet<>();
		for (BlockPos log : logs) {
			for (BlockPos candidate : BlockPos.betweenClosed(log.offset(-1, -1, -1), log.offset(1, 1, 1))) {
				BlockState state = level.getBlockState(candidate);
				if (state.is(BlockTags.LEAVES)) leafTypes.add(state.getBlock());
			}
		}
		return leafTypes;
	}

	private static Set<BlockPos> nearbyForeignTrunks(ServerLevel level, Set<BlockPos> ownLogs, int minX, int maxX, int minY, int maxY, int minZ, int maxZ, int padding) {
		Set<BlockPos> trunks = new HashSet<>();
		for (BlockPos candidate : BlockPos.betweenClosed(minX - padding, minY - padding, minZ - padding, maxX + padding, maxY, maxZ + padding)) {
			if (ownLogs.contains(candidate) || !level.getBlockState(candidate).is(BlockTags.LOGS)) continue;
			if (level.getBlockState(candidate.above()).is(BlockTags.LOGS) || level.getBlockState(candidate.below()).is(BlockTags.LOGS)) {
				trunks.add(candidate.immutable());
			}
		}
		return trunks;
	}

	private static void addLeaf(
		ServerLevel level,
		BlockPos candidate,
		int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
		Set<Block> ownLeafTypes,
		Set<BlockPos> ownLogs,
		Set<BlockPos> foreignTrunks,
		Set<BlockPos> leaves,
		ArrayDeque<BlockPos> queue
	) {
		if (candidate.getX() < minX || candidate.getX() > maxX
			|| candidate.getY() < minY || candidate.getY() > maxY
			|| candidate.getZ() < minZ || candidate.getZ() > maxZ) {
			return;
		}
		BlockState state = level.getBlockState(candidate);
		if (!state.is(BlockTags.LEAVES)) return;
		if (!ownLeafTypes.isEmpty() && !ownLeafTypes.contains(state.getBlock())) return;
		// Prefer own trunk on a tie so boundary leaves of the felled tree do not stay floating.
		if (nearestDistanceSquared(candidate, ownLogs) > nearestDistanceSquared(candidate, foreignTrunks)) return;
		BlockPos leaf = candidate.immutable();
		if (leaves.add(leaf)) queue.addLast(leaf);
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
}
