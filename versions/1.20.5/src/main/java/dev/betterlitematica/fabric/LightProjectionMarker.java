package dev.betterlitematica.fabric;
import net.minecraft.block.BlockState;
import net.minecraft.item.*;
import net.minecraft.state.property.Properties;
final class LightProjectionMarker {
    static ItemStack stack(BlockState state){
        var stack=new ItemStack(Items.LIGHT);
        stack.set(net.minecraft.component.DataComponentTypes.BLOCK_STATE,net.minecraft.component.type.BlockStateComponent.DEFAULT.with(Properties.LEVEL_15,state));
        return stack;
    }
}
