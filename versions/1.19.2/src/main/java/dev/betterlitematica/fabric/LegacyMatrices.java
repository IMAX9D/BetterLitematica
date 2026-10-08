package dev.betterlitematica.fabric;

import org.lwjgl.system.MemoryStack;

/** Copy column-major matrices at the pre-JOML rendering boundary. */
final class LegacyMatrices {
    static org.joml.Matrix4f joml(net.minecraft.util.math.Matrix4f matrix){
        try(var stack=MemoryStack.stackPush()){var values=stack.mallocFloat(16);matrix.writeColumnMajor(values);values.rewind();return new org.joml.Matrix4f(values);}
    }
    static net.minecraft.util.math.Matrix4f minecraft(org.joml.Matrix4f matrix){
        try(var stack=MemoryStack.stackPush()){var values=stack.mallocFloat(16);matrix.get(values);values.rewind();var result=new net.minecraft.util.math.Matrix4f();result.readColumnMajor(values);return result;}
    }
    static org.joml.Quaternionf joml(net.minecraft.util.math.Quaternion value){return new org.joml.Quaternionf(value.getX(),value.getY(),value.getZ(),value.getW());}
}
