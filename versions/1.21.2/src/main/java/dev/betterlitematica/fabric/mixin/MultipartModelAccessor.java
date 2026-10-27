package dev.betterlitematica.fabric.mixin;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.MultipartBakedModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.List;
@Mixin(MultipartBakedModel.class)
public interface MultipartModelAccessor {
    @Accessor("components") List<MultipartBakedModel.class_10204> betterlitematica$components();
}
