package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.TerrainTraversal;
import dev.betterlitematica.fabric.TerrainRenderLists;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.world.ClientWorld;
import java.util.concurrent.BlockingQueue;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Own visibility as a unit; the existing workers and draw passes consume compact output. */
@Mixin(WorldRenderer.class)
public abstract class TerrainVisibilityGraphMixin {
    @Shadow private BuiltChunkStorage chunks;
    @Shadow private ClientWorld world;
    @Shadow @Final private ObjectArrayList<ChunkBuilder.BuiltChunk> nearbyChunks;
    @Shadow private ChunkBuilder chunkBuilder;
    @Shadow @Final private MinecraftClient client;
    @Shadow @Final private ObjectArrayList<ChunkBuilder.BuiltChunk> builtChunks;

    @Shadow private int viewDistance,cameraChunkX,cameraChunkY,cameraChunkZ;

    @Shadow public abstract void reload();
    @Unique private final TerrainTraversal betterlitematica$traversal=new TerrainTraversal();
    @Inject(method="setupTerrain",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$traverse(Camera camera,Frustum view,boolean forced,boolean spectator,CallbackInfo ci){
        if(client.options.getClampedViewDistance()!=viewDistance)reload();
        if(world==null||chunks==null||chunkBuilder==null)return;
        ci.cancel();
        var player=client.player;
        double x=camera.getPos().x,y=camera.getPos().y,z=camera.getPos().z;
        int sx=(int)Math.floor(x/16),sy=(int)Math.floor(y/16),sz=(int)Math.floor(z/16);
        if(sx!=cameraChunkX||sy!=cameraChunkY||sz!=cameraChunkZ){

            cameraChunkX=sx;cameraChunkY=sy;cameraChunkZ=sz;chunks.updateCameraPosition(net.minecraft.util.math.ChunkSectionPos.from(camera.getPos()));
        }
        chunkBuilder.setCameraPosition(camera.getPos());
        // Completed data is read directly; no old background-graph mailbox accumulates references.

        // Captured-frustum debug mode freezes visibility, not the storage/camera lifecycle.
        if(forced)return;
        boolean culling=client.chunkCullingEnabled;
        if(spectator&&world.getBlockState(camera.getBlockPos()).isOpaqueFullCube())culling=false;
        var conservative=view.getClass()==Frustum.class?new Frustum(view).coverBoxAroundSetPosition(8):view;
        betterlitematica$traversal.collect(chunks,world,camera.getPos(),conservative,viewDistance,culling,builtChunks);
        nearbyChunks.clear();
        for(var section:builtChunks){var p=section.getOrigin();
            if(Math.abs(p.getX()+8-x)<=48&&Math.abs(p.getY()+8-y)<=48&&Math.abs(p.getZ()+8-z)<=48)nearbyChunks.add(section);
        }
        ((TerrainRenderLists)this).betterlitematica$invalidateLists();
    }
    @Inject(method="addBuiltChunk",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$directPublication(ChunkBuilder.BuiltChunk section,CallbackInfo ci){ci.cancel();}
    @Inject(method={"setWorld","reload()V","close"},at=@At("HEAD"))
    private void betterlitematica$clearGraph(CallbackInfo ci){betterlitematica$traversal.clear();}
}
