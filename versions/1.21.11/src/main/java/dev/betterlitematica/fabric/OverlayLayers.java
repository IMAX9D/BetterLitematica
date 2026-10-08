package dev.betterlitematica.fabric;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.*;

/** Each overlay owns its draw state, including the entity mask for through-wall inspection. */
final class OverlayLayers extends RenderLayer {
    private final boolean face,through;
    private final RenderPipeline pipeline;
    private static final RenderLayer[] LINES={new OverlayLayers(false,false),new OverlayLayers(false,true)};
    private static final RenderLayer[] FACES={new OverlayLayers(true,false),new OverlayLayers(true,true)};
    private OverlayLayers(boolean face,boolean through){
        super("betterlitematica_overlay_"+face+"_"+through,setup(face,through));
        this.face=face;this.through=through;pipeline=getRenderPipeline();
    }
    private static RenderSetup setup(boolean face,boolean through){var builder=RenderSetup.builder(ProjectionShaders.overlay(face,through)).expectedBufferSize(32768);if(face)builder.translucent();return builder.build();}
    static RenderLayer lines(boolean through){return LINES[through?1:0];}
    static RenderLayer faces(boolean through){return FACES[through?1:0];}
    public Framebuffer getTarget(){return MinecraftClient.getInstance().getFramebuffer();}
    public RenderPipeline getPipeline(){return pipeline;}
    @Override public VertexFormat getVertexFormat(){return face?VertexFormats.POSITION_COLOR:VertexFormats.POSITION_COLOR_NORMAL;}
    @Override public VertexFormat.DrawMode getDrawMode(){return face?VertexFormat.DrawMode.QUADS:VertexFormat.DrawMode.LINES;}
    @Override public void draw(BuiltBuffer source){
        try(source){var parameters=source.getDrawParameters();if(parameters.indexCount()==0)return;
            var format=parameters.format();var vertices=format.uploadImmediateVertexBuffer(source.getBuffer());
            var sequential=RenderSystem.getSequentialBuffer(parameters.mode());
            var indices=source.getSortedBuffer()==null?sequential.getIndexBuffer(parameters.indexCount()):format.uploadImmediateIndexBuffer(source.getSortedBuffer());
            var indexType=source.getSortedBuffer()==null?sequential.getIndexType():parameters.indexType();
            var transforms=ProjectionUniforms.transforms(RenderSystem.getModelViewMatrix(),1,1,1,1,1);var mask=EntityOverlayMask.prepare(through);
            try(var pass=ProjectionGpu.pass(getTarget(),pipeline)){
                pass.setUniform("DynamicTransforms",transforms);
                mask.bind(pass);pass.setVertexBuffer(0,vertices);pass.setIndexBuffer(indices,indexType);pass.drawIndexed(0,0,parameters.indexCount(),1);
            }
        }
    }
}
