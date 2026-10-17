package com.peterwolf.realtreefelling.persistence;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.peterwolf.realtreefelling.RealTreeFellingMod;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Persists cut progress for landed logs, including after a server restart. */
public final class FallenLogSavedData extends SavedData {
	public record FallenLog(BlockPos position, ResourceKey<Level> dimension, int requiredHits, int strikes) {
		private static final Codec<FallenLog> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			BlockPos.CODEC.fieldOf("position").forGetter(FallenLog::position), Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(FallenLog::dimension),
			Codec.INT.fieldOf("required_hits").forGetter(FallenLog::requiredHits), Codec.INT.optionalFieldOf("strikes", 0).forGetter(FallenLog::strikes)
		).apply(instance, FallenLog::new));
	}
	private static final Codec<FallenLogSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(FallenLog.CODEC.listOf().optionalFieldOf("logs", List.of()).forGetter(FallenLogSavedData::logs)).apply(instance, FallenLogSavedData::new));
	private static final SavedDataType<FallenLogSavedData> TYPE = new SavedDataType<>(RealTreeFellingMod.id("fallen_logs").toString(), FallenLogSavedData::new, CODEC, DataFixTypes.LEVEL);
	private final List<FallenLog> logs = new ArrayList<>();
	public FallenLogSavedData() {}
	private FallenLogSavedData(List<FallenLog> logs) { this.logs.addAll(logs); }
	public static FallenLogSavedData get(ServerLevel level) { return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE); }
	public Optional<FallenLog> find(ResourceKey<Level> dimension, BlockPos position) { return logs.stream().filter(log -> log.dimension.equals(dimension) && log.position.equals(position)).findFirst(); }
	public void register(ResourceKey<Level> dimension, BlockPos position, int requiredHits) { remove(dimension, position); logs.add(new FallenLog(position.immutable(), dimension, requiredHits, 0)); setDirty(); }
	public FallenLog increment(FallenLog log) { logs.remove(log); FallenLog updated = new FallenLog(log.position, log.dimension, log.requiredHits, log.strikes + 1); logs.add(updated); setDirty(); return updated; }
	public void remove(ResourceKey<Level> dimension, BlockPos position) { if (logs.removeIf(log -> log.dimension.equals(dimension) && log.position.equals(position))) setDirty(); }
	private List<FallenLog> logs() { return List.copyOf(logs); }
}
