package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.*;
import net.minecraft.client.render.chunk.*;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.math.BlockPos;
import java.util.Map;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ChunkBuilder.BuiltChunk.class)
public abstract class TerrainSectionBufferMixin implements TerrainSectionOwner,TerrainUploadSource {
    @Shadow @Final private ChunkBuilder field_20833;
    @Shadow public abstract BlockPos getOrigin();
    @Shadow public abstract void scheduleRebuild(ChunkRendererRegionBuilder regions);
    @Unique private volatile long betterlitematica$generation,betterlitematica$published=Long.MIN_VALUE;
    public long betterlitematica$generation(){return betterlitematica$generation;}
    public TerrainBuildStamp betterlitematica$stamp(boolean rebuild){if(rebuild)betterlitematica$generation++;var p=getOrigin();return new TerrainBuildStamp(this,rebuild?betterlitematica$generation:betterlitematica$published,p.getX(),p.getY(),p.getZ());}
    public void betterlitematica$published(long value){betterlitematica$published=value;}
    public TerrainUploadQueue betterlitematica$uploads(){return ((TerrainUploadOwner)field_20833).betterlitematica$uploads();}
    @Inject(method="clear",at=@At("HEAD")) private void betterlitematica$clear(CallbackInfo ci){betterlitematica$generation++;betterlitematica$published=Long.MIN_VALUE;}
    @Inject(method="rebuild",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$async(ChunkRendererRegionBuilder regions,CallbackInfo ci){scheduleRebuild(regions);ci.cancel();}
}
