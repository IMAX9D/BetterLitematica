package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.*;
import net.minecraft.client.render.chunk.Buffers;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Buffers.class)
public abstract class TerrainBufferCloseMixin implements TerrainPooledBuffer {
    @Unique private boolean betterlitematica$pooled;
    public void betterlitematica$pooled(boolean value){betterlitematica$pooled=value;}
    @Inject(method="close",at=@At("HEAD"),cancellable=true)
    private void betterlitematica$releaseRange(CallbackInfo ci){if(betterlitematica$pooled){TerrainRegions.INSTANCE.release((Buffers)(Object)this);ci.cancel();}}
}
