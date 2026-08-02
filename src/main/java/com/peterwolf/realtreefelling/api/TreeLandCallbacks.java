package com.peterwolf.realtreefelling.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * Public landing hooks for other mods (e.g. Forest Fire) to react when a felled tree hits the ground.
 */
public final class TreeLandCallbacks {
	@FunctionalInterface
	public interface Listener {
		/**
		 * @param placedLogs   log blocks successfully written into the world
		 * @param placedLeaves leaf blocks successfully written (decaying canopy)
		 */
		void onTreeLanded(ServerLevel level, BlockPos anchor, Direction fallDirection, List<BlockPos> placedLogs, List<BlockPos> placedLeaves);
	}

	private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

	private TreeLandCallbacks() {
	}

	public static void register(Listener listener) {
		if (listener != null) {
			LISTENERS.addIfAbsent(listener);
		}
	}

	public static void unregister(Listener listener) {
		LISTENERS.remove(listener);
	}

	public static void fire(ServerLevel level, BlockPos anchor, Direction fallDirection, List<BlockPos> placedLogs, List<BlockPos> placedLeaves) {
		if (LISTENERS.isEmpty()) {
			return;
		}
		List<BlockPos> logs = List.copyOf(placedLogs);
		List<BlockPos> leaves = List.copyOf(placedLeaves);
		for (Listener listener : LISTENERS) {
			try {
				listener.onTreeLanded(level, anchor, fallDirection, logs, leaves);
			} catch (Exception exception) {
				// Never let a listener crash the fall transaction
				exception.printStackTrace();
			}
		}
	}
}
