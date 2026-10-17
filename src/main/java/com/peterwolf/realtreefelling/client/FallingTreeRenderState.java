package com.peterwolf.realtreefelling.client;
import com.peterwolf.realtreefelling.entity.FallingTreeEntity;
import java.util.List;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
public final class FallingTreeRenderState extends EntityRenderState {
	public float tilt; public int direction; public List<FallingTreeEntity.VisualBlock> blocks = List.of();
	public final MovingBlockRenderState logBlock = new MovingBlockRenderState();
	public final MovingBlockRenderState leafBlock = new MovingBlockRenderState();
}
