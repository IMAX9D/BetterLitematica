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
        return new Surface(ProjectionUniforms.transforms(new org.joml.Matrix4f(RenderSystem.getModelViewMatrix()).mul(view),1,1,1,opacity,1),ProjectionUniforms.projection(projection),ProjectionUniforms.clip(),ProjectionUniforms.modes(intensity?1:0,wrong?1:0,false),net.minecraft.client.MinecraftClient.getInstance().getTextureManager().getTexture(texture).getGlTextureView());
    }
    record Surface(GpuBufferSlice transforms,GpuBufferSlice projection,GpuBufferSlice clip,GpuBuffer modes,com.mojang.blaze3d.textures.GpuTextureView texture){
        void bind(RenderPass pass){pass.setUniform("DynamicTransforms",transforms);pass.setUniform("Projection",projection);pass.setUniform("ProjectionClip",clip);pass.setUniform("ProjectionModes",modes);pass.bindSampler("Sampler0",texture);}
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

    static ProjectionGpu page(int capacity){
        var mode=VertexFormat.DrawMode.QUADS;int indices=capacity/4*6;
        RenderSystem.getSequentialBuffer(mode).getIndexBuffer(indices);
        var buffer=RenderSystem.getDevice().createBuffer(()->"BetterLitematica projection page",GpuBuffer.USAGE_VERTEX|GpuBuffer.USAGE_COPY_DST,capacity*24);
        return new ProjectionGpu(buffer,mode,indices);
    }
    void write(int first,java.nio.ByteBuffer data){RenderSystem.getDevice().createCommandEncoder().writeToBuffer(vertices.slice(first*24,data.remaining()),data);}
    private final PageDraw[] pageDraws={new PageDraw(),new PageDraw()};
    private static final class PageDraw {int[] ranges;GpuBuffer indices;java.util.List<RenderPass.RenderObject<Void>> commands;}
    void drawPage(Matrix4f view,Matrix4f projection,float opacity,boolean red,int[] ranges){
        var surface=surface(view,projection,opacity,net.minecraft.client.texture.SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE,false,red);
        var sequential=RenderSystem.getSequentialBuffer(mode);var index=sequential.getIndexBuffer(indices);var type=sequential.getIndexType();
        var draw=pageDraws[red?1:0];
        if(draw.ranges!=ranges||draw.indices!=index){
            var commands=new java.util.ArrayList<RenderPass.RenderObject<Void>>(ranges.length/2);
            for(int i=0;i<ranges.length;i+=2)commands.add(new RenderPass.RenderObject<>(0,vertices,index,type,ranges[i],ranges[i+1]));
            draw.ranges=ranges;draw.indices=index;draw.commands=commands;
        }
        try(var pass=pass(ProjectionComposite.target(MinecraftClient.getInstance()),ProjectionShaders.SURFACE)){
            surface.bind(pass);pass.drawMultipleIndexed(draw.commands,index,type,java.util.List.of(),null);
        }
    }
    boolean drawSurface(Matrix4f view,Matrix4f projection,Identifier texture,boolean intensity,float opacity,QuadVisibility.Part geometry,QuadVisibility.Mask completed,QuadVisibility.Mask wrong,boolean red){
        if(geometry.rangeCount(completed,wrong,red)==0)return false;
        var surface=surface(view,projection,opacity,texture,intensity,red);
        RenderSystem.getSequentialBuffer(mode).getIndexBuffer(indices);
        try(var pass=pass(ProjectionComposite.target(MinecraftClient.getInstance()),ProjectionShaders.SURFACE)){surface.bind(pass);return draw(pass,geometry,completed,wrong,red);}
    }
    @Override public void close(){vertices.close();}
}
