package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class TerrainSectionBufferMixin implements TerrainSectionOwner {
    @Shadow public abstract BlockPos getRenderOrigin();
    @Shadow public abstract void compileAsync(RenderSectionRegion region);
    @Shadow private void releaseSectionMesh(SectionMesh mesh){throw new AssertionError();}
    @Unique private volatile long betterlitematica$generation;
    public long betterlitematica$generation(){return betterlitematica$generation;}
    public TerrainBuildStamp betterlitematica$stamp(boolean rebuild){if(rebuild)betterlitematica$generation++;var p=getRenderOrigin();return new TerrainBuildStamp(this,betterlitematica$generation,p.getX(),p.getY(),p.getZ());}
    public void betterlitematica$published(long value){}
    @Inject(method="reset",at=@At("HEAD"))private void betterlitematica$reset(CallbackInfo ci){betterlitematica$generation++;TerrainNativeBudget.changing();}
    @Inject(method="reset",at=@At("RETURN"))private void betterlitematica$resetDone(CallbackInfo ci){TerrainNativeBudget.changed();}
    @Inject(method="compileSync",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$async(RenderSectionRegion region,CallbackInfo ci){compileAsync(region);ci.cancel();}
    @Inject(method="checkSectionMesh",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$discardOld(CompiledSectionMesh mesh,CallbackInfo ci){if(!((TerrainMeshStamp)mesh).betterlitematica$current()){releaseSectionMesh(mesh);ci.cancel();}}
    @WrapMethod(method="setSectionMesh")
    private SectionMesh betterlitematica$publication(SectionMesh mesh,Operation<SectionMesh> original){
        TerrainNativeBudget.changing();try{return original.call(mesh);}finally{TerrainNativeBudget.changed();}
    }
}
