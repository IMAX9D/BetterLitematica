package dev.betterlitematica.fabric.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.betterlitematica.fabric.*;
import net.minecraft.client.renderer.chunk.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public abstract class TerrainDataPublishMixin {
    @Shadow @Final private SectionRenderDispatcher.RenderSection this$1;
    @Unique private TerrainBuildStamp betterlitematica$stamp;
    @Inject(method="<init>",at=@At("RETURN"))private void betterlitematica$capture(CallbackInfo ci){betterlitematica$stamp=((TerrainSectionOwner)this$1).betterlitematica$stamp(true);}
    @WrapOperation(method="doTask",at=@At(value="NEW",target="(Lnet/minecraft/client/renderer/chunk/TranslucencyPointOfView;Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;)Lnet/minecraft/client/renderer/chunk/CompiledSectionMesh;"))
    private CompiledSectionMesh betterlitematica$tag(TranslucencyPointOfView view,SectionCompiler.Results data,Operation<CompiledSectionMesh> original){
        var mesh=original.call(view,data);((TerrainMeshStamp)mesh).betterlitematica$stamp(betterlitematica$stamp);return mesh;
    }
    @WrapOperation(method="doTask",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;setSectionMesh(Lnet/minecraft/client/renderer/chunk/SectionMesh;)Lnet/minecraft/client/renderer/chunk/SectionMesh;"))
    private SectionMesh betterlitematica$publish(SectionRenderDispatcher.RenderSection section,SectionMesh mesh,Operation<SectionMesh> original){
        // The caller releases the returned old mesh; return the stale new mesh for disposal instead.
        if(!betterlitematica$stamp.current())return mesh;
        TerrainNativeBudget.changing();try{return original.call(section,mesh);}finally{TerrainNativeBudget.changed();}
    }
}
