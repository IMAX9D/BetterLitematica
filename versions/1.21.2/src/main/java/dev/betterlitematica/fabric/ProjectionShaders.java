package dev.betterlitematica.fabric;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.*;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import java.util.List;
/** ShaderLoader owns compilation, resource reload and disposal in this game generation. */
final class ProjectionShaders {
    static final ShaderProgramKey SURFACE=key("projection_surface",VertexFormats.POSITION_TEXTURE_COLOR);
    static final ShaderProgramKey COMPOSITE=key("projection_composite",VertexFormats.POSITION_TEXTURE_COLOR);
    static final ShaderProgramKey LINES=key("overlay_lines",VertexFormats.LINES);
    static final ShaderProgramKey FACES=key("overlay_faces",VertexFormats.POSITION_COLOR);
    private static ShaderProgramKey key(String name,VertexFormat format){return new ShaderProgramKey(Identifier.of("betterlitematica","core/"+name),format,Defines.EMPTY);}
    private static ShaderProgram program(ShaderProgramKey key){
        var shader=MinecraftClient.getInstance().getShaderLoader().getOrCreateProgram(key);
        if(shader==null)throw new IllegalStateException("投影着色器未就绪");
        return shader;
    }
    static ShaderProgram surface(){return program(SURFACE);}
    static ShaderProgram composite(){return program(COMPOSITE);}
 // Programs are shared between layers, so every supplier states its own near fade at draw time.
 static final float NEAR_CLEAR=1f,NEAR_FULL=3f,NEAR_MIN=.18f;
 static ShaderProgram overlayLines(){return near(EntityOverlayMask.bind(program(LINES)),false);}
    static ShaderProgram overlayFaces(){return near(EntityOverlayMask.bind(program(FACES)),false);}
 static ShaderProgram nearLines(){return near(EntityOverlayMask.bind(program(LINES)),true);}
 static ShaderProgram nearFaces(){return near(EntityOverlayMask.bind(program(FACES)),true);}
 /** Depth-tested highlights rely on depth for occlusion; the entity mask would hide lines drawn in front of mobs. */
 static ShaderProgram nearLinesDepthTested(){return near(unmasked(program(LINES)),true);}
 static ShaderProgram nearFacesDepthTested(){return near(unmasked(program(FACES)),true);}
 private static ShaderProgram unmasked(ShaderProgram shader){shader.getUniformOrDefault("EntityMaskEnabled").set(0);return shader;}
 private static ShaderProgram near(ShaderProgram shader,boolean fade){var uniform=shader.getUniformOrDefault("NearFade");if(fade)uniform.set(NEAR_CLEAR,NEAR_FULL,NEAR_MIN);else uniform.set(0f,0f,1f);return shader;}
    static void register(){
        var programs=ShaderProgramKeys.getAll();
        for(var key:List.of(SURFACE,COMPOSITE,LINES,FACES))if(!programs.contains(key))programs.add(key);
    }
}
