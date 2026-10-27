package dev.betterlitematica.fabric.mixin;
import dev.betterlitematica.fabric.TerrainView;
import net.minecraft.client.render.Frustum;
import net.minecraft.util.math.Matrix4f;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Frustum.class)
public abstract class TerrainFrustumAccessor implements TerrainView {
    @Shadow private double x,y,z;
    @Unique private org.joml.Matrix4f betterlitematica$matrix;
    @Unique private org.joml.FrustumIntersection betterlitematica$intersection;
    @Inject(method="<init>(Lnet/minecraft/util/math/Matrix4f;Lnet/minecraft/util/math/Matrix4f;)V",at=@At("RETURN"))
    private void betterlitematica$capture(Matrix4f view,Matrix4f projection,CallbackInfo ci){
        try(var stack=org.lwjgl.system.MemoryStack.stackPush()){
            var values=stack.mallocFloat(16);projection.writeColumnMajor(values);values.rewind();
            betterlitematica$matrix=new org.joml.Matrix4f(values);values.clear();view.writeColumnMajor(values);values.rewind();
            betterlitematica$matrix.mul(new org.joml.Matrix4f(values));
            betterlitematica$intersection=new org.joml.FrustumIntersection(betterlitematica$matrix);
        }
    }
    @Inject(method="<init>(Lnet/minecraft/client/render/Frustum;)V",at=@At("RETURN"))
    private void betterlitematica$copy(Frustum source,CallbackInfo ci){
        betterlitematica$matrix=new org.joml.Matrix4f(((TerrainView)source).betterlitematica$matrix());
        betterlitematica$intersection=new org.joml.FrustumIntersection(betterlitematica$matrix);
    }
    public org.joml.Matrix4f betterlitematica$matrix(){return betterlitematica$matrix;}
    public org.joml.FrustumIntersection betterlitematica$intersection(){return betterlitematica$intersection;}
    public double betterlitematica$x(){return x;} public double betterlitematica$y(){return y;} public double betterlitematica$z(){return z;}
}
