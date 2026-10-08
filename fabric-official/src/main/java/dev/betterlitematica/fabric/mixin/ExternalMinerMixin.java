package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.ExternalMiner;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Pseudo
@Mixin(targets={"com.github.bunnyi116.bedrockminer.task.TaskManager","me.z7087.blockminer.task.TaskManager"},remap=false)
abstract class ExternalMinerMixin {
    @Inject(method="tick",at=@At("HEAD"),require=0,remap=false,cancellable=true)
    private void betterlitematica$enter(CallbackInfo ci){if(dev.betterlitematica.fabric.BetterLitematicaClient.nativeMiningBusy()||!ExternalMiner.entering(this))ci.cancel();}
    @Inject(method="tick",at=@At("RETURN"),require=0,remap=false)
    private void betterlitematica$leave(CallbackInfo ci){ExternalMiner.leaving(this);}
    @Inject(method="addBlockTask",at=@At("HEAD"),require=0,remap=false,cancellable=true)
    private void betterlitematica$nativeTarget(CallbackInfo ci){if(dev.betterlitematica.fabric.BetterLitematicaClient.nativeMiningBusy())ci.cancel();}
    @Inject(method="handleAttackBlock",at=@At("HEAD"),require=0,remap=false,cancellable=true)
    private void betterlitematica$nativeAttack(CallbackInfoReturnable<Boolean> ci){if(dev.betterlitematica.fabric.BetterLitematicaClient.nativeMiningBusy())ci.setReturnValue(false);}
}
