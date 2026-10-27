package dev.betterlitematica.fabric.mixin;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.render.model.MultipartBlockStateModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(MultipartBlockStateModel.class)
public interface MultipartModelAccessor {
    @Accessor("models") java.util.List<BlockStateModel> betterlitematica$components();
}
