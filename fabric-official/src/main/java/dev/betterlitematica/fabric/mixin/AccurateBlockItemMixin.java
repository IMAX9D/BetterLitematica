package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.AccuratePlacement;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(BlockItem.class)
public abstract class AccurateBlockItemMixin {
    @Redirect(method="place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",at=@At(value="INVOKE",target="Lnet/minecraft/world/item/BlockItem;getPlacementState(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState betterlitematica$state(BlockItem item,BlockPlaceContext context){BlockState base=item.getPlacementState(context);return AccuratePlacement.validatePlacement(item,context,base,AccuratePlacement.apply(context,base));}
}
