package dev.betterlitematica.fabric.mixin;
import net.minecraft.client.render.ChunkRenderingDataPreparer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ChunkRenderingDataPreparer.class)
public abstract class TerrainGraphEventsMixin {
    @Inject(method={"method_52819","method_52827"},at=@At("HEAD"),cancellable=true)
    private void betterlitematica$noUnusedMailbox(CallbackInfo ci){ci.cancel();}
}
