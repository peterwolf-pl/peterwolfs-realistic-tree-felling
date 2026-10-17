package com.peterwolf.realtreefelling.cutting;

import com.peterwolf.realtreefelling.persistence.FallenLogSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Tracks only logs produced by this mod, so player-built horizontal log walls remain vanilla. */
public final class FallenLogManager {
	private FallenLogManager() {}
	public static void register(ServerLevel level, BlockPos position) {
		int hits = 2 + Math.floorMod(position.getX() * 31 + position.getY() * 17 + position.getZ(), 2);
		FallenLogSavedData.get(level).register(level.dimension(), position, hits);
	}
	/** @return true when a tracked fallen log consumed the break attempt. */
	public static boolean onBreakAttempt(ServerLevel level, ServerPlayer player, BlockPos position, ItemStack tool) {
		FallenLogSavedData data = FallenLogSavedData.get(level);
		var tracked = data.find(level.dimension(), position);
		if (tracked.isEmpty()) return false;
		FallenLogSavedData.FallenLog state = tracked.get();
		BlockState block = level.getBlockState(position);
		if (!block.is(BlockTags.LOGS)) { data.remove(level.dimension(), position); return false; }
		if (!tool.is(ItemTags.AXES)) { player.sendSystemMessage(Component.translatable("message.peterwolfs_realistic_tree_felling.require_axe")); return true; }
		state = data.increment(state);
		if (!player.getAbilities().instabuild && tool.isDamageableItem()) tool.hurtAndBreak(1, player, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
		level.playSound(null, position, SoundEvents.AXE_STRIP.value(), SoundSource.BLOCKS, 0.9F, 0.85F + state.strikes() * 0.08F);
		if (state.strikes() < state.requiredHits()) {
			player.sendSystemMessage(Component.translatable("message.peterwolfs_realistic_tree_felling.fallen_log_progress", state.strikes(), state.requiredHits()));
			return true;
		}
		data.remove(level.dimension(), position); level.setBlock(position, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		ItemStack drop = new ItemStack(block.getBlock());
		if (!player.getInventory().add(drop)) player.drop(drop, false, Prediction.SERVER_ONLY);
		player.sendSystemMessage(Component.translatable("message.peterwolfs_realistic_tree_felling.fallen_log_collected"));
		return true;
	}
}
