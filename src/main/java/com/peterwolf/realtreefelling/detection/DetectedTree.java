package com.peterwolf.realtreefelling.detection;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public record DetectedTree(BlockPos base, List<TreeBlockData> blocks, int height) {
	public int mass() { return blocks.size(); }
	public record TreeBlockData(BlockPos relative, BlockState state) {}
}
