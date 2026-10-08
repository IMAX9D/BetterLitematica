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
        super("betterlitematica_overlay_"+face+"_"+through,32768,false,face,()->{},()->{});
        this.face=face;this.through=through;pipeline=ProjectionShaders.overlay(face,through);
    }
    static RenderLayer lines(boolean through){return LINES[through?1:0];}
    static RenderLayer faces(boolean through){return FACES[through?1:0];}
    @Override public Framebuffer getTarget(){return MinecraftClient.getInstance().getFramebuffer();}
    @Override public RenderPipeline getPipeline(){return pipeline;}
    @Override public VertexFormat getVertexFormat(){return face?VertexFormats.POSITION_COLOR:VertexFormats.POSITION_COLOR_NORMAL;}
    @Override public VertexFormat.DrawMode getDrawMode(){return face?VertexFormat.DrawMode.QUADS:VertexFormat.DrawMode.LINES;}
    @Override public void draw(BuiltBuffer source){
        try(source){var parameters=source.getDrawParameters();if(parameters.indexCount()==0)return;
            var format=parameters.format();var vertices=format.uploadImmediateVertexBuffer(source.getBuffer());
            var sequential=RenderSystem.getSequentialBuffer(parameters.mode());
            var indices=source.getSortedBuffer()==null?sequential.getIndexBuffer(parameters.indexCount()):format.uploadImmediateIndexBuffer(source.getSortedBuffer());
            var indexType=source.getSortedBuffer()==null?sequential.getIndexType():parameters.indexType();
            try(var pass=ProjectionGpu.pass(getTarget(),pipeline)){
                pass.setUniform("ModelViewMat",RenderSystem.getModelViewMatrix());pass.setUniform("ProjMat",RenderSystem.getProjectionMatrix());
                var color=RenderSystem.getShaderColor();pass.setUniform("ColorModulator",color[0],color[1],color[2],color[3]);
                if(!face){var fog=RenderSystem.getShaderFog();
                    pass.setUniform("LineWidth",RenderSystem.getShaderLineWidth());pass.setUniform("ScreenSize",(float)getTarget().textureWidth,(float)getTarget().textureHeight);
                    pass.setUniform("FogShape",fog.shape().getId());pass.setUniform("FogStart",fog.start());pass.setUniform("FogEnd",fog.end());pass.setUniform("FogColor",fog.red(),fog.green(),fog.blue(),fog.alpha());
                }
                EntityOverlayMask.bind(pass,through);pass.setVertexBuffer(0,vertices);pass.setIndexBuffer(indices,indexType);pass.drawIndexed(0,parameters.indexCount());
            }
        }
    }
}
