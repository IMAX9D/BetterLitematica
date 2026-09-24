package dev.betterlitematica.fabric;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
final class ProjectionShaders {
 private static ShaderProgram surface,composite;
 static ShaderProgram surface(){return surface;}
 static ShaderProgram composite(){return composite;}
 static void register(){CoreShaderRegistrationCallback.EVENT.register(context->{
 context.register(new Identifier("betterlitematica","projection_surface"),VertexFormats.POSITION_TEXTURE_COLOR,p->surface=p);
 context.register(new Identifier("betterlitematica","projection_composite"),VertexFormats.POSITION_TEXTURE_COLOR,p->composite=p);
 });}
}
