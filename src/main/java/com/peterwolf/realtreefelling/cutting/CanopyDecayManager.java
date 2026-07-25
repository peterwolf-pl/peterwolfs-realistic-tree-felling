package com.peterwolf.realtreefelling.cutting;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Forces preserved canopy leaves to disappear over time, like vanilla decay after the trunk is gone.
 * Nearby fallen logs would otherwise keep leaf distance low forever, so removal is scheduled explicitly.
 */
public final class CanopyDecayManager {
	private static final Map<ServerLevel, List<PendingLeaf>> PENDING = new ConcurrentHashMap<>();

	private CanopyDecayManager() {}

	/** Schedule a placed leaf to vanish after {@code delayTicks}, with vanilla leaf loot on removal. */
	public static void schedule(ServerLevel level, BlockPos pos, int delayTicks) {
		List<PendingLeaf> list = PENDING.computeIfAbsent(level, ignored -> new ArrayList<>());
		synchronized (list) {
			list.add(new PendingLeaf(pos.immutable(), Math.max(1, delayTicks)));
		}
	}

	public static void tick(ServerLevel level) {
		List<PendingLeaf> list = PENDING.get(level);
		if (list == null || list.isEmpty()) return;
		List<BlockPos> ready = new ArrayList<>();
		synchronized (list) {
			for (Iterator<PendingLeaf> it = list.iterator(); it.hasNext(); ) {
				PendingLeaf pending = it.next();
				pending.ticksLeft--;
				if (pending.ticksLeft > 0) continue;
				it.remove();
				ready.add(pending.pos);
			}
			if (list.isEmpty()) PENDING.remove(level, list);
		}
		for (BlockPos pos : ready) decayLeaf(level, pos);
	}

	private static void decayLeaf(ServerLevel level, BlockPos pos) {
		if (!level.isLoaded(pos)) return;
		BlockState state = level.getBlockState(pos);
		if (!state.is(BlockTags.LEAVES)) return;
		// Same outcome as LeavesBlock random decay: loot table drops, then remove.
		Block.dropResources(state, level, pos);
		level.removeBlock(pos, false);
	}

	/** Place a non-persistent, max-distance leaf so it looks disconnected until forced decay runs. */
	public static BlockState decayingState(BlockState leaf) {
		BlockState state = leaf;
		if (state.hasProperty(LeavesBlock.PERSISTENT)) state = state.setValue(LeavesBlock.PERSISTENT, false);
		if (state.hasProperty(LeavesBlock.DISTANCE)) state = state.setValue(LeavesBlock.DISTANCE, LeavesBlock.DECAY_DISTANCE);
		return state;
	}

	private static final class PendingLeaf {
		private final BlockPos pos;
		private int ticksLeft;

		private PendingLeaf(BlockPos pos, int ticksLeft) {
			this.pos = pos;
			this.ticksLeft = ticksLeft;
		}
	}
}
