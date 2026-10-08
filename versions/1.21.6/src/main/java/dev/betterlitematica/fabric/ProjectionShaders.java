package dev.betterlitematica.fabric;

import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gl.*;
import net.minecraft.client.render.VertexFormats;

/** Explicit pipelines share Minecraft's frame uniform buffers, with a bounded mode block. */
final class ProjectionShaders {
    private ProjectionShaders(){}
    private static RenderPipeline.Builder common(String name,String vertex,String fragment){
        return RenderPipeline.builder().withLocation(net.minecraft.util.Identifier.of("betterlitematica",name)).withVertexShader(net.minecraft.util.Identifier.of(vertex)).withFragmentShader(net.minecraft.util.Identifier.of(fragment))
            .withUniform("DynamicTransforms",UniformType.UNIFORM_BUFFER).withUniform("Projection",UniformType.UNIFORM_BUFFER).withCull(false);
    }
    static final RenderPipeline SURFACE=RenderPipelines.register(common("projection_surface","betterlitematica:core/projection","betterlitematica:core/projection_surface")
        .withSampler("Sampler0").withUniform("ProjectionModes",UniformType.UNIFORM_BUFFER)
        .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR,VertexFormat.DrawMode.QUADS)
        .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST).withDepthWrite(true).withDepthBias(-1,-1).withoutBlend().build());
    static final RenderPipeline COMPOSITE=RenderPipelines.register(common("projection_composite","betterlitematica:core/projection","betterlitematica:core/projection_composite")
        .withSampler("Sampler0").withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR,VertexFormat.DrawMode.QUADS)
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false).withBlend(BlendFunction.TRANSLUCENT).build());
    static RenderPipeline overlay(boolean face,boolean through){
        var builder=common("overlay_"+face+"_"+through,face?"betterlitematica:core/overlay_faces":"minecraft:core/rendertype_lines",
            face?"betterlitematica:core/overlay_faces":"betterlitematica:core/overlay_lines")
            .withSampler("EntityDepthBefore").withSampler("EntityDepthAfter").withUniform("ProjectionModes",UniformType.UNIFORM_BUFFER)
            .withVertexFormat(face?VertexFormats.POSITION_COLOR:VertexFormats.POSITION_COLOR_NORMAL,face?VertexFormat.DrawMode.QUADS:VertexFormat.DrawMode.LINES)
            .withDepthTestFunction(through?DepthTestFunction.NO_DEPTH_TEST:DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(false).withBlend(BlendFunction.TRANSLUCENT);
        if(!face)builder.withUniform("Globals",UniformType.UNIFORM_BUFFER).withUniform("Fog",UniformType.UNIFORM_BUFFER);
        return RenderPipelines.register(builder.build());
    }
    static void register(){OverlayLayers.lines(false);OverlayLayers.faces(false);}
}
