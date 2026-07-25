package com.peterwolf.realtreefelling.command;

import com.mojang.brigadier.CommandDispatcher;
import com.peterwolf.realtreefelling.config.TreeFellingConfig;
import com.peterwolf.realtreefelling.cutting.TreeCutManager;
import com.peterwolf.realtreefelling.detection.TreeDetector;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;

public final class TreeFellingCommands {
	private static boolean debug;
	private TreeFellingCommands() {}
	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("treefelling").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("reload").executes(context -> { TreeFellingConfig.load(); context.getSource().sendSuccess(() -> Component.translatable("command.peterwolfs_realistic_tree_felling.reloaded"), true); return 1; }))
			.then(Commands.literal("config").executes(context -> { context.getSource().sendSuccess(() -> Component.literal("Config: config/peterwolfs-realistic-tree-felling.json"), false); return 1; }))
			.then(Commands.literal("debug").then(Commands.literal("on").executes(context -> { debug = true; return 1; })).then(Commands.literal("off").executes(context -> { debug = false; return 1; })))
			.then(Commands.literal("inspect").executes(context -> inspect(context.getSource().getPlayerOrException())))
			.then(Commands.literal("preview").executes(context -> inspect(context.getSource().getPlayerOrException())))
			.then(Commands.literal("cancel").executes(context -> { ServerPlayer p = context.getSource().getPlayerOrException(); TreeCutManager.cancel((net.minecraft.server.level.ServerLevel) p.level(), target(p)); return 1; }))
			.then(Commands.literal("forcefall").executes(context -> { ServerPlayer p = context.getSource().getPlayerOrException(); return TreeCutManager.forceFall((net.minecraft.server.level.ServerLevel) p.level(), p, target(p)) ? 1 : 0; })));
	}
	private static int inspect(ServerPlayer player) {
		var position = target(player); var tree = TreeDetector.detect((net.minecraft.server.level.ServerLevel) player.level(), position); if (tree.isEmpty()) { player.sendSystemMessage(Component.translatable("command.peterwolfs_realistic_tree_felling.no_session")); return 0; }
		var value = tree.get(); player.sendSystemMessage(Component.translatable("command.peterwolfs_realistic_tree_felling.inspect", value.blocks().size(), value.height(), value.mass(), "away from player", "opposite facing")); return 1;
	}
	private static net.minecraft.core.BlockPos target(ServerPlayer player) { return player.pick(8.0D, 0.0F, false) instanceof BlockHitResult hit ? hit.getBlockPos() : player.blockPosition(); }
	public static boolean debug() { return debug; }
}
