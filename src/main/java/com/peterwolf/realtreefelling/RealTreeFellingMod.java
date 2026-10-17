package com.peterwolf.realtreefelling;

import com.peterwolf.realtreefelling.command.TreeFellingCommands;
import com.peterwolf.realtreefelling.config.TreeFellingConfig;
import com.peterwolf.realtreefelling.cutting.CanopyDecayManager;
import com.peterwolf.realtreefelling.cutting.TreeCutManager;
import com.peterwolf.realtreefelling.entity.FallingTreeEntity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RealTreeFellingMod implements ModInitializer {
	public static final String MOD_ID = "peterwolfs_realistic_tree_felling";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final ResourceKey<EntityType<?>> FALLING_TREE_KEY = ResourceKey.create(Registries.ENTITY_TYPE, id("falling_tree"));
	public static final EntityType<FallingTreeEntity> FALLING_TREE = Registry.register(BuiltInRegistries.ENTITY_TYPE, FALLING_TREE_KEY, EntityType.Builder.of(FallingTreeEntity::new, MobCategory.MISC).sized(1.0F, 1.0F).clientTrackingRange(12).build(FALLING_TREE_KEY));

	public static final SoundEvent TREE_FALL_START = registerSound("tree_fall_start");
	public static final SoundEvent TREE_FALL_IMPACT = registerSound("tree_fall_impact");
	public static final SoundEvent RTF = registerSound("rtf");

	private static SoundEvent registerSound(String name) {
		Identifier id = id(name);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
	}
	@Override public void onInitialize() {
		TreeFellingConfig.load();
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) return !TreeCutManager.onLogBreakAttempt(serverLevel, serverPlayer, pos);
			return true;
		});
		ServerTickEvents.END_WORLD_TICK.register(CanopyDecayManager::tick);
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> TreeFellingCommands.register(dispatcher));
		LOGGER.info("PeterWolf's Realistic Tree Felling initialized.");
	}
	public static Identifier id(String path) { return Identifier.fromNamespaceAndPath(MOD_ID, path); }
}
