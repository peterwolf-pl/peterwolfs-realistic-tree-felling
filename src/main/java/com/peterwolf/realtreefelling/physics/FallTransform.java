package com.peterwolf.realtreefelling.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Single authoritative coordinate transform for both visual fall direction and landed blocks. */
public final class FallTransform {
	private FallTransform() {}

	/**
	 * Maps an upright tree-local offset onto the ground after a 90° fall in {@code direction}.
	 * <p>
	 * Original height becomes distance along the fall axis (trunk tip / crown end). Only the
	 * horizontal component perpendicular to the fall is kept as lateral spread — the original
	 * component along the fall axis would map into the ground or into the air, and keeping it
	 * smeared the canopy along the whole trunk. Y is never raised above the stump plane so the
	 * crown lies flat at the far end instead of forming a mound.
	 */
	public static BlockPos landedPosition(BlockPos anchor, BlockPos relative, Direction direction) {
		int fx = direction.getStepX();
		int fz = direction.getStepZ();
		// Height → distance along fall. Drop the original fall-axis horizontal component.
		int along = relative.getY();
		int sideX = fx == 0 ? relative.getX() : 0;
		int sideZ = fz == 0 ? relative.getZ() : 0;
		// Flat on the stump plane (allow rare negative relatives below the base).
		int y = Math.min(relative.getY(), 0);
		return anchor.offset(fx * along + sideX, y, fz * along + sideZ);
	}

	public static float rendererYaw(Direction direction) {
		// Positive model Y is world-up. Positive Z tilt then points local west.
		return switch (direction) { case NORTH -> -90.0F; case SOUTH -> 90.0F; case WEST -> 0.0F; case EAST -> 180.0F; default -> 0.0F; };
	}
}
