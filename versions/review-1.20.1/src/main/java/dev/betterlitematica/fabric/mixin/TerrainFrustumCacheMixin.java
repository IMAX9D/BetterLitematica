package dev.betterlitematica.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.betterlitematica.fabric.TerrainFrustumCache;
import dev.betterlitematica.fabric.TerrainExpandedBounds;
import dev.betterlitematica.fabric.TerrainRenderLists;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.util.math.Box;
import java.util.concurrent.atomic.AtomicReference;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public abstract class TerrainFrustumCacheMixin {
    @Shadow @Final private AtomicReference<?> renderableChunks;
    @Unique private final TerrainFrustumCache betterlitematica$viewCache=new TerrainFrustumCache();
    @Unique private boolean betterlitematica$cacheAllowed,betterlitematica$expanded;
    @Unique private int betterlitematica$sx,betterlitematica$sy,betterlitematica$sz;
    @Unique private Object betterlitematica$pendingGraph;

    @Inject(method="setupTerrain",at=@At("HEAD"))
    private void betterlitematica$camera(Camera camera,Frustum view,boolean forced,boolean spectator,CallbackInfo ci){
        var pos=camera.getBlockPos();betterlitematica$sx=pos.getX()>>4;betterlitematica$sy=pos.getY()>>4;betterlitematica$sz=pos.getZ()>>4;betterlitematica$cacheAllowed=!forced;
    }
    @Inject(method="applyFrustum",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$reuseView(Frustum view,CallbackInfo ci){
        betterlitematica$expanded=false;betterlitematica$pendingGraph=null;
        var source=renderableChunks.get();
        if(betterlitematica$cacheAllowed&&view.getClass()==Frustum.class&&source instanceof TerrainVisibilitySetAccessor access){
            var graph=access.betterlitematica$chunks();
            if(betterlitematica$viewCache.reuse(view,graph,betterlitematica$sx,betterlitematica$sy,betterlitematica$sz)){ci.cancel();return;}
            betterlitematica$expanded=betterlitematica$viewCache.capture(view,graph,betterlitematica$sx,betterlitematica$sy,betterlitematica$sz);
            if(betterlitematica$expanded)betterlitematica$pendingGraph=graph;
        }
        if(!betterlitematica$expanded)betterlitematica$viewCache.clear();
        ((TerrainRenderLists)this).betterlitematica$invalidateLists();
    }
    @WrapOperation(method="applyFrustum",at=@At(value="INVOKE",target="Lnet/minecraft/client/render/chunk/ChunkBuilder$BuiltChunk;getBoundingBox()Lnet/minecraft/util/math/Box;"))
    private Box betterlitematica$expandedBox(ChunkBuilder.BuiltChunk chunk,Operation<Box> original){
        Box box=original.call(chunk);
        return betterlitematica$expanded?((TerrainExpandedBounds)chunk).betterlitematica$expandedBounds(box):box;
    }
    @Inject(method="applyFrustum",at=@At("RETURN"))
    private void betterlitematica$rememberView(Frustum view,CallbackInfo ci){
        if(betterlitematica$pendingGraph!=null){
            var source=renderableChunks.get();
            if(source instanceof TerrainVisibilitySetAccessor access&&access.betterlitematica$chunks()==betterlitematica$pendingGraph)
                betterlitematica$viewCache.commit(view,access.betterlitematica$chunks(),betterlitematica$sx,betterlitematica$sy,betterlitematica$sz);
            else betterlitematica$viewCache.clear();
        }
        betterlitematica$pendingGraph=null;betterlitematica$expanded=false;
    }
    @Inject(method={"setWorld","reload()V"},at=@At("HEAD"))
    private void betterlitematica$clearView(CallbackInfo ci){betterlitematica$viewCache.clear();betterlitematica$pendingGraph=null;betterlitematica$expanded=false;betterlitematica$cacheAllowed=false;}
}
