package com.peterwolf.realtreefelling.client;

import com.peterwolf.realtreefelling.RealTreeFellingMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@Mod(value = RealTreeFellingMod.MOD_ID, dist = Dist.CLIENT)
public final class RealTreeFellingClient {
	public RealTreeFellingClient(IEventBus modBus) {
		modBus.addListener(RealTreeFellingClient::registerRenderers);
	}

	private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
		event.registerEntityRenderer(RealTreeFellingMod.FALLING_TREE.get(), FallingTreeRenderer::new);
	}
}
