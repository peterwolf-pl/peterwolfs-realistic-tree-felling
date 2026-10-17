package com.peterwolf.realtreefelling.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.peterwolf.realtreefelling.RealTreeFellingMod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Small, human-editable server configuration. Reload is intentionally atomic. */
public final class TreeFellingConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("peterwolfs-realistic-tree-felling.json");
	public static TreeFellingConfig INSTANCE = new TreeFellingConfig();
	public boolean enableRealisticFelling = true, requireAxe = true, allowHandCutting = false, requireLeavesForTreeDetection = false;
	public int maximumTreeHeight = 48, maximumTreeBlocks = 256, maximumBranchDistance = 7;
	public boolean supportTwoByTwoTrunks = true, supportModdedLogs = true;
	public double stumpCutTimeMultiplier = 1.8;
	public boolean enableDiagonalFallDirections = false, enableAdvancedHingePhysics = false, enableTreeLean = false, enableWindInfluence = false, enableRandomFallDeviation = false;
	public double maximumRandomFallAngle = 8.0;
	public boolean enableBlockDamage = false, enableEntityDamage = true, enablePlayerDamage = true, enableMobDamage = true, enablePvPDamage = false;
	public double baseEntityDamage = 4.0, leafEntityDamage = 1.0, maximumEntityDamage = 6.0;
	public boolean breakLeavesOnImpact = true, breakPlantsOnImpact = true, breakFragileBlocksOnImpact = false, damageStructures = false;
	public boolean placeTreeBlocksAfterLanding = true, keepTreeAsEntityAfterLanding = false, dropUnplaceableBlocks = true;
	public boolean enableFallDirectionPreview = true, enableLandingZonePreview = true, enableScreenShake = true, enableParticles = true, enableCreakingSounds = true, enableActionBarHints = true;
	public boolean allowSneakVanillaBreaking = true;
	public int treeEntityLifetimeSeconds = 20;

	public static void load() {
		try {
			if (Files.notExists(PATH)) { save(INSTANCE); return; }
			TreeFellingConfig loaded = GSON.fromJson(Files.readString(PATH), TreeFellingConfig.class);
			if (loaded != null) INSTANCE = loaded;
		} catch (Exception exception) { RealTreeFellingMod.LOGGER.error("Could not load tree-felling configuration; using safe defaults", exception); }
	}
	private static void save(TreeFellingConfig config) throws IOException { Files.createDirectories(PATH.getParent()); Files.writeString(PATH, GSON.toJson(config)); }
}
