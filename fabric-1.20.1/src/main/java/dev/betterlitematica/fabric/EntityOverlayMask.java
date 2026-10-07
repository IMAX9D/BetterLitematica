package dev.betterlitematica.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/** Two GPU-only depth snapshots isolate visible entity pixels without hiding terrain overlays. */
final class EntityOverlayMask {
    private static int before,after,width,height;
    private static boolean captured,ready;
    private EntityOverlayMask(){}
    static void reset(){captured=false;ready=false;}
    static void before(MinecraftClient client,boolean needed){
        reset();if(!needed)return;
        var target=client.getFramebuffer();
        if(width!=target.textureWidth||height!=target.textureHeight||before==0){
            close();width=target.textureWidth;height=target.textureHeight;
            before=allocate();after=allocate();
        }
        copy(client,before);captured=true;
    }
    static void after(MinecraftClient client){if(!captured)return;copy(client,after);ready=true;}
    static ShaderProgram bind(ShaderProgram shader){
        shader.getUniformOrDefault("EntityMaskEnabled").set(ready?1:0);
        if(ready){shader.addSampler("EntityDepthBefore",before);shader.addSampler("EntityDepthAfter",after);}
        return shader;
    }
    private static int allocate(){
        int previous=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),texture=GL11.glGenTextures();
        try{GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12_CLAMP);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12_CLAMP);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL14.GL_DEPTH_COMPONENT24,width,height,0,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,(java.nio.ByteBuffer)null);
        }finally{GL11.glBindTexture(GL11.GL_TEXTURE_2D,previous);}return texture;
    }
    private static final int GL12_CLAMP=org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
    private static void copy(MinecraftClient client,int texture){
        int previous=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),read=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try{GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,client.getFramebuffer().fbo);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,0,0,width,height);
        }finally{GL11.glBindTexture(GL11.GL_TEXTURE_2D,previous);GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);}
    }
    static void close(){com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();reset();if(before!=0)GL11.glDeleteTextures(before);if(after!=0)GL11.glDeleteTextures(after);before=after=width=height=0;}
}
