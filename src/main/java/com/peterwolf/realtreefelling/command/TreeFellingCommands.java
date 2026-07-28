package com.peterwolf.realtreefelling.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.peterwolf.realtreefelling.config.TreeFellingConfig;
import com.peterwolf.realtreefelling.cutting.TreeCutManager;
import com.peterwolf.realtreefelling.detection.DetectedTree;
import com.peterwolf.realtreefelling.detection.TreeDetector;
import com.peterwolf.realtreefelling.persistence.FallenLogSavedData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;

public final class TreeFellingCommands {
	private static boolean debug;

	private static final Map<String, Block> SAPLING_ALIASES = Map.ofEntries(
		Map.entry("oak", Blocks.OAK_SAPLING),
		Map.entry("oak_sapling", Blocks.OAK_SAPLING),
		Map.entry("birch", Blocks.BIRCH_SAPLING),
		Map.entry("birch_sapling", Blocks.BIRCH_SAPLING),
		Map.entry("spruce", Blocks.SPRUCE_SAPLING),
		Map.entry("spruce_sapling", Blocks.SPRUCE_SAPLING),
		Map.entry("jungle", Blocks.JUNGLE_SAPLING),
		Map.entry("jungle_sapling", Blocks.JUNGLE_SAPLING),
		Map.entry("acacia", Blocks.ACACIA_SAPLING),
		Map.entry("acacia_sapling", Blocks.ACACIA_SAPLING),
		Map.entry("dark_oak", Blocks.DARK_OAK_SAPLING),
		Map.entry("darkoak", Blocks.DARK_OAK_SAPLING),
		Map.entry("dark_oak_sapling", Blocks.DARK_OAK_SAPLING),
		Map.entry("cherry", Blocks.CHERRY_SAPLING),
		Map.entry("cherry_sapling", Blocks.CHERRY_SAPLING),
		Map.entry("pale_oak", Blocks.PALE_OAK_SAPLING),
		Map.entry("paleoak", Blocks.PALE_OAK_SAPLING),
		Map.entry("pale_oak_sapling", Blocks.PALE_OAK_SAPLING),
		Map.entry("mangrove", Blocks.MANGROVE_PROPAGULE),
		Map.entry("mangrove_propagule", Blocks.MANGROVE_PROPAGULE),
		Map.entry("propagule", Blocks.MANGROVE_PROPAGULE),
		Map.entry("bamboo", Blocks.BAMBOO_SAPLING),
		Map.entry("bamboo_sapling", Blocks.BAMBOO_SAPLING)
	);

	private static final SuggestionProvider<CommandSourceStack> SAPLING_SUGGESTIONS = (context, builder) ->
		SharedSuggestionProvider.suggest(List.of(
			"oak", "birch", "spruce", "jungle", "acacia", "dark_oak", "cherry", "pale_oak", "mangrove", "bamboo"
		), builder);

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
			.then(Commands.literal("removelogs")
				.then(Commands.argument("radius", IntegerArgumentType.integer(1, 200))
					.executes(context -> removeLogs(context.getSource(), IntegerArgumentType.getInteger(context, "radius")))))
			.then(Commands.literal("reseed")
				.then(Commands.argument("radius", IntegerArgumentType.integer(1, 200))
					.then(Commands.argument("spacing", IntegerArgumentType.integer(1, 64))
						.then(Commands.argument("saplings", StringArgumentType.greedyString())
							.suggests(SAPLING_SUGGESTIONS)
							.executes(context -> reseed(
								context.getSource(),
								IntegerArgumentType.getInteger(context, "radius"),
								IntegerArgumentType.getInteger(context, "spacing"),
								StringArgumentType.getString(context, "saplings")))))))
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

	/** Removes every log block in a horizontal radius around the player (no drops). */
	private static int removeLogs(CommandSourceStack source, int radius) {
		ServerPlayer player;
		try {
			player = source.getPlayerOrException();
		} catch (Exception e) {
			source.sendFailure(Component.literal("Komenda może być wykonana tylko przez gracza."));
			return 0;
		}

		ServerLevel level = (ServerLevel) player.level();
		BlockPos center = player.blockPosition();
		int clampedRadius = Math.clamp(radius, 1, 200);
		int rSq = clampedRadius * clampedRadius;

		int minX = center.getX() - clampedRadius;
		int maxX = center.getX() + clampedRadius;
		int minZ = center.getZ() - clampedRadius;
		int maxZ = center.getZ() + clampedRadius;
		// Tall standing trunks + fallen logs on slopes.
		int minY = Math.max(level.getMinY(), center.getY() - Math.max(clampedRadius, 20));
		int maxY = Math.min(level.getMaxY(), center.getY() + Math.max(clampedRadius, 40));

		FallenLogSavedData fallenData = FallenLogSavedData.get(level);
		int removed = 0;

		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				int dx = x - center.getX();
				int dz = z - center.getZ();
				if (dx * dx + dz * dz > rSq) continue;

				for (int y = minY; y <= maxY; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					BlockState state = level.getBlockState(pos);
					if (!state.is(BlockTags.LOGS)) continue;

					fallenData.remove(level.dimension(), pos);
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
					removed++;
				}
			}
		}

		final int count = removed;
		source.sendSuccess(() -> Component.literal("§a[Realistic Tree Felling]§r Usunięto " + count + " kłód w promieniu " + clampedRadius + " bloków."), true);
		return removed;
	}

	/**
	 * Plants saplings on a grid within a horizontal radius.
	 * Usage: /timber reseed &lt;radius&gt; &lt;spacing&gt; &lt;sapling…&gt;
	 * Multiple sapling types are chosen randomly per plant.
	 */
	private static int reseed(CommandSourceStack source, int radius, int spacing, String saplingRaw) {
		ServerPlayer player;
		try {
			player = source.getPlayerOrException();
		} catch (Exception e) {
			source.sendFailure(Component.literal("Komenda może być wykonana tylko przez gracza."));
			return 0;
		}

		List<Block> saplings = parseSaplings(saplingRaw);
		if (saplings.isEmpty()) {
			source.sendFailure(Component.literal("Nieprawidłowy typ sadzonki. Użyj np.: oak birch spruce dark_oak cherry mangrove."));
			return 0;
		}

		ServerLevel level = (ServerLevel) player.level();
		BlockPos center = player.blockPosition();
		int clampedRadius = Math.clamp(radius, 1, 200);
		int clampedSpacing = Math.clamp(spacing, 1, 64);
		int rSq = clampedRadius * clampedRadius;

		int planted = 0;
		int skipped = 0;
		var random = ThreadLocalRandom.current();

		// Align the grid so it is centered near the player.
		int originX = center.getX() - ((center.getX() % clampedSpacing + clampedSpacing) % clampedSpacing);
		int originZ = center.getZ() - ((center.getZ() % clampedSpacing + clampedSpacing) % clampedSpacing);

		for (int x = originX - clampedRadius; x <= originX + clampedRadius; x += clampedSpacing) {
			for (int z = originZ - clampedRadius; z <= originZ + clampedRadius; z += clampedSpacing) {
				int dx = x - center.getX();
				int dz = z - center.getZ();
				if (dx * dx + dz * dz > rSq) continue;

				int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				if (surfaceY < level.getMinY() || surfaceY > level.getMaxY()) {
					skipped++;
					continue;
				}

				BlockPos placePos = new BlockPos(x, surfaceY, z);
				BlockState existing = level.getBlockState(placePos);
				if (!existing.isAir() && !existing.canBeReplaced()) {
					skipped++;
					continue;
				}

				Block saplingBlock = saplings.get(random.nextInt(saplings.size()));
				BlockState saplingState = saplingBlock.defaultBlockState();
				if (!saplingState.canSurvive(level, placePos)) {
					skipped++;
					continue;
				}

				level.setBlock(placePos, saplingState, Block.UPDATE_ALL);
				planted++;
			}
		}

		final int count = planted;
		final int miss = skipped;
		final String types = saplings.stream()
			.map(block -> BuiltInRegistries.BLOCK.getKey(block).getPath())
			.reduce((a, b) -> a + ", " + b)
			.orElse("?");
		source.sendSuccess(() -> Component.literal(
			"§a[Realistic Tree Felling]§r Zasadzono " + count + " sadzonek (pominięto " + miss
				+ ") w promieniu " + clampedRadius + ", co " + clampedSpacing + " bloków. Typy: " + types + "."
		), true);
		return planted;
	}

	private static List<Block> parseSaplings(String raw) {
		List<Block> result = new ArrayList<>();
		if (raw == null || raw.isBlank()) return result;

		for (String token : raw.trim().split("\\s+")) {
			if (token.isBlank()) continue;
			Block block = resolveSapling(token);
			if (block == null) return List.of(); // any invalid token aborts
			if (!result.contains(block)) result.add(block);
		}
		return result;
	}

	private static Block resolveSapling(String token) {
		String key = token.trim().toLowerCase(Locale.ROOT);
		if (key.startsWith("minecraft:")) key = key.substring("minecraft:".length());

		Block alias = SAPLING_ALIASES.get(key);
		if (alias != null) return alias;

		// Full / registry path: oak_sapling, minecraft:birch_sapling, modid:custom_sapling
		String idString = token.contains(":") ? token.trim().toLowerCase(Locale.ROOT) : "minecraft:" + key;
		Identifier id = Identifier.tryParse(idString);
		if (id == null) return null;

		Optional<Block> optional = BuiltInRegistries.BLOCK.getOptional(id);
		if (optional.isEmpty()) return null;

		Block block = optional.get();
		if (block instanceof SaplingBlock || block instanceof BambooSaplingBlock) return block;
		// Allow other plantable “sapling-like” blocks resolved by id if they can exist as default state.
		String path = id.getPath();
		if (path.contains("sapling") || path.contains("propagule")) return block;
		return null;
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
