package dev.betterlitematica.fabric.mixin;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.WeightedBakedModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(WeightedBakedModel.class)
public interface WeightedModelAccessor {
    @Accessor("models") net.minecraft.util.collection.DataPool<BakedModel> betterlitematica$models();
}
