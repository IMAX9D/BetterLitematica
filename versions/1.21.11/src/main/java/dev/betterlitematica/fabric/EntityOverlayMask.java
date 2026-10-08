package dev.betterlitematica.fabric;

import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import net.minecraft.client.MinecraftClient;

/** GPU depth snapshots isolate entity pixels without hiding terrain overlays. */
final class EntityOverlayMask {
    private static GpuTexture before,after;
    private static GpuTextureView beforeView,afterView;
    private static int width,height;
    private static boolean captured,ready;
    private EntityOverlayMask(){}
    static void reset(){captured=false;ready=false;}
    static void before(MinecraftClient client,boolean needed){
        ProjectionUniforms.beginFrame();reset();if(!needed)return;var target=client.getFramebuffer();
        if(before==null||width!=target.textureWidth||height!=target.textureHeight){
            close();width=target.textureWidth;height=target.textureHeight;before=allocate("before entities");after=allocate("after entities");
            beforeView=RenderSystem.getDevice().createTextureView(before);afterView=RenderSystem.getDevice().createTextureView(after);
        }
        copy(client,before);captured=true;
    }
    static void after(MinecraftClient client){if(captured){copy(client,after);ready=true;}}
    static Mask prepare(boolean enabled){
        var depth=MinecraftClient.getInstance().getFramebuffer().getDepthAttachmentView();
        return new Mask(ProjectionUniforms.modes(0,0,ready&&enabled),ready?beforeView:depth,ready?afterView:depth,RenderSystem.getSamplerCache().get(FilterMode.NEAREST));
    }
    record Mask(com.mojang.blaze3d.buffers.GpuBuffer modes,GpuTextureView before,GpuTextureView after,net.minecraft.client.gl.GpuSampler sampler){
        void bind(RenderPass pass){pass.setUniform("ProjectionModes",modes);pass.bindTexture("EntityDepthBefore",before,sampler);pass.bindTexture("EntityDepthAfter",after,sampler);}
    }
    private static GpuTexture allocate(String name){
        var texture=RenderSystem.getDevice().createTexture("BetterLitematica "+name,GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING,TextureFormat.DEPTH32,width,height,1,1);
        return texture;
    }
    private static void copy(MinecraftClient client,GpuTexture destination){
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(client.getFramebuffer().getDepthAttachment(),destination,0,0,0,0,0,width,height);
    }
    static void close(){RenderSystem.assertOnRenderThread();reset();if(beforeView!=null)beforeView.close();if(afterView!=null)afterView.close();beforeView=afterView=null;if(before!=null)before.close();if(after!=null)after.close();before=after=null;width=height=0;ProjectionUniforms.close();}
}
