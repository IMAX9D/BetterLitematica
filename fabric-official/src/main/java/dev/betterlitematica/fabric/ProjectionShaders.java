package dev.betterlitematica.fabric;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.resources.Identifier;
/** Explicit immutable pipelines: no global OpenGL state and no backend assumptions. */
final class ProjectionShaders {
 static Identifier id(String path){return Identifier.fromNamespaceAndPath("betterlitematica",path);}
 static final RenderPipeline SURFACE=base("surface","projection","projection_surface",DefaultVertexFormat.POSITION_TEX_COLOR,VertexFormat.Mode.QUADS).withSampler("Sampler0").withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL,true,-1,-1)).build();
 static final RenderPipeline COMPOSITE=base("composite","projection","projection_composite",DefaultVertexFormat.POSITION_TEX_COLOR,VertexFormat.Mode.QUADS).withSampler("Sampler0").withDepthStencilState(java.util.Optional.empty()).withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build();
 private static final RenderPipeline[] LINES={overlay(false,false),overlay(false,true)},FACES={overlay(true,false),overlay(true,true)};
 private static RenderPipeline.Builder base(String name,String vertex,String fragment,VertexFormat format,VertexFormat.Mode mode){return RenderPipeline.builder().withLocation(id("pipeline/"+name)).withVertexShader(id("core/"+vertex)).withFragmentShader(id("core/"+fragment)).withUniform("Projection",UniformType.UNIFORM_BUFFER).withVertexFormat(format,mode).withCull(false);}
 private static RenderPipeline overlay(boolean face,boolean through){return base("overlay_"+face+"_"+through,face?"overlay_face":"overlay_line","overlay_mask",face?DefaultVertexFormat.POSITION_COLOR:DefaultVertexFormat.POSITION_COLOR_NORMAL,face?VertexFormat.Mode.QUADS:VertexFormat.Mode.LINES).withSampler("EntityDepthBefore").withSampler("EntityDepthAfter").withDepthStencilState(new DepthStencilState(through?CompareOp.ALWAYS_PASS:CompareOp.LESS_THAN_OR_EQUAL,false)).withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build();}
 static RenderPipeline overlay(boolean face,int through){return(face?FACES:LINES)[through];}
}
