package com.peterwolf.realtreefelling.cutting;

import net.minecraft.core.Direction;

/** Horizontal direction in which a tree moves, always away from the chopper. */
public enum FallDirection {
	NORTH(Direction.NORTH), SOUTH(Direction.SOUTH), EAST(Direction.EAST), WEST(Direction.WEST);
	private final Direction direction;
	FallDirection(Direction direction) { this.direction = direction; }
	public Direction direction() { return direction; }
	public static FallDirection from(Direction direction) { return switch (direction) { case NORTH -> NORTH; case SOUTH -> SOUTH; case EAST -> EAST; default -> WEST; }; }
}
