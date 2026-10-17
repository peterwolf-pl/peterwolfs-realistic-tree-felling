package com.peterwolf.realtreefelling.client;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;

/** Separate trunk and canopy layers avoid the old flat T-shaped placeholder. */
public final class FallingTreeModel extends EntityModel<FallingTreeRenderState> {
	public FallingTreeModel(ModelPart root) { super(root); }
	public static LayerDefinition trunkLayer() {
		MeshDefinition mesh = new MeshDefinition(); var root = mesh.getRoot();
		// Positive model Y is world-up in this entity renderer. y=0 is the cut/base pivot.
		root.addOrReplaceChild("trunk", CubeListBuilder.create().texOffs(0, 0).addBox(-8, 0, -8, 16, 64, 16), PartPose.ZERO);
		return LayerDefinition.create(mesh, 32, 32);
	}
	public static LayerDefinition canopyLayer() {
		MeshDefinition mesh = new MeshDefinition(); var root = mesh.getRoot();
		// A blocky crown attached to the top of the trunk, rather than a horizontal branch bar.
		root.addOrReplaceChild("canopy", CubeListBuilder.create().texOffs(0, 0)
			.addBox(-28, 48, -28, 56, 36, 56)
			.addBox(-20, 80, -20, 40, 20, 40), PartPose.ZERO);
		return LayerDefinition.create(mesh, 128, 128);
	}
}
