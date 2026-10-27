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
    @Shadow private ChunkBuilder chunkBuilder;
    @Shadow @Final private MinecraftClient client;
    @Shadow @Final private ObjectArrayList<WorldRenderer.ChunkInfo> chunkInfos;
    @Shadow @Final private BlockingQueue<ChunkBuilder.BuiltChunk> builtChunks;
    @Shadow private int viewDistance,cameraChunkX,cameraChunkY,cameraChunkZ;
    @Shadow private double lastCameraChunkUpdateX,lastCameraChunkUpdateY,lastCameraChunkUpdateZ;
    @Shadow public abstract void reload();
    @Unique private final TerrainTraversal betterlitematica$traversal=new TerrainTraversal();
    @Inject(method="setupTerrain",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$traverse(Camera camera,Frustum view,boolean forced,boolean spectator,CallbackInfo ci){
        if(client.options.getViewDistance()!=viewDistance)reload();
        if(world==null||chunks==null||chunkBuilder==null)return;
        ci.cancel();
        net.minecraft.entity.Entity.setRenderDistanceMultiplier(net.minecraft.util.math.MathHelper.clamp(client.options.getViewDistance()/8.0,1.0,2.5)*client.options.entityDistanceScaling);
        var player=client.player;
        double x=player==null?camera.getPos().x:player.getX(),y=player==null?camera.getPos().y:player.getY(),z=player==null?camera.getPos().z:player.getZ();
        int sx=(int)Math.floor(x/16),sy=(int)Math.floor(y/16),sz=(int)Math.floor(z/16);
        double dx=x-lastCameraChunkUpdateX,dy=y-lastCameraChunkUpdateY,dz=z-lastCameraChunkUpdateZ;
        if(sx!=cameraChunkX||sy!=cameraChunkY||sz!=cameraChunkZ||dx*dx+dy*dy+dz*dz>16){
            lastCameraChunkUpdateX=x;lastCameraChunkUpdateY=y;lastCameraChunkUpdateZ=z;
            cameraChunkX=sx;cameraChunkY=sy;cameraChunkZ=sz;chunks.updateCameraPosition(x,z);
        }
        chunkBuilder.setCameraPosition(camera.getPos());
        // Completed data is read directly; no old background-graph mailbox accumulates references.
        builtChunks.clear();
        // Captured-frustum debug mode freezes visibility, not the storage/camera lifecycle.
        if(forced)return;
        boolean culling=client.chunkCullingEnabled;
        if(spectator&&world.getBlockState(camera.getBlockPos()).isOpaqueFullCube(world,camera.getBlockPos()))culling=false;
        var conservative=view.getClass()==Frustum.class?new Frustum(view).method_38557(8):view;
        betterlitematica$traversal.collect(chunks,world,camera.getPos(),conservative,viewDistance,culling,chunkInfos);
        ((TerrainRenderLists)this).betterlitematica$invalidateLists();
    }
    @Inject(method={"setWorld","reload()V","close"},at=@At("HEAD"))
    private void betterlitematica$clearGraph(CallbackInfo ci){betterlitematica$traversal.clear();}
}
