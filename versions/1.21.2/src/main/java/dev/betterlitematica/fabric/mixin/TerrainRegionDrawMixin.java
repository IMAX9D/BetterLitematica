package dev.betterlitematica.fabric.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.betterlitematica.fabric.TerrainRegions;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep vanilla layer setup, fog, shader binding, fluid sort scheduling and layer teardown intact. */
@Mixin(WorldRenderer.class)
public abstract class TerrainRegionDrawMixin {
    @Unique private ObjectArrayList<?> betterlitematica$pass;
    @Inject(method="render",at=@At("HEAD"))
    private void betterlitematica$beginFrame(CallbackInfo ci){TerrainRegions.INSTANCE.beginFrame();}
    @Inject(method="render",at=@At("RETURN"))
    private void betterlitematica$endFrame(CallbackInfo ci){TerrainRegions.INSTANCE.endFrame();}
    @Inject(method={"setWorld","reload()V","close"},at=@At("HEAD"))
    private void betterlitematica$clear(CallbackInfo ci){betterlitematica$pass=null;TerrainRegions.INSTANCE.clear();}
    @WrapOperation(method="renderLayer",at=@At(value="INVOKE",target="Lit/unimi/dsi/fastutil/objects/ObjectArrayList;listIterator(I)Lit/unimi/dsi/fastutil/objects/ObjectListIterator;"))
    private ObjectListIterator<?> betterlitematica$collect(ObjectArrayList<?> values,int index,Operation<ObjectListIterator<?>> original){
        var shader=RenderSystem.getShader();
        betterlitematica$pass=values;
        return betterlitematica$pass==null?original.call(values,index):ObjectLists.emptyList().listIterator();
    }
    @WrapOperation(method="renderLayer",at=@At(value="INVOKE",target="Lnet/minecraft/client/gl/ShaderProgram;bind()V"))
    private void betterlitematica$draw(ShaderProgram shader,Operation<Void> original,@Local(argsOnly=true)RenderLayer layer,
                                     @Local(argsOnly=true,ordinal=0)double x,@Local(argsOnly=true,ordinal=1)double y,@Local(argsOnly=true,ordinal=2)double z){
        original.call(shader);
        if(betterlitematica$pass!=null)TerrainRegions.INSTANCE.draw(betterlitematica$pass,layer,shader,x,y,z);
        betterlitematica$pass=null;
    }
}
