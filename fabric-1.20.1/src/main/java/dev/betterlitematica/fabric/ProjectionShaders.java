package dev.betterlitematica.fabric;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
final class ProjectionShaders {
 private static ShaderProgram surface,composite,overlayLines,overlayFaces;
 static ShaderProgram surface(){return surface;}
 static ShaderProgram composite(){return composite;}
 // Programs are shared between layers, so every supplier states its own near fade at draw time.
 static final float NEAR_CLEAR=1f,NEAR_FULL=3f,NEAR_MIN=.18f;
 static ShaderProgram overlayLines(){return near(EntityOverlayMask.bind(overlayLines),false);}
 static ShaderProgram overlayFaces(){return near(EntityOverlayMask.bind(overlayFaces),false);}
 static ShaderProgram nearLines(){return near(EntityOverlayMask.bind(overlayLines),true);}
 static ShaderProgram nearFaces(){return near(EntityOverlayMask.bind(overlayFaces),true);}
 /** Depth-tested highlights rely on depth for occlusion; the entity mask would hide lines drawn in front of mobs. */
 static ShaderProgram nearLinesDepthTested(){return near(unmasked(overlayLines),true);}
 static ShaderProgram nearFacesDepthTested(){return near(unmasked(overlayFaces),true);}
 private static ShaderProgram unmasked(ShaderProgram shader){shader.getUniformOrDefault("EntityMaskEnabled").set(0);return shader;}
 private static ShaderProgram near(ShaderProgram shader,boolean fade){var uniform=shader.getUniformOrDefault("NearFade");if(fade)uniform.set(NEAR_CLEAR,NEAR_FULL,NEAR_MIN);else uniform.set(0f,0f,1f);return shader;}
 static void register(){CoreShaderRegistrationCallback.EVENT.register(context->{
 context.register(new Identifier("betterlitematica","overlay_lines"),VertexFormats.LINES,p->overlayLines=p);
 context.register(new Identifier("betterlitematica","overlay_faces"),VertexFormats.POSITION_COLOR,p->overlayFaces=p);
 context.register(new Identifier("betterlitematica","projection_surface"),VertexFormats.POSITION_TEXTURE_COLOR,p->surface=p);
 context.register(new Identifier("betterlitematica","projection_composite"),VertexFormats.POSITION_TEXTURE_COLOR,p->composite=p);
 });}
}
