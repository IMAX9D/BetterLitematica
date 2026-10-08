package dev.betterlitematica.fabric;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.render.Shader;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
final class ProjectionShaders {
 private static Shader surface,composite,overlayLines,overlayFaces;
 static Shader surface(){return surface;}
 static Shader composite(){return composite;}
 static Shader overlayLines(){return EntityOverlayMask.bind(overlayLines);}
 static Shader overlayFaces(){return EntityOverlayMask.bind(overlayFaces);}
 static void register(){CoreShaderRegistrationCallback.EVENT.register(context->{
 context.register(new Identifier("betterlitematica","overlay_lines"),VertexFormats.LINES,p->overlayLines=p);
 context.register(new Identifier("betterlitematica","overlay_faces"),VertexFormats.POSITION_COLOR,p->overlayFaces=p);
 context.register(new Identifier("betterlitematica","projection_surface"),VertexFormats.POSITION_TEXTURE_COLOR,p->surface=p);
 context.register(new Identifier("betterlitematica","projection_composite"),VertexFormats.POSITION_TEXTURE_COLOR,p->composite=p);
 });}
}
