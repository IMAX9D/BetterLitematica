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
 static Shader overlayLines(){return EntityOverlayMask.bind(overlayLines);}
 static Shader overlayFaces(){return EntityOverlayMask.bind(overlayFaces);}
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
