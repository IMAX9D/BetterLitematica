package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.AccuratePlacement;
import net.minecraft.item.*;
import net.minecraft.block.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(BlockItem.class)
public abstract class AccurateBlockItemMixin {
    @Redirect(method="place(Lnet/minecraft/item/ItemPlacementContext;)Lnet/minecraft/util/ActionResult;",at=@At(value="INVOKE",target="Lnet/minecraft/item/BlockItem;getPlacementState(Lnet/minecraft/item/ItemPlacementContext;)Lnet/minecraft/block/BlockState;"))
    private BlockState betterlitematica$state(BlockItem item,ItemPlacementContext context){BlockState base=item.getPlacementState(context);return AccuratePlacement.validatePlacement(item,context,base,AccuratePlacement.apply(context,base));}
}
