package dev.betterlitematica.fabric;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.resources.Identifier;
/** Explicit immutable pipelines: no global OpenGL state and no backend assumptions. */
final class ProjectionShaders {
 static Identifier id(String path){return Identifier.fromNamespaceAndPath("betterlitematica",path);}
 static final RenderPipeline SURFACE=base("surface","projection","projection_surface",DefaultVertexFormat.POSITION_TEX_COLOR,com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS).withBindGroupLayout(BindGroupLayout.builder().withUniform("Sampler0",UniformType.COMBINED_IMAGE_SAMPLER).build()).withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,true,1,1)).withColorTargetState(ColorTargetState.DEFAULT).build();
 static final RenderPipeline COMPOSITE=base("composite","projection","projection_composite",DefaultVertexFormat.POSITION_TEX_COLOR,com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS).withBindGroupLayout(BindGroupLayout.builder().withUniform("Sampler0",UniformType.COMBINED_IMAGE_SAMPLER).build()).withDepthStencilState(java.util.Optional.empty()).withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build();
 private static final RenderPipeline[] LINES={overlay(false,false),overlay(false,true)},FACES={overlay(true,false),overlay(true,true)};
 private static RenderPipeline.Builder base(String name,String vertex,String fragment,VertexFormat format,com.mojang.renderpearl.api.pipeline.PrimitiveTopology mode){return RenderPipeline.builder().withLocation(id("pipeline/"+name)).withVertexShader(id("core/"+vertex)).withFragmentShader(id("core/"+fragment)).withBindGroupLayout(BindGroupLayout.builder().withUniform("Projection",UniformType.UNIFORM_BUFFER).build()).withVertexBinding(0,format).withPrimitiveTopology(mode).withCull(false);}
 private static RenderPipeline overlay(boolean face,boolean through){return base("overlay_"+face+"_"+through,face?"overlay_face":"overlay_line","overlay_mask",face?DefaultVertexFormat.POSITION_COLOR:DefaultVertexFormat.POSITION_COLOR_NORMAL,face?com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS:com.mojang.renderpearl.api.pipeline.PrimitiveTopology.LINES).withBindGroupLayout(BindGroupLayout.builder().withUniform("EntityDepthBefore",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("EntityDepthAfter",UniformType.COMBINED_IMAGE_SAMPLER).build()).withDepthStencilState(new DepthStencilState(through?CompareOp.ALWAYS_PASS:CompareOp.GREATER_THAN_OR_EQUAL,false)).withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build();}
 static RenderPipeline overlay(boolean face,int through){return(face?FACES:LINES)[through];}
}
