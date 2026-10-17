package com.peterwolf.realtreefelling;

import com.peterwolf.realtreefelling.command.TreeFellingCommands;
import com.peterwolf.realtreefelling.config.TreeFellingConfig;
import com.peterwolf.realtreefelling.cutting.CanopyDecayManager;
import com.peterwolf.realtreefelling.cutting.TreeCutManager;
import com.peterwolf.realtreefelling.entity.FallingTreeEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(RealTreeFellingMod.MOD_ID)
public final class RealTreeFellingMod {
	public static final String MOD_ID = "peterwolfs_realistic_tree_felling";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final DeferredRegister.Entities ENTITY_TYPES = DeferredRegister.createEntities(MOD_ID);
	public static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(Registries.SOUND_EVENT, MOD_ID);

	private static final ResourceKey<EntityType<?>> FALLING_TREE_KEY = ResourceKey.create(Registries.ENTITY_TYPE, id("falling_tree"));
	public static final DeferredHolder<EntityType<?>, EntityType<FallingTreeEntity>> FALLING_TREE = ENTITY_TYPES.register(
		"falling_tree",
		() -> EntityType.Builder.<FallingTreeEntity>of(FallingTreeEntity::new, MobCategory.MISC)
			.sized(1.0F, 1.0F)
			.clientTrackingRange(12)
			.build(FALLING_TREE_KEY)
	);

	public static final DeferredHolder<SoundEvent, SoundEvent> TREE_FALL_START = registerSound("tree_fall_start");
	public static final DeferredHolder<SoundEvent, SoundEvent> TREE_FALL_IMPACT = registerSound("tree_fall_impact");
	public static final DeferredHolder<SoundEvent, SoundEvent> RTF = registerSound("rtf");

	private static DeferredHolder<SoundEvent, SoundEvent> registerSound(String name) {
		return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(id(name)));
	}

	public RealTreeFellingMod(IEventBus modBus) {
		ENTITY_TYPES.register(modBus);
		SOUND_EVENTS.register(modBus);
		TreeFellingConfig.load();
		NeoForge.EVENT_BUS.register(GameEvents.class);
		LOGGER.info("PeterWolf's Realistic Tree Felling initialized.");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	public static final class GameEvents {
		private GameEvents() {
		}

		@SubscribeEvent
		public static void onBlockBreak(BreakBlockEvent event) {
			if (event.getLevel() instanceof ServerLevel serverLevel && event.getPlayer() instanceof ServerPlayer serverPlayer) {
				if (TreeCutManager.onLogBreakAttempt(serverLevel, serverPlayer, event.getPos())) {
					event.setCanceled(true);
					event.setNotifyClient(true);
				}
			}
		}

		@SubscribeEvent
		public static void onLevelTick(LevelTickEvent.Post event) {
			if (event.getLevel() instanceof ServerLevel serverLevel) {
				CanopyDecayManager.tick(serverLevel);
			}
		}

		@SubscribeEvent
		public static void onRegisterCommands(RegisterCommandsEvent event) {
			TreeFellingCommands.register(event.getDispatcher());
		}
	}
}
