package dev.betterlitematica.fabric.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.betterlitematica.fabric.*;
import net.minecraft.TracingExecutor;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import java.util.concurrent.ExecutorService;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(SectionRenderDispatcher.class)
public abstract class TerrainUploadQueueMixin {
    @Unique private ExecutorService betterlitematica$workers;
    @ModifyVariable(method="<init>",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/RenderBuffers;fixedBufferPack()Lnet/minecraft/client/renderer/SectionBufferBuilderPack;"),argsOnly=true)
    private TracingExecutor betterlitematica$workers(TracingExecutor original){betterlitematica$workers=TerrainWorkers.create();return new TracingExecutor(betterlitematica$workers);}
    @WrapMethod(method="uploadGlobalGeomBuffersToGPU")
    private void betterlitematica$upload(Operation<Void> original){TerrainNativeBudget.begin();try{original.call();}finally{TerrainNativeBudget.end();}}
    @Inject(method="dispose",at=@At("RETURN"))private void betterlitematica$stop(CallbackInfo ci){if(betterlitematica$workers!=null)betterlitematica$workers.shutdown();}
}
