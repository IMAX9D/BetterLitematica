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
            RenderSystem.getSequentialBuffer(parameters.mode()).getIndexBuffer(parameters.indexCount());
            return new ProjectionGpu(RenderSystem.getDevice().createBuffer(()->"BetterLitematica projection",GpuBuffer.USAGE_VERTEX,source.getBuffer()),parameters.mode(),parameters.indexCount());
        }
    }
    static RenderPass pass(Framebuffer target,RenderPipeline pipeline){
        var pass=RenderSystem.getDevice().createCommandEncoder().createRenderPass(()->"BetterLitematica projection",target.getColorAttachmentView(),OptionalInt.empty(),target.getDepthAttachmentView(),OptionalDouble.empty());
        pass.setPipeline(pipeline);RenderSystem.bindDefaultUniforms(pass);return pass;
    }
    static Surface surface(org.joml.Matrix4f view,org.joml.Matrix4f projection,float opacity,net.minecraft.util.Identifier texture,boolean intensity,boolean wrong){
        return new Surface(ProjectionUniforms.transforms(new org.joml.Matrix4f(RenderSystem.getModelViewMatrix()).mul(view),1,1,1,opacity,1),ProjectionUniforms.projection(projection),ProjectionUniforms.modes(intensity?1:0,wrong?1:0,false),net.minecraft.client.MinecraftClient.getInstance().getTextureManager().getTexture(texture).getGlTextureView());
    }
    record Surface(GpuBufferSlice transforms,GpuBufferSlice projection,GpuBuffer modes,com.mojang.blaze3d.textures.GpuTextureView texture){
        void bind(RenderPass pass){pass.setUniform("DynamicTransforms",transforms);pass.setUniform("Projection",projection);pass.setUniform("ProjectionModes",modes);pass.bindSampler("Sampler0",texture);}
    }
    void bind(RenderPass pass){
        var sequential=RenderSystem.getSequentialBuffer(mode);
        pass.setVertexBuffer(0,vertices);pass.setIndexBuffer(sequential.getIndexBuffer(indices),sequential.getIndexType());
    }
    void draw(RenderPass pass){bind(pass);pass.drawIndexed(0,0,indices,1);}
    boolean draw(RenderPass pass,QuadVisibility.Part geometry,QuadVisibility.Mask completed,QuadVisibility.Mask wrong,boolean red){
        int ranges=geometry.rangeCount(completed,wrong,red);if(ranges==0)return false;bind(pass);
        for(int i=0;i<ranges;i++)pass.drawIndexed(0,geometry.firstQuad(i)*6,geometry.quadCount(i)*6,1);
        return true;
    }
    @Override public void close(){vertices.close();}
}
