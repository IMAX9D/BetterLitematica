package dev.betterlitematica.fabric;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.*;
import org.lwjgl.system.MemoryStack;

/** Tiny immutable mode blocks and Minecraft's frame-managed transform uniform ring. */
final class ProjectionUniforms {
    private static final GpuBuffer[] modes=new GpuBuffer[8];
    private static GpuBuffer identityProjection;
    private static net.minecraft.client.gl.DynamicUniformStorage<Projection> projections;
    private ProjectionUniforms(){}
    static GpuBufferSlice transforms(Matrix4f modelView,float red,float green,float blue,float alpha,float lineWidth){
        return RenderSystem.getDynamicUniforms().write(modelView,new Vector4f(red,green,blue,alpha),new Vector3f(),new Matrix4f());
    }
    static void beginFrame(){if(projections!=null)projections.clear();}
    static GpuBufferSlice projection(Matrix4f projection){
        if(projections==null)projections=new net.minecraft.client.gl.DynamicUniformStorage<>("BetterLitematica projection matrices",64,16);
        return projections.write(new Projection(new Matrix4f(projection)));
    }
    private record Projection(Matrix4f matrix) implements net.minecraft.client.gl.DynamicUniformStorage.Uploadable {
        public void write(java.nio.ByteBuffer buffer){matrix.get(buffer);}
    }
    static GpuBuffer modes(int texture,int tint,boolean mask){
        int key=(texture==0?0:1)|(tint==0?0:2)|(mask?4:0);
        if(modes[key]==null)try(var stack=MemoryStack.stackPush()){
            var data=stack.malloc(16);data.putInt(texture).putInt(tint).putInt(mask?1:0).putInt(0).flip();
            modes[key]=RenderSystem.getDevice().createBuffer(()->"BetterLitematica projection modes",GpuBuffer.USAGE_UNIFORM,data);
        }
        return modes[key];
    }
    static GpuBuffer identityProjection(){
        if(identityProjection==null)try(var stack=MemoryStack.stackPush()){
            var data=stack.malloc(64);new Matrix4f().get(0,data);
            identityProjection=RenderSystem.getDevice().createBuffer(()->"BetterLitematica composite projection",GpuBuffer.USAGE_UNIFORM,data);
        }
        return identityProjection;
    }
    static void close(){for(int i=0;i<modes.length;i++)if(modes[i]!=null){modes[i].close();modes[i]=null;}if(identityProjection!=null){identityProjection.close();identityProjection=null;}if(projections!=null){projections.close();projections=null;}}
}
