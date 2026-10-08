package dev.betterlitematica.fabric;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.*;
import net.minecraft.client.render.*;
import net.minecraft.client.util.BufferAllocator;
import org.joml.Matrix4f;

/** All placements share one nearest-surface image; opacity is applied exactly once per pixel. */
final class ProjectionComposite implements AutoCloseable {
    private Framebuffer target;
    private ProjectionGpu quad;
    private static Framebuffer active;
    static Framebuffer target(MinecraftClient client){return active==null?client.getFramebuffer():active;}
    void begin(MinecraftClient client){
        Framebuffer main=client.getFramebuffer();
        if(target==null||target.textureWidth!=main.textureWidth||target.textureHeight!=main.textureHeight){
            close();target=new SimpleFramebuffer("BetterLitematica nearest surface",main.textureWidth,main.textureHeight,true);
        }
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorAttachment(),0,target.getDepthAttachment(),1);
        target.copyDepthFrom(main);active=target;
    }
    void finish(MinecraftClient client,float opacity){
        active=null;
        if(quad==null)try(var allocator=new BufferAllocator(256)){
            var b=new BufferBuilder(allocator,VertexFormat.DrawMode.QUADS,VertexFormats.POSITION_TEXTURE_COLOR);
            b.vertex(-1,-1,0).texture(0,0).color(255,255,255,255);
            b.vertex(1,-1,0).texture(1,0).color(255,255,255,255);
            b.vertex(1,1,0).texture(1,1).color(255,255,255,255);
            b.vertex(-1,1,0).texture(0,1).color(255,255,255,255);
            quad=ProjectionGpu.upload(b.end());
        }
        var transforms=ProjectionUniforms.transforms(new Matrix4f(),1,1,1,opacity,1);var projection=ProjectionUniforms.identityProjection();
        try(var pass=ProjectionGpu.pass(client.getFramebuffer(),ProjectionShaders.COMPOSITE)){
            pass.setUniform("DynamicTransforms",transforms);pass.setUniform("Projection",projection);
            pass.bindSampler("Sampler0",target.getColorAttachmentView());quad.draw(pass);
        }
    }
    @Override public void close(){if(active==target)active=null;if(target!=null){target.delete();target=null;}if(quad!=null){quad.close();quad=null;}}
}
