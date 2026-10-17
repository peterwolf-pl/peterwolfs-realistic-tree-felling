package com.peterwolf.realtreefelling.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.peterwolf.realtreefelling.RealTreeFellingMod;
import com.peterwolf.realtreefelling.entity.FallingTreeEntity;
import com.peterwolf.realtreefelling.physics.FallTransform;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class FallingTreeRenderer extends EntityRenderer<FallingTreeEntity, FallingTreeRenderState> {
	public FallingTreeRenderer(EntityRendererProvider.Context context) { super(context); shadowRadius = 1.6F; }
	@Override public FallingTreeRenderState createRenderState() { return new FallingTreeRenderState(); }
	@Override public void extractRenderState(FallingTreeEntity entity, FallingTreeRenderState state, float partial) {
		super.extractRenderState(entity, state, partial); state.tilt = entity.tilt(partial); state.direction = entity.fallDirection().get2DDataValue(); state.blocks = entity.visualBlocks();
		if (entity.level() instanceof ClientLevel level) {
			BlockPos origin = entity.blockPosition();
			BlockState uprightLog = entity.visualLogState();
			// Rotate the entire local tree, never each log separately. A vertical source axis avoids 90-degree sideways segments.
			if (uprightLog.hasProperty(BlockStateProperties.AXIS)) uprightLog = uprightLog.setValue(BlockStateProperties.AXIS, net.minecraft.core.Direction.Axis.Y);
			configure(state.logBlock, level, origin, uprightLog);
			configure(state.leafBlock, level, origin, entity.visualLeafState());
		}
	}
	@Override public void submit(FallingTreeRenderState state, PoseStack stack, SubmitNodeCollector collector, CameraRenderState camera) {
		stack.pushPose();
		// Submit vanilla block models around local y=0: the cut/base point. This deliberately avoids an entity mesh.
		stack.mulPose(Axis.YP.rotationDegrees(FallTransform.rendererYaw(net.minecraft.core.Direction.from2DDataValue(state.direction))));
		stack.mulPose(Axis.ZP.rotationDegrees(state.tilt));
		for (FallingTreeEntity.VisualBlock block : state.blocks) {
			BlockPos relative = block.relative();
			submitBlock(collector, stack, block.leaf() ? state.leafBlock : state.logBlock, relative.getX(), relative.getY(), relative.getZ());
		}
		stack.popPose();
	}
	private static void submitBlock(SubmitNodeCollector collector, PoseStack stack, MovingBlockRenderState block, int x, int y, int z) {
		stack.pushPose(); stack.translate(x - 0.5D, y, z - 0.5D); collector.submitMovingBlock(stack, block); stack.popPose();
	}
	private static void configure(MovingBlockRenderState state, ClientLevel level, BlockPos position, net.minecraft.world.level.block.state.BlockState block) {
		state.randomSeedPos = position; state.blockPos = position; state.blockState = block; state.biome = level.getBiome(position); state.level = level;
	}
}
