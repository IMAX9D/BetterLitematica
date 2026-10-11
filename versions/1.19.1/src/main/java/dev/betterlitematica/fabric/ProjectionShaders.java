package dev.betterlitematica.fabric;
import net.minecraft.client.render.Shader;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.resource.ResourceFactory;
import net.minecraft.util.Identifier;
import java.io.IOException;
/** Reload custom programs together with vanilla on versions before Fabric's shader callback. */
public final class ProjectionShaders {
 private static Shader surface,composite,overlayLines,overlayFaces;
 static Shader surface(){return surface;}
 static Shader composite(){return composite;}
 // Programs are shared between layers, so every supplier states its own near fade at draw time.
 static final float NEAR_CLEAR=1f,NEAR_FULL=3f,NEAR_MIN=.18f;
 static Shader overlayLines(){return near(EntityOverlayMask.bind(overlayLines),false);}
 static Shader overlayFaces(){return near(EntityOverlayMask.bind(overlayFaces),false);}
 static Shader nearLines(){return near(EntityOverlayMask.bind(overlayLines),true);}
 static Shader nearFaces(){return near(EntityOverlayMask.bind(overlayFaces),true);}
 /** Depth-tested highlights rely on depth for occlusion; the entity mask would hide lines drawn in front of mobs. */
 static Shader nearLinesDepthTested(){return near(unmasked(overlayLines),true);}
 static Shader nearFacesDepthTested(){return near(unmasked(overlayFaces),true);}
 private static Shader unmasked(Shader shader){shader.getUniformOrDefault("EntityMaskEnabled").set(0);return shader;}
 private static Shader near(Shader shader,boolean fade){var uniform=shader.getUniformOrDefault("NearFade");if(fade)uniform.set(NEAR_CLEAR,NEAR_FULL,NEAR_MIN);else uniform.set(0f,0f,1f);return shader;}
 static void register(){} // LegacyShaderReloadMixin installs programs during vanilla shader reload.
 public static void close(){
  for(Shader shader:new Shader[]{surface,composite,overlayLines,overlayFaces})if(shader!=null)shader.close();
  surface=composite=overlayLines=overlayFaces=null;
 }
 public static void reload(ResourceFactory resources)throws IOException {
  ResourceFactory scoped=id->{
   String prefix="shaders/core/betterlitematica_";
   return resources.getResource(id.getNamespace().equals("minecraft")&&id.getPath().startsWith(prefix)
    ?new Identifier("betterlitematica","shaders/core/"+id.getPath().substring(prefix.length())):id);
  };
  try {
   overlayLines=new Shader(scoped,"betterlitematica_overlay_lines",VertexFormats.LINES);
   overlayFaces=new Shader(scoped,"betterlitematica_overlay_faces",VertexFormats.POSITION_COLOR);
   surface=new Shader(scoped,"betterlitematica_projection_surface",VertexFormats.POSITION_TEXTURE_COLOR);
   composite=new Shader(scoped,"betterlitematica_projection_composite",VertexFormats.POSITION_TEXTURE_COLOR);
  }catch(IOException|RuntimeException failure){close();throw failure;}
 }
}
