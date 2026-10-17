package com.peterwolf.realtreefelling.entity;

import com.peterwolf.realtreefelling.RealTreeFellingMod;
import com.peterwolf.realtreefelling.api.TreeLandCallbacks;
import com.peterwolf.realtreefelling.config.TreeFellingConfig;
import com.peterwolf.realtreefelling.cutting.CanopyDecayManager;
import com.peterwolf.realtreefelling.cutting.FallDirection;
import com.peterwolf.realtreefelling.cutting.FallenLogManager;
import com.peterwolf.realtreefelling.detection.DetectedTree;
import com.peterwolf.realtreefelling.physics.FallTransform;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LinearInterpolationHandler;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** A single persistent entity owns the blocks until its landing transaction completes. */
public final class FallingTreeEntity extends Entity {
	private static final int FALL_TICKS = 40;
	private static final EntityDataAccessor<Integer> AGE = SynchedEntityData.defineId(FallingTreeEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> HEIGHT = SynchedEntityData.defineId(FallingTreeEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> DIRECTION = SynchedEntityData.defineId(FallingTreeEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<String> VISUAL_LOG_BLOCK = SynchedEntityData.defineId(FallingTreeEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<String> VISUAL_LEAF_BLOCK = SynchedEntityData.defineId(FallingTreeEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<String> VISUAL_GEOMETRY = SynchedEntityData.defineId(FallingTreeEntity.class, EntityDataSerializers.STRING);
	private final List<TreeBlock> blocks = new ArrayList<>(); private BlockPos anchor = BlockPos.ZERO;
	private final Map<Integer, Float> leafDamageDealt = new HashMap<>();
	private String decodedGeometry = ""; private List<VisualBlock> decodedVisualBlocks = List.of();
	public FallingTreeEntity(EntityType<? extends FallingTreeEntity> type, Level level) { super(type, level); this.blocksBuilding = true; }
	public void configure(DetectedTree tree, FallDirection direction) {
		anchor = tree.base(); entityData.set(HEIGHT, tree.height()); entityData.set(DIRECTION, direction.direction().get2DDataValue());
		entityData.set(VISUAL_LOG_BLOCK, visualBlockId(tree, BlockTags.LOGS, Blocks.OAK_LOG));
		entityData.set(VISUAL_LEAF_BLOCK, visualBlockId(tree, BlockTags.LEAVES, Blocks.OAK_LEAVES));
		blocks.clear(); for (var data : tree.blocks()) blocks.add(new TreeBlock(data.relative(), data.state()));
		entityData.set(VISUAL_GEOMETRY, encodeVisualGeometry(blocks));
	}
	@Override protected void defineSynchedData(SynchedEntityData.Builder data) {
		data.define(AGE, 0); data.define(HEIGHT, 2); data.define(DIRECTION, Direction.NORTH.get2DDataValue());
		data.define(VISUAL_LOG_BLOCK, "minecraft:oak_log"); data.define(VISUAL_LEAF_BLOCK, "minecraft:oak_leaves");
		data.define(VISUAL_GEOMETRY, "");
	}
	/** Starts slowly, then accelerates into the landing tick instead of rotating at a constant speed. */
	public float tilt(float partial) { float progress = Math.clamp((entityData.get(AGE) + partial) / FALL_TICKS, 0.0F, 1.0F); return 90.0F * progress * progress; }
	public int height() { return entityData.get(HEIGHT); }
	public Direction fallDirection() { return Direction.from2DDataValue(entityData.get(DIRECTION)); }
	/** Source species selected by the server, synchronized so every client sees the felled tree rather than oak. */
	public BlockState visualLogState() { return blockState(entityData.get(VISUAL_LOG_BLOCK), Blocks.OAK_LOG); }
	public BlockState visualLeafState() { return blockState(entityData.get(VISUAL_LEAF_BLOCK), Blocks.OAK_LEAVES); }
	/** Exact relative tree layout, supplied by the server for the client-only falling animation. */
	public List<VisualBlock> visualBlocks() {
		String encoded = entityData.get(VISUAL_GEOMETRY);
		if (!encoded.equals(decodedGeometry)) { decodedGeometry = encoded; decodedVisualBlocks = decodeVisualGeometry(encoded); }
		return decodedVisualBlocks;
	}
	@Override public void tick() {
		super.tick();
		if (level().isClientSide()) {
			getInterpolation().interpolate();
			return;
		}
		int age = entityData.get(AGE) + 1;
		entityData.set(AGE, age);
		if (TreeFellingConfig.INSTANCE.enableEntityDamage) {
			checkAndApplyEntityDamage((ServerLevel) level());
		}
		if (age >= FALL_TICKS) land((ServerLevel) level());
	}

	private void checkAndApplyEntityDamage(ServerLevel level) {
		if (blocks.isEmpty()) return;
		int age = entityData.get(AGE);
		float progress = Math.clamp((float) age / FALL_TICKS, 0.0F, 1.0F);
		float tiltDeg = 90.0F * progress * progress;
		double tiltRad = Math.toRadians(tiltDeg);
		double cosTilt = Math.cos(tiltRad);
		double sinTilt = Math.sin(tiltRad);

		Direction direction = fallDirection();
		int maxH = Math.max(2, height());
		net.minecraft.world.phys.AABB searchBox = new net.minecraft.world.phys.AABB(anchor).inflate(maxH + 4);
		List<net.minecraft.world.entity.LivingEntity> candidateEntities = level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, searchBox, e -> e.isAlive() && !e.isSpectator());

		if (candidateEntities.isEmpty()) return;

		Set<net.minecraft.world.entity.LivingEntity> logHits = new HashSet<>();
		Set<net.minecraft.world.entity.LivingEntity> leafHits = new HashSet<>();

		for (TreeBlock treeBlock : blocks) {
			BlockPos rel = treeBlock.relative;
			boolean isLog = treeBlock.state.is(BlockTags.LOGS);
			boolean isLeaf = treeBlock.state.is(BlockTags.LEAVES);
			if (!isLog && !isLeaf) continue;

			double localX = rel.getX();
			double localY = rel.getY() + 0.5;
			double localZ = rel.getZ();

			double x1 = localX * cosTilt - localY * sinTilt;
			double y1 = localX * sinTilt + localY * cosTilt;
			double z1 = localZ;

			double worldOffsetX;
			double worldOffsetZ;
			switch (direction) {
				case NORTH -> { worldOffsetX = -z1; worldOffsetZ = x1; }
				case SOUTH -> { worldOffsetX = z1; worldOffsetZ = -x1; }
				case EAST -> { worldOffsetX = -x1; worldOffsetZ = -z1; }
				default -> { worldOffsetX = x1; worldOffsetZ = z1; }
			}

			double worldX = anchor.getX() + 0.5 + worldOffsetX;
			double worldY = anchor.getY() + y1;
			double worldZ = anchor.getZ() + 0.5 + worldOffsetZ;

			net.minecraft.world.phys.AABB blockAABB = new net.minecraft.world.phys.AABB(worldX - 0.55, worldY - 0.55, worldZ - 0.55, worldX + 0.55, worldY + 0.55, worldZ + 0.55);

			for (net.minecraft.world.entity.LivingEntity entity : candidateEntities) {
				if (!entity.isAlive()) continue;

				if (entity instanceof net.minecraft.server.level.ServerPlayer player) {
					if (player.isCreative() || !TreeFellingConfig.INSTANCE.enablePlayerDamage) continue;
				} else {
					if (!TreeFellingConfig.INSTANCE.enableMobDamage) continue;
				}

				if (entity.getBoundingBox().intersects(blockAABB)) {
					if (isLog) logHits.add(entity);
					else leafHits.add(entity);
				}
			}
		}

		for (net.minecraft.world.entity.LivingEntity entity : logHits) {
			if (!entity.isAlive()) continue;
			entity.setInvulnerableTime(0);
			entity.hurtServer(level, level.damageSources().fallingBlock(this), 1000.0F);
		}

		TreeFellingConfig config = TreeFellingConfig.INSTANCE;
		float leafDamage = (float) Math.max(0.0, config.leafEntityDamage);
		float leafCap = (float) Math.max(0.0, config.maximumEntityDamage);
		if (leafDamage <= 0.0F || leafCap <= 0.0F) return;

		for (net.minecraft.world.entity.LivingEntity entity : leafHits) {
			if (!entity.isAlive() || logHits.contains(entity)) continue;
			float already = leafDamageDealt.getOrDefault(entity.getId(), 0.0F);
			float remaining = leafCap - already;
			if (remaining <= 0.0F) continue;
			float amount = Math.min(leafDamage, remaining);
			if (entity.hurtServer(level, level.damageSources().fallingBlock(this), amount)) {
				leafDamageDealt.merge(entity.getId(), amount, Float::sum);
			}
		}
	}
	private void land(ServerLevel level) {
		Direction direction = fallDirection();
		// Over a chasm / void (no terrain at stump height or just below) the whole tree shatters into block items.
		if (isFallingIntoChasm(level, direction)) {
			shatterIntoBlocks(level, direction);
			discard();
			return;
		}
		List<BlockPos> placedLogs = new ArrayList<>();
		List<BlockPos> placedLeaves = new ArrayList<>();
		for (TreeBlock treeBlock : blocks) {
			BlockPos r = treeBlock.relative;
			boolean leaf = treeBlock.state.is(BlockTags.LEAVES);
			// Same transform as logs: canopy lies flat with the trunk — no elevated leaf mound.
			BlockPos destination = FallTransform.landedPosition(anchor, r, direction);
			if (leaf) {
				BlockPos leafAt = placeOrBreakLeaf(level, destination, r, direction, treeBlock.state);
				if (leafAt != null) {
					placedLeaves.add(leafAt);
				}
				continue;
			}
			BlockState existing = level.getBlockState(destination);
			BlockState landedState = rotateLogTowardFall(treeBlock.state, direction);
			if (existing.isAir() || existing.canBeReplaced()) {
				level.setBlock(destination, landedState, Block.UPDATE_ALL);
				if (landedState.is(BlockTags.LOGS)) {
					FallenLogManager.register(level, destination);
					placedLogs.add(destination.immutable());
				}
			} else if (TreeFellingConfig.INSTANCE.dropUnplaceableBlocks) {
				Block.popResource(level, destination, new ItemStack(treeBlock.state.getBlock()));
			}
		}
		BlockPos impactPos = anchor.offset(direction.getStepX() * Math.max(1, height() / 2), 0, direction.getStepZ() * Math.max(1, height() / 2));
		level.playSound(null, impactPos, RealTreeFellingMod.TREE_FALL_IMPACT, SoundSource.BLOCKS, 1.6F, 0.9F + level.getRandom().nextFloat() * 0.2F);
		TreeLandCallbacks.fire(level, anchor, direction, placedLogs, placedLeaves);
		discard();
	}

	/**
	 * Half the canopy breaks into debris on impact (leaf blocks / sticks / branch wood).
	 * The other half stays in the landed crown zone (far end of the trunk), flat — never piled
	 * into a tall mound — and slowly disappears like leaves after the trunk is gone.
	 */
	/** @return placed leaf position, or null when debris-only / no free spot */
	private BlockPos placeOrBreakLeaf(ServerLevel level, BlockPos destination, BlockPos relative, Direction direction, BlockState leaf) {
		if (TreeFellingConfig.INSTANCE.breakLeavesOnImpact && isImpactCanopyHalf(relative)) {
			dropLeafDebris(level, destination, leaf);
			return null;
		}
		BlockPos placeAt = findFlatCrownSpot(level, destination, direction);
		if (placeAt != null) {
			BlockState decaying = CanopyDecayManager.decayingState(leaf);
			// Clients only: avoid immediate neighbour rewrites from adjacent fallen logs before we schedule decay.
			level.setBlock(placeAt, decaying, Block.UPDATE_CLIENTS);
			int delay = 100 + level.getRandom().nextInt(300); // ~5–20 s, staggered so the canopy fades gradually
			CanopyDecayManager.schedule(level, placeAt, delay);
			return placeAt.immutable();
		} else if (TreeFellingConfig.INSTANCE.dropUnplaceableBlocks) {
			dropLeafDebris(level, destination, leaf);
		}
		return null;
	}

	/**
	 * Prefer an empty cell in the flat crown plane at {@code preferred}. If that cell is the
	 * trunk or another leaf, try lateral neighbours first; only then a single block above —
	 * never a multi-block leaf tower. Returns null when no flat-ish slot is free.
	 */
	private static BlockPos findFlatCrownSpot(ServerLevel level, BlockPos preferred, Direction fallDirection) {
		if (isReplaceable(level, preferred)) return preferred;
		// Lateral offsets in the crown plane (perpendicular to fall + a little along the fall).
		int fx = fallDirection.getStepX();
		int fz = fallDirection.getStepZ();
		int[][] offsets = {
			{fz, 0, -fx}, {-fz, 0, fx}, // true sideways
			{fx, 0, fz}, {-fx, 0, -fz}, // a little along the trunk axis
			{fz + fx, 0, -fx + fz}, {-fz - fx, 0, fx - fz},
			{fz - fx, 0, -fx - fz}, {-fz + fx, 0, fx + fz},
			{0, 1, 0}, // last resort: one block on top of the log, still not a mound
		};
		for (int[] o : offsets) {
			BlockPos candidate = preferred.offset(o[0], o[1], o[2]);
			if (isReplaceable(level, candidate)) return candidate;
		}
		return null;
	}

	private static boolean isReplaceable(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.isAir() || state.canBeReplaced();
	}

	/** Random impact debris: leaf block, stick, or a matching branch log piece. */
	private void dropLeafDebris(ServerLevel level, BlockPos pos, BlockState leaf) {
		int roll = level.getRandom().nextInt(3);
		ItemStack drop = switch (roll) {
			case 0 -> new ItemStack(leaf.getBlock()); // blok liści
			case 1 -> new ItemStack(net.minecraft.world.item.Items.STICK); // patyk
			default -> new ItemStack(branchLogFor(leaf.getBlock())); // konar
		};
		Block.popResource(level, pos, drop);
	}

	/** Matching log used as a "branch" piece when canopy shatters. */
	private Block branchLogFor(Block leaf) {
		if (leaf == Blocks.OAK_LEAVES) return Blocks.OAK_LOG;
		if (leaf == Blocks.BIRCH_LEAVES) return Blocks.BIRCH_LOG;
		if (leaf == Blocks.SPRUCE_LEAVES) return Blocks.SPRUCE_LOG;
		if (leaf == Blocks.JUNGLE_LEAVES) return Blocks.JUNGLE_LOG;
		if (leaf == Blocks.ACACIA_LEAVES) return Blocks.ACACIA_LOG;
		if (leaf == Blocks.DARK_OAK_LEAVES) return Blocks.DARK_OAK_LOG;
		if (leaf == Blocks.MANGROVE_LEAVES) return Blocks.MANGROVE_LOG;
		if (leaf == Blocks.CHERRY_LEAVES) return Blocks.CHERRY_LOG;
		if (leaf == Blocks.PALE_OAK_LEAVES) return Blocks.PALE_OAK_LOG;
		if (leaf == Blocks.AZALEA_LEAVES || leaf == Blocks.FLOWERING_AZALEA_LEAVES) return Blocks.OAK_LOG;
		// Prefer the tree's visual log species for modded / unknown leaves.
		Block visual = visualLogState().getBlock();
		return visual.defaultBlockState().is(BlockTags.LOGS) ? visual : Blocks.OAK_LOG;
	}

	/** True when a substantial share of the fallen trunk path has no solid ground at/below stump height. */
	private boolean isFallingIntoChasm(ServerLevel level, Direction direction) {
		int h = Math.max(2, height());
		int unsupported = 0;
		for (int dist = 1; dist <= h; dist++) {
			BlockPos alongTrunk = anchor.offset(direction.getStepX() * dist, 0, direction.getStepZ() * dist);
			if (!hasGroundAtOrBelow(level, alongTrunk)) unsupported++;
		}
		return unsupported * 2 > h;
	}

	/** Solid terrain at the stump height cell or a few blocks under it (mild slopes still count as support). */
	private static boolean hasGroundAtOrBelow(ServerLevel level, BlockPos stumpHeightPos) {
		int minY = level.getMinY();
		for (int dy = 1; dy <= 4; dy++) {
			BlockPos below = stumpHeightPos.below(dy);
			if (below.getY() < minY) return false;
			BlockState state = level.getBlockState(below);
			if (state.isFaceSturdy(level, below, Direction.UP)) return true;
			if (!state.isAir() && !state.canBeReplaced() && !state.getCollisionShape(level, below).isEmpty()) return true;
		}
		return false;
	}

	/** Drop every captured log/leaf as its block item at the last solid edge (so loot is not lost in the void). */
	private void shatterIntoBlocks(ServerLevel level, Direction direction) {
		BlockPos dropAt = lastSupportedAlongFall(level, direction);
		for (TreeBlock treeBlock : blocks) {
			Block.popResource(level, dropAt, new ItemStack(treeBlock.state.getBlock()));
		}
		level.playSound(null, dropAt, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 1.2F, 0.7F);
	}

	private BlockPos lastSupportedAlongFall(ServerLevel level, Direction direction) {
		BlockPos last = anchor;
		int h = Math.max(2, height());
		for (int dist = 1; dist <= h; dist++) {
			BlockPos alongTrunk = anchor.offset(direction.getStepX() * dist, 0, direction.getStepZ() * dist);
			if (!hasGroundAtOrBelow(level, alongTrunk)) break;
			last = alongTrunk;
		}
		return last;
	}

	/** Deterministic ~50% of the crown: checkerboard in tree-local space so impact looks shattered. */
	private static boolean isImpactCanopyHalf(BlockPos relative) {
		// Mix height into the parity so vertical crowns (x≈z≈0) still split cleanly in half.
		return ((relative.getX() * 3 + relative.getY() * 5 + relative.getZ() * 7) & 1) == 0;
	}
	private static BlockState rotateLogTowardFall(BlockState state, Direction direction) {
		if (state.is(BlockTags.LOGS) && state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)) return state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, direction.getAxis());
		return state;
	}
	private static String visualBlockId(DetectedTree tree, net.minecraft.tags.TagKey<Block> tag, Block fallback) {
		return tree.blocks().stream().map(DetectedTree.TreeBlockData::state).filter(state -> state.is(tag)).findFirst()
			.map(state -> BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()).orElseGet(() -> BuiltInRegistries.BLOCK.getKey(fallback).toString());
	}
	private static BlockState blockState(String rawId, Block fallback) {
		Identifier id = Identifier.tryParse(rawId);
		Block block = id == null ? fallback : BuiltInRegistries.BLOCK.getOptional(id).orElse(fallback);
		return block.defaultBlockState();
	}
	private static String encodeVisualGeometry(List<TreeBlock> blocks) {
		byte[] data = new byte[blocks.size() * Integer.BYTES]; int offset = 0;
		for (TreeBlock treeBlock : blocks) {
			BlockPos relative = treeBlock.relative;
			if (relative.getX() < -512 || relative.getX() > 511 || relative.getY() < 0 || relative.getY() > 1_023 || relative.getZ() < -512 || relative.getZ() > 511) throw new IllegalArgumentException("Falling-tree geometry exceeds its synchronized bounds");
			int packed = (treeBlock.state.is(BlockTags.LEAVES) ? 1 << 30 : 0) | ((relative.getX() + 512) << 20) | (relative.getY() << 10) | (relative.getZ() + 512);
			data[offset++] = (byte) (packed >>> 24); data[offset++] = (byte) (packed >>> 16); data[offset++] = (byte) (packed >>> 8); data[offset++] = (byte) packed;
		}
		return Base64.getEncoder().encodeToString(data);
	}
	private static List<VisualBlock> decodeVisualGeometry(String encoded) {
		if (encoded.isEmpty()) return List.of();
		try {
			byte[] data = Base64.getDecoder().decode(encoded); if (data.length % Integer.BYTES != 0) return List.of();
			List<VisualBlock> visualBlocks = new ArrayList<>(data.length / Integer.BYTES);
			for (int offset = 0; offset < data.length; offset += Integer.BYTES) {
				int packed = ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16) | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
				visualBlocks.add(new VisualBlock(new BlockPos(((packed >>> 20) & 0x3FF) - 512, (packed >>> 10) & 0x3FF, (packed & 0x3FF) - 512), (packed & (1 << 30)) != 0));
			}
			return List.copyOf(visualBlocks);
		} catch (IllegalArgumentException ignored) { return List.of(); }
	}
	@Override public boolean isClientAuthoritative() { return false; }
	@Override public boolean hurtServer(ServerLevel level, net.minecraft.world.damagesource.DamageSource source, float amount) { return false; }
	@Override protected boolean isLocalClientAuthoritative() { return false; }
	@Override protected InterpolationHandler createInterpolationHandler() { return LinearInterpolationHandler.create(this, 3); }
	@Override protected void readAdditionalSaveData(ValueInput input) {
		anchor = new BlockPos(input.getIntOr("AnchorX", 0), input.getIntOr("AnchorY", 0), input.getIntOr("AnchorZ", 0)); entityData.set(AGE, input.getIntOr("Age", 0)); entityData.set(HEIGHT, input.getIntOr("Height", 2)); entityData.set(DIRECTION, input.getIntOr("Direction", 2)); entityData.set(VISUAL_LOG_BLOCK, input.getStringOr("VisualLogBlock", "minecraft:oak_log")); entityData.set(VISUAL_LEAF_BLOCK, input.getStringOr("VisualLeafBlock", "minecraft:oak_leaves"));
		blocks.clear(); int count = input.getIntOr("BlockCount", 0); for (int i = 0; i < count; i++) { Identifier id = Identifier.tryParse(input.getStringOr("Block" + i, "minecraft:oak_log")); Block b = id == null ? Blocks.OAK_LOG : BuiltInRegistries.BLOCK.getValue(id); blocks.add(new TreeBlock(new BlockPos(input.getIntOr("X" + i, 0), input.getIntOr("Y" + i, 0), input.getIntOr("Z" + i, 0)), b.defaultBlockState())); }
		entityData.set(VISUAL_GEOMETRY, input.getStringOr("VisualGeometry", encodeVisualGeometry(blocks)));
	}
	@Override protected void addAdditionalSaveData(ValueOutput output) {
		output.putInt("AnchorX", anchor.getX()); output.putInt("AnchorY", anchor.getY()); output.putInt("AnchorZ", anchor.getZ()); output.putInt("Age", entityData.get(AGE)); output.putInt("Height", height()); output.putInt("Direction", entityData.get(DIRECTION)); output.putString("VisualLogBlock", entityData.get(VISUAL_LOG_BLOCK)); output.putString("VisualLeafBlock", entityData.get(VISUAL_LEAF_BLOCK)); output.putString("VisualGeometry", entityData.get(VISUAL_GEOMETRY)); output.putInt("BlockCount", blocks.size());
		for (int i = 0; i < blocks.size(); i++) { TreeBlock b = blocks.get(i); output.putInt("X" + i, b.relative.getX()); output.putInt("Y" + i, b.relative.getY()); output.putInt("Z" + i, b.relative.getZ()); output.putString("Block" + i, BuiltInRegistries.BLOCK.getKey(b.state.getBlock()).toString()); }
	}
	private record TreeBlock(BlockPos relative, BlockState state) {}
	public record VisualBlock(BlockPos relative, boolean leaf) {}
}
