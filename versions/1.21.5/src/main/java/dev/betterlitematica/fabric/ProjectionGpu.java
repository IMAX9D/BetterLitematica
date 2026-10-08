package dev.betterlitematica.fabric;

import com.mojang.blaze3d.buffers.*;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.betterlitematica.core.QuadVisibility;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.BuiltBuffer;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;

/** A bounded vertex allocation; sequential indices remain owned and shared by Minecraft. */
final class ProjectionGpu implements AutoCloseable {
    private final GpuBuffer vertices;
    private final VertexFormat.DrawMode mode;
    private final int indices;
    private ProjectionGpu(GpuBuffer vertices,VertexFormat.DrawMode mode,int indices){this.vertices=vertices;this.mode=mode;this.indices=indices;}
    static ProjectionGpu upload(BuiltBuffer source){
        try(source){var parameters=source.getDrawParameters();
            // Shared index growth writes GPU memory, which must happen before any render pass opens.
            RenderSystem.getSequentialBuffer(parameters.mode()).getIndexBuffer(parameters.indexCount());
            return new ProjectionGpu(RenderSystem.getDevice().createBuffer(()->"BetterLitematica projection",BufferType.VERTICES,BufferUsage.STATIC_WRITE,source.getBuffer()),parameters.mode(),parameters.indexCount());
        }
    }
    static RenderPass pass(Framebuffer target,RenderPipeline pipeline){
        var pass=RenderSystem.getDevice().createCommandEncoder().createRenderPass(target.getColorAttachment(),OptionalInt.empty(),target.getDepthAttachment(),OptionalDouble.empty());
        pass.setPipeline(pipeline);return pass;
    }
    record Surface(Matrix4f view,Matrix4f projection,float opacity,com.mojang.blaze3d.textures.GpuTexture texture,boolean intensity,boolean wrong){
        void bind(RenderPass pass){
            pass.setUniform("ProjectionView",view);pass.setUniform("ProjectionProjection",projection);pass.setUniform("ProjectionColor",1f,1f,1f,opacity);
            pass.setUniform("TextureMode",intensity?1:0);pass.setUniform("SurfaceTint",wrong?1:0);pass.bindSampler("Sampler0",texture);
        }
    }
    static Surface surface(Matrix4f view,Matrix4f projection,float opacity,Identifier texture,boolean intensity,boolean wrong){
        return new Surface(new Matrix4f(RenderSystem.getModelViewMatrix()).mul(view),new Matrix4f(projection),opacity,MinecraftClient.getInstance().getTextureManager().getTexture(texture).getGlTexture(),intensity,wrong);
    }
    void bind(RenderPass pass){
        var sequential=RenderSystem.getSequentialBuffer(mode);
        pass.setVertexBuffer(0,vertices);pass.setIndexBuffer(sequential.getIndexBuffer(indices),sequential.getIndexType());
    }
    void draw(RenderPass pass){bind(pass);pass.drawIndexed(0,indices);}
    boolean draw(RenderPass pass,QuadVisibility.Part geometry,QuadVisibility.Mask completed,QuadVisibility.Mask wrong,boolean red){
        int ranges=geometry.rangeCount(completed,wrong,red);if(ranges==0)return false;bind(pass);
        for(int i=0;i<ranges;i++)pass.drawIndexed(geometry.firstQuad(i)*6,geometry.quadCount(i)*6);
        return true;
    }
    @Override public void close(){vertices.close();}
}
