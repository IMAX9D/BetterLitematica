package dev.betterlitematica.fabric;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.*;
import net.minecraft.client.render.*;
import org.joml.Matrix4f;

/** All placements share one nearest-surface image; opacity is applied exactly once per pixel. */
final class ProjectionComposite implements AutoCloseable {
    private Framebuffer target;
    private VertexBuffer quad;private int previousTexture;
    void begin(MinecraftClient client){
        previousTexture=RenderSystem.getShaderTexture(0);Framebuffer main=client.getFramebuffer();
        if(target==null||target.textureWidth!=main.textureWidth||target.textureHeight!=main.textureHeight){
            close();target=new SimpleFramebuffer(main.textureWidth,main.textureHeight,true,MinecraftClient.IS_SYSTEM_MAC);
            target.setClearColor(0,0,0,0);
        }
        target.clear(MinecraftClient.IS_SYSTEM_MAC);
        target.copyDepthFrom(main);
        target.beginWrite(false);
    }
    void finish(MinecraftClient client,float opacity){
        client.getFramebuffer().beginWrite(false);
        Shader old=RenderSystem.getShader();float[] color=RenderSystem.getShaderColor().clone();
        try{
            if(quad==null){
                BufferBuilder b=new BufferBuilder(256);b.begin(VertexFormat.DrawMode.QUADS,VertexFormats.POSITION_TEXTURE_COLOR);
                b.vertex(-1,-1,0).texture(0,0).color(255,255,255,255).next();
                b.vertex(1,-1,0).texture(1,0).color(255,255,255,255).next();
                b.vertex(1,1,0).texture(1,1).color(255,255,255,255).next();
                b.vertex(-1,1,0).texture(0,1).color(255,255,255,255).next();
                quad=new VertexBuffer();quad.bind();b.end();quad.upload(b);VertexBuffer.unbind();
            }
            RenderSystem.disableDepthTest();RenderSystem.depthMask(false);RenderSystem.disableCull();
            RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1,1,1,opacity);RenderSystem.setShaderTexture(0,target.getColorAttachment());
            RenderSystem.setShader(ProjectionShaders::composite);
            quad.bind();quad.setShader(LegacyMatrices.minecraft(new Matrix4f()),LegacyMatrices.minecraft(new Matrix4f()),ProjectionShaders.composite());
        }finally{
            VertexBuffer.unbind();RenderSystem.setShaderTexture(0,previousTexture);RenderSystem.depthMask(true);RenderSystem.enableDepthTest();RenderSystem.enableCull();RenderSystem.disableBlend();
            RenderSystem.setShaderColor(color[0],color[1],color[2],color[3]);if(old!=null)RenderSystem.setShader(()->old);
        }
    }
    @Override public void close(){if(target!=null){target.delete();target=null;}if(quad!=null){quad.close();quad=null;}}
}
