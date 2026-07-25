package com.peterwolf.realtreefelling.client;
import com.peterwolf.realtreefelling.RealTreeFellingMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
public final class RealTreeFellingClient implements ClientModInitializer {
	@Override public void onInitializeClient() { EntityRendererRegistry.register(RealTreeFellingMod.FALLING_TREE, FallingTreeRenderer::new); }
}
