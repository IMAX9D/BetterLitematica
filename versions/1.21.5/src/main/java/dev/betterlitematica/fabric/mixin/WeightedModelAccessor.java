package dev.betterlitematica.fabric.mixin;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.render.model.WeightedBlockStateModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(WeightedBlockStateModel.class)
public interface WeightedModelAccessor {
    @Accessor("models") net.minecraft.util.collection.Pool<BlockStateModel> betterlitematica$models();
}
