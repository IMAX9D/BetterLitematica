package dev.betterlitematica.fabric;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
final class LightProjectionMarker {
    static ItemStack stack(BlockState state){
        var stack=new ItemStack(Items.LIGHT);
        stack.set(net.minecraft.core.component.DataComponents.BLOCK_STATE,net.minecraft.world.item.component.BlockItemStateProperties.EMPTY.with(BlockStateProperties.LEVEL,state));
        return stack;
    }
}
