package dev.betterlitematica.fabric;
import net.minecraft.block.BlockState;
import net.minecraft.item.*;
import net.minecraft.state.property.Properties;
final class LightProjectionMarker {
    static ItemStack stack(BlockState state){
        var stack=new ItemStack(Items.LIGHT);
        stack.getOrCreateSubNbt("BlockStateTag").putString("level",Integer.toString(state.get(Properties.LEVEL_15)));
        return stack;
    }
}
