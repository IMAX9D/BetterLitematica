package dev.betterlitematica.fabric;

import dev.betterlitematica.core.*;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.*;
import net.minecraft.world.*;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.chunk.light.LightingProvider;
import java.util.List;

/** Projection neighbors, real biome colors, and full-bright preview lighting. No world writes. */
final class ProjectionBlockView implements BlockRenderView {
    static final class Pending extends RuntimeException {private static final long serialVersionUID=1L;Pending(){super(null,null,false,false);}}
    private final ClientWorld world;private final ProjectionScene scene;private final List<ProjectionScene.Part> parts;private final LayerRange layer;
    ProjectionBlockView(ClientWorld world,ProjectionScene scene,List<ProjectionScene.Part> parts,LayerRange layer){this.world=world;this.scene=scene;this.parts=parts;this.layer=layer;}
    @Override public BlockState getBlockState(BlockPos pos){var at=new dev.betterlitematica.core.Vec3i(pos.getX(),pos.getY(),pos.getZ());if(!layer.contains(at))return Blocks.AIR.getDefaultState();var cell=scene.sampleDisplayed(parts,at);if(cell==null)return Blocks.AIR.getDefaultState();if(cell.unknown())throw new Pending();return cell.renderer().resolve(cell.region().index(),cell.id());}
    @Override public FluidState getFluidState(BlockPos pos){return getBlockState(pos).getFluidState();}
    @Override public BlockEntity getBlockEntity(BlockPos pos){return null;}
    @Override public float getBrightness(Direction direction,boolean shaded){return world.getBrightness(direction,shaded);}
    @Override public LightingProvider getLightingProvider(){return world.getLightingProvider();}
    @Override public int getColor(BlockPos pos,ColorResolver resolver){return world.getColor(pos,resolver);}
    @Override public int getLightLevel(LightType type,BlockPos pos){return 15;}
    @Override public int getBaseLightLevel(BlockPos pos,int ambient){return 15;}
    @Override public int getHeight(){return world.getHeight();}
    @Override public int getBottomY(){return world.getBottomY();}
}
