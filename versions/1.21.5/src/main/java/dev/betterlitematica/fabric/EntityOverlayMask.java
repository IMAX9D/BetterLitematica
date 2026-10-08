package dev.betterlitematica.fabric;

import com.mojang.blaze3d.systems.*;
import com.mojang.blaze3d.textures.*;
import net.minecraft.client.MinecraftClient;

/** GPU depth snapshots isolate entity pixels without hiding terrain overlays. */
final class EntityOverlayMask {
    private static GpuTexture before,after;
    private static int width,height;
    private static boolean captured,ready;
    private EntityOverlayMask(){}
    static void reset(){captured=false;ready=false;}
    static void before(MinecraftClient client,boolean needed){
        reset();if(!needed)return;var target=client.getFramebuffer();
        if(before==null||width!=target.textureWidth||height!=target.textureHeight){
            close();width=target.textureWidth;height=target.textureHeight;before=allocate("before entities");after=allocate("after entities");
        }
        copy(client,before);captured=true;
    }
    static void after(MinecraftClient client){if(captured){copy(client,after);ready=true;}}
    static void bind(RenderPass pass,boolean enabled){
        pass.setUniform("EntityMaskEnabled",ready&&enabled?1:0);
        // Always bind valid textures, including the first frame before snapshots exist.
        var depth=MinecraftClient.getInstance().getFramebuffer().getDepthAttachment();
        pass.bindSampler("EntityDepthBefore",ready?before:depth);pass.bindSampler("EntityDepthAfter",ready?after:depth);
    }
    private static GpuTexture allocate(String name){
        var texture=RenderSystem.getDevice().createTexture("BetterLitematica "+name,TextureFormat.DEPTH32,width,height,1);
        texture.setTextureFilter(FilterMode.NEAREST,false);texture.setAddressMode(AddressMode.CLAMP_TO_EDGE);return texture;
    }
    private static void copy(MinecraftClient client,GpuTexture destination){
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(client.getFramebuffer().getDepthAttachment(),destination,0,0,0,0,0,width,height);
    }
    static void close(){RenderSystem.assertOnRenderThread();reset();if(before!=null)before.close();if(after!=null)after.close();before=after=null;width=height=0;}
}
