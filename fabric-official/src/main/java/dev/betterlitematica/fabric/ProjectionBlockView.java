package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.*;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import java.util.List;

/** Projection neighbors, real biome colors, and full-bright preview lighting. No world writes. */
final class ProjectionBlockView implements BlockAndTintGetter {
    static final class Pending extends RuntimeException {private static final long serialVersionUID=1L;Pending(){super(null,null,false,false);}}
    private final ClientLevel world;private final ProjectionScene scene;private final List<ProjectionScene.Part> parts;private final LayerRange layer;
    ProjectionBlockView(ClientLevel world,ProjectionScene scene,List<ProjectionScene.Part> parts,LayerRange layer){this.world=world;this.scene=scene;this.parts=parts;this.layer=layer;}
    @Override public BlockState getBlockState(BlockPos pos){var at=new dev.betterlitematica.core.Vec3i(pos.getX(),pos.getY(),pos.getZ());if(!layer.contains(at))return Blocks.AIR.defaultBlockState();var cell=scene.sampleDisplayed(parts,at);if(cell==null)return Blocks.AIR.defaultBlockState();if(cell.unknown())throw new Pending();return cell.renderer().resolve(cell.region().index(),cell.id());}
    @Override public FluidState getFluidState(BlockPos pos){return getBlockState(pos).getFluidState();}
    @Override public BlockEntity getBlockEntity(BlockPos pos){return null;}
    @Override public net.minecraft.world.level.CardinalLighting cardinalLighting(){return world.cardinalLighting();}
    @Override public LevelLightEngine getLightEngine(){return world.getLightEngine();}
    @Override public int getBlockTint(BlockPos pos,ColorResolver resolver){return world.getBlockTint(pos,resolver);}
    @Override public int getBrightness(LightLayer type,BlockPos pos){return 15;}
    @Override public int getRawBrightness(BlockPos pos,int ambient){return 15;}
    @Override public int getHeight(){return world.getHeight();}
    @Override public int getMinY(){return world.getMinY();}
}
