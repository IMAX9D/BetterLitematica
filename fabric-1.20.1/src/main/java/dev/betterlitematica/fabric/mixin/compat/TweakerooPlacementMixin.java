package dev.betterlitematica.fabric.mixin.compat;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Pseudo
@Mixin(targets="fi.dy.masa.tweakeroo.config.FeatureToggle",remap=false)
abstract class TweakerooPlacementMixin {
    @Inject(method="getBooleanValue",at=@At("HEAD"),cancellable=true,require=0)
    private void betterlitematica$placement(CallbackInfoReturnable<Boolean> ci){
        if((Object)this instanceof Enum<?> feature)switch(feature.name()){
            case "TWEAK_ACCURATE_BLOCK_PLACEMENT","TWEAK_AFTER_CLICKER","TWEAK_FAKE_SNEAK_PLACEMENT",
                "TWEAK_FAST_BLOCK_PLACEMENT","TWEAK_FAST_LEFT_CLICK","TWEAK_FAST_RIGHT_CLICK",
                "TWEAK_FLEXIBLE_BLOCK_PLACEMENT","TWEAK_HOLD_USE","TWEAK_PERIODIC_USE",
                "TWEAK_PERIODIC_HOLD_USE","TWEAK_PLACEMENT_GRID","TWEAK_PLACEMENT_LIMIT",
                "TWEAK_PLACEMENT_RESTRICTION","TWEAK_PLACEMENT_REST_FIRST","TWEAK_PLACEMENT_REST_HAND",
                "TWEAK_PICK_BEFORE_PLACE","TWEAK_HAND_RESTOCK","TWEAK_TOOL_SWITCH" -> ci.setReturnValue(false);
            default -> { }
        }
    }
}
