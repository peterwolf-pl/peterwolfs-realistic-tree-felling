package com.peterwolf.realtreefelling.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.peterwolf.realtreefelling.config.TreeFellingConfig;
import com.peterwolf.realtreefelling.cutting.TreeCutManager;
import com.peterwolf.realtreefelling.detection.DetectedTree;
import com.peterwolf.realtreefelling.detection.TreeDetector;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class TreeFellingCommands {
	private static boolean debug;
	private TreeFellingCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		var secretFallNode = Commands.literal("secretfall")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.argument("radius", IntegerArgumentType.integer(1, 200))
				.executes(context -> secretFall(context.getSource(), IntegerArgumentType.getInteger(context, "radius"), null))
				.then(Commands.argument("direction", StringArgumentType.word())
					.executes(context -> secretFall(context.getSource(), IntegerArgumentType.getInteger(context, "radius"), StringArgumentType.getString(context, "direction")))));

		var fellAllNode = Commands.literal("fellall")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.argument("radius", IntegerArgumentType.integer(1, 200))
				.executes(context -> secretFall(context.getSource(), IntegerArgumentType.getInteger(context, "radius"), null))
				.then(Commands.argument("direction", StringArgumentType.word())
					.executes(context -> secretFall(context.getSource(), IntegerArgumentType.getInteger(context, "radius"), StringArgumentType.getString(context, "direction")))));

		var timberNode = Commands.literal("timber")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.argument("radius", IntegerArgumentType.integer(1, 200))
				.executes(context -> secretFall(context.getSource(), IntegerArgumentType.getInteger(context, "radius"), null))
				.then(Commands.argument("direction", StringArgumentType.word())
					.executes(context -> secretFall(context.getSource(), IntegerArgumentType.getInteger(context, "radius"), StringArgumentType.getString(context, "direction")))));

		dispatcher.register(secretFallNode);
		dispatcher.register(fellAllNode);
		dispatcher.register(timberNode);

		dispatcher.register(Commands.literal("treefelling").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("reload").executes(context -> { TreeFellingConfig.load(); context.getSource().sendSuccess(() -> Component.translatable("command.peterwolfs_realistic_tree_felling.reloaded"), true); return 1; }))
			.then(Commands.literal("config").executes(context -> { context.getSource().sendSuccess(() -> Component.literal("Config: config/peterwolfs-realistic-tree-felling.json"), false); return 1; }))
			.then(Commands.literal("debug").then(Commands.literal("on").executes(context -> { debug = true; return 1; })).then(Commands.literal("off").executes(context -> { debug = false; return 1; })))
			.then(Commands.literal("inspect").executes(context -> inspect(context.getSource().getPlayerOrException())))
			.then(Commands.literal("preview").executes(context -> inspect(context.getSource().getPlayerOrException())))
			.then(Commands.literal("cancel").executes(context -> { ServerPlayer p = context.getSource().getPlayerOrException(); TreeCutManager.cancel((ServerLevel) p.level(), target(p)); return 1; }))
			.then(Commands.literal("forcefall").executes(context -> { ServerPlayer p = context.getSource().getPlayerOrException(); return TreeCutManager.forceFall((ServerLevel) p.level(), p, target(p)) ? 1 : 0; }))
			.then(Commands.literal("secretfall")
				.then(Commands.argument("radius", IntegerArgumentType.integer(1, 200))
					.executes(context -> secretFall(context.getSource(), IntegerArgumentType.getInteger(context, "radius"), null))
					.then(Commands.argument("direction", StringArgumentType.word())
						.executes(context -> secretFall(context.getSource(), IntegerArgumentType.getInteger(context, "radius"), StringArgumentType.getString(context, "direction"))))))
			.then(Commands.literal("fellall")
				.then(Commands.argument("radius", IntegerArgumentType.integer(1, 200))
					.executes(context -> secretFall(context.getSource(), IntegerArgumentType.getInteger(context, "radius"), null))
					.then(Commands.argument("direction", StringArgumentType.word())
						.executes(context -> secretFall(context.getSource(), IntegerArgumentType.getInteger(context, "radius"), StringArgumentType.getString(context, "direction")))))));
	}

	private static int secretFall(CommandSourceStack source, int radius, String directionStr) {
		ServerPlayer player;
		try {
			player = source.getPlayerOrException();
		} catch (Exception e) {
			source.sendFailure(Component.literal("Komenda może być wykonana tylko przez gracza."));
			return 0;
		}

		ServerLevel level = (ServerLevel) player.level();
		Direction direction = parseDirection(directionStr, player.getDirection());
		BlockPos center = player.blockPosition();

		int clampedRadius = Math.clamp(radius, 1, 200);
		Set<BlockPos> visited = new HashSet<>();
		int felledCount = 0;

		int minX = center.getX() - clampedRadius;
		int maxX = center.getX() + clampedRadius;
		int minZ = center.getZ() - clampedRadius;
		int maxZ = center.getZ() + clampedRadius;
		int minY = Math.max(level.getMinY(), center.getY() - 15);
		int maxY = Math.min(level.getMaxY(), center.getY() + 35);

		int rSq = clampedRadius * clampedRadius;

		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				int dx = x - center.getX();
				int dz = z - center.getZ();
				if (dx * dx + dz * dz > rSq) continue;

				for (int y = minY; y <= maxY; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (visited.contains(pos)) continue;

					BlockState state = level.getBlockState(pos);
					if (state.is(BlockTags.LOGS)) {
						Optional<DetectedTree> treeOpt = TreeDetector.detect(level, pos);
						if (treeOpt.isPresent()) {
							DetectedTree tree = treeOpt.get();
							for (DetectedTree.TreeBlockData blockData : tree.blocks()) {
								visited.add(tree.base().offset(blockData.relative()));
							}
							visited.add(tree.base());

							TreeCutManager.startFallWithDirection(level, player, tree, direction);
							felledCount++;
						}
					}
				}
			}
		}

		final int count = felledCount;
		final Direction dir = direction;
		source.sendSuccess(() -> Component.literal("§a[Realistic Tree Felling]§r Tajna komenda wykonana! Powalono " + count + " drzew w promieniu " + clampedRadius + " bloków w kierunku " + dir.getName().toUpperCase() + "."), true);
		return felledCount;
	}

	private static Direction parseDirection(String input, Direction fallback) {
		if (input == null || input.isBlank()) return fallback;
		String s = input.trim().toUpperCase();
		return switch (s) {
			case "N", "NORTH" -> Direction.NORTH;
			case "S", "SOUTH" -> Direction.SOUTH;
			case "W", "WEST" -> Direction.WEST;
			case "E", "EAST" -> Direction.EAST;
			default -> fallback;
		};
	}

	private static int inspect(ServerPlayer player) {
		var position = target(player); var tree = TreeDetector.detect((ServerLevel) player.level(), position); if (tree.isEmpty()) { player.sendSystemMessage(Component.translatable("command.peterwolfs_realistic_tree_felling.no_session")); return 0; }
		var value = tree.get(); player.sendSystemMessage(Component.translatable("command.peterwolfs_realistic_tree_felling.inspect", value.blocks().size(), value.height(), value.mass(), "away from player", "opposite facing")); return 1;
	}

	private static BlockPos target(ServerPlayer player) { return player.pick(8.0D, 0.0F, false) instanceof BlockHitResult hit ? hit.getBlockPos() : player.blockPosition(); }
	public static boolean debug() { return debug; }
}

