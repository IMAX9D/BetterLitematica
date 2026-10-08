package dev.betterlitematica.fabric;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.resources.Identifier;
/** Explicit immutable pipelines: no global OpenGL state and no backend assumptions. */
final class ProjectionShaders {
 static Identifier id(String path){return Identifier.fromNamespaceAndPath("betterlitematica",path);}
 static final RenderPipeline SURFACE=base("surface","projection","projection_surface",DefaultVertexFormat.POSITION_TEX_COLOR,com.mojang.blaze3d.PrimitiveTopology.QUADS).withBindGroupLayout(BindGroupLayout.builder().withSampler("Sampler0").build()).withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,true,1,1)).build();
 static final RenderPipeline COMPOSITE=base("composite","projection","projection_composite",DefaultVertexFormat.POSITION_TEX_COLOR,com.mojang.blaze3d.PrimitiveTopology.QUADS).withBindGroupLayout(BindGroupLayout.builder().withSampler("Sampler0").build()).withDepthStencilState(java.util.Optional.empty()).withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build();
 private static final RenderPipeline[] LINES={overlay(false,false),overlay(false,true)},FACES={overlay(true,false),overlay(true,true)};
 private static RenderPipeline.Builder base(String name,String vertex,String fragment,VertexFormat format,com.mojang.blaze3d.PrimitiveTopology mode){return RenderPipeline.builder().withLocation(id("pipeline/"+name)).withVertexShader(id("core/"+vertex)).withFragmentShader(id("core/"+fragment)).withBindGroupLayout(BindGroupLayout.builder().withUniform("Projection",UniformType.UNIFORM_BUFFER).build()).withVertexBinding(0,format).withPrimitiveTopology(mode).withCull(false);}
 private static RenderPipeline overlay(boolean face,boolean through){return base("overlay_"+face+"_"+through,face?"overlay_face":"overlay_line","overlay_mask",face?DefaultVertexFormat.POSITION_COLOR:DefaultVertexFormat.POSITION_COLOR_NORMAL,face?com.mojang.blaze3d.PrimitiveTopology.QUADS:com.mojang.blaze3d.PrimitiveTopology.LINES).withBindGroupLayout(BindGroupLayout.builder().withSampler("EntityDepthBefore").withSampler("EntityDepthAfter").build()).withDepthStencilState(new DepthStencilState(through?CompareOp.ALWAYS_PASS:CompareOp.GREATER_THAN_OR_EQUAL,false)).withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build();}
 static RenderPipeline overlay(boolean face,int through){return(face?FACES:LINES)[through];}
}
