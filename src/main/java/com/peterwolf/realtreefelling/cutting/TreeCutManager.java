package com.peterwolf.realtreefelling.cutting;

import com.peterwolf.realtreefelling.RealTreeFellingMod;
import com.peterwolf.realtreefelling.config.TreeFellingConfig;
import com.peterwolf.realtreefelling.detection.DetectedTree;
import com.peterwolf.realtreefelling.detection.TreeDetector;
import com.peterwolf.realtreefelling.entity.FallingTreeEntity;
import java.util.List;
import java.util.Optional;
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

/** The server detects the tree and starts a fall away from the player in one axe break. */
public final class TreeCutManager {
	private TreeCutManager() {}
	public static boolean onLogBreakAttempt(ServerLevel level, ServerPlayer player, BlockPos hit) {
		TreeFellingConfig c = TreeFellingConfig.INSTANCE;
		if (!c.enableRealisticFelling || !level.getBlockState(hit).is(BlockTags.LOGS) || (player.isShiftKeyDown() && c.allowSneakVanillaBreaking)) return false;
		ItemStack tool = player.getMainHandItem();
		if (FallenLogManager.onBreakAttempt(level, player, hit, tool)) return true;
		if (c.requireAxe && !(tool.getItem() instanceof AxeItem)) { hint(player, "message.peterwolfs_realistic_tree_felling.require_axe"); return true; }
		Optional<DetectedTree> detected = TreeDetector.detect(level, hit);
		if (detected.isEmpty()) { hint(player, "message.peterwolfs_realistic_tree_felling.invalid_tree"); return true; }
		cutSound(level, hit); damageAxe(player, tool);
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
	public static void startFallWithDirection(ServerLevel level, ServerPlayer player, DetectedTree tree, Direction direction) {
		startFall(level, player, tree, FallDirection.from(direction));
	}
	private static void startFall(ServerLevel level, ServerPlayer player, DetectedTree tree, FallDirection direction) {
		// Keep the base block as a clean stump; it is not a virtual hinge and never controls the fall.
		List<DetectedTree.TreeBlockData> fallingBlocks = tree.blocks().stream().filter(block -> !block.relative().equals(BlockPos.ZERO)).toList();
		for (DetectedTree.TreeBlockData block : fallingBlocks) level.setBlock(tree.base().offset(block.relative()), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		FallingTreeEntity entity = new FallingTreeEntity(RealTreeFellingMod.FALLING_TREE, level);
		entity.configure(new DetectedTree(tree.base(), fallingBlocks, tree.height()), direction); entity.setPos(tree.base().getX() + 0.5D, tree.base().getY(), tree.base().getZ() + 0.5D); level.addFreshEntity(entity);
		hint(player, "message.peterwolfs_realistic_tree_felling.falling");
		if (TreeFellingConfig.INSTANCE.enableCreakingSounds) {
			level.playSound(null, tree.base(), RealTreeFellingMod.TREE_FALL_START, SoundSource.BLOCKS, 1.5F, 1.0F);
		}
	}
	private static void cutSound(ServerLevel level, BlockPos pos) { level.playSound(null, pos, SoundEvents.AXE_STRIP, SoundSource.BLOCKS, 0.9F, 1.0F); }
	private static void damageAxe(ServerPlayer player, ItemStack stack) { if (!player.getAbilities().instabuild && stack.isDamageableItem()) stack.hurtAndBreak(1, player, net.minecraft.world.entity.EquipmentSlot.MAINHAND); }
	private static void hint(ServerPlayer player, String key) { if (TreeFellingConfig.INSTANCE.enableActionBarHints) player.sendSystemMessage(Component.translatable(key)); }
}
