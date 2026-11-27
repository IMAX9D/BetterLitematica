package dev.betterlitematica.fabric.mixin;

import dev.betterlitematica.fabric.TerrainColumnRevision;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.world.ClientChunkManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkManager.class)
abstract class TerrainColumnUpdateMixin implements TerrainColumnRevision {
    @Unique private final AtomicLong betterlitematica$columnChanges=new AtomicLong();

    @Override public long betterlitematica$columnRevision(){return betterlitematica$columnChanges.get();}

    @Inject(method="loadChunkFromPacket",at=@At("RETURN"))
    private void betterlitematica$columnAvailable(CallbackInfoReturnable<?> result){
        if(result.getReturnValue()!=null)betterlitematica$columnChanges.incrementAndGet();
    }

    @Inject(method={"unload","setChunkMapCenter","updateLoadDistance"},at=@At("RETURN"))
    private void betterlitematica$columnsChanged(CallbackInfo callback){
        betterlitematica$columnChanges.incrementAndGet();
    }
}
