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
    static ShaderProgram overlayLines(){return EntityOverlayMask.bind(program(LINES));}
    static ShaderProgram overlayFaces(){return EntityOverlayMask.bind(program(FACES));}
    static void register(){
        var programs=ShaderProgramKeys.getAll();
        for(var key:List.of(SURFACE,COMPOSITE,LINES,FACES))if(!programs.contains(key))programs.add(key);
    }
}
