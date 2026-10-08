package dev.betterlitematica.fabric.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.betterlitematica.fabric.EntityDepthPass;
import net.minecraft.client.renderer.LevelRenderer;
import java.util.*;
import java.util.function.Supplier;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(LevelRenderer.class)
public abstract class EntityDepthPassMixin {
    @WrapOperation(method="lambda$addMainPass$0",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/commands/RenderPass;"))
    private RenderPass betterlitematica$depthSnapshots(CommandEncoder encoder,Supplier<String> name,GpuTextureView color,Optional<Vector4fc> clear,GpuTextureView depth,OptionalDouble clearDepth,Operation<RenderPass> original){return EntityDepthPass.wrap(encoder,name,color,depth,original.call(encoder,name,color,clear,depth,clearDepth));}
}
