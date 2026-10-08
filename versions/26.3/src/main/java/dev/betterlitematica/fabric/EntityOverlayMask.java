package dev.betterlitematica.fabric;
import com.mojang.blaze3d.systems.*;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.*;
import net.minecraft.client.Minecraft;
/** GPU depth snapshots exclude foreground entity pixels from through-terrain overlays. */
final class EntityOverlayMask {
 private static GpuTexture dummy;private static GpuTextureView dummyView;
 private static GpuTexture before,after;private static GpuTextureView beforeView,afterView;private static int width,height;private static boolean captured,ready;
 static boolean ready(){return ready;}static void reset(){captured=false;ready=false;}
 static void prepare(){if(dummy!=null)return;var device=RenderSystem.getDevice();dummy=device.createTexture("BetterLitematica inactive mask",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_DST,ClientUi.target(Minecraft.getInstance()).getDepthTexture().getFormat(),1,1,1,1);dummyView=device.createTextureView(dummy);device.createCommandEncoder().clearDepthTexture(dummy,0);}
 static void before(Minecraft client,boolean needed){reset();if(!needed)return;var main=ClientUi.target(client);if(before==null||width!=main.width||height!=main.height){release();width=main.width;height=main.height;var device=RenderSystem.getDevice();int usage=GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_TEXTURE_BINDING;before=device.createTexture("BetterLitematica terrain depth",usage,main.getDepthTexture().getFormat(),width,height,1,1);after=device.createTexture("BetterLitematica feature depth",usage,main.getDepthTexture().getFormat(),width,height,1,1);beforeView=device.createTextureView(before);afterView=device.createTextureView(after);}copy(client,before);captured=true;}
 static void after(Minecraft client){if(captured){copy(client,after);ready=true;}}
 private static void copy(Minecraft client,GpuTexture target){EntityDepthPass.snapshot(target,width,height);}
 static void bind(RenderPass pass){var sampler=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);pass.setUniform("EntityDepthBefore",ready?beforeView:dummyView,sampler);pass.setUniform("EntityDepthAfter",ready?afterView:dummyView,sampler);}
 private static void release(){reset();if(before!=null){var a=before;var b=after;var av=beforeView;var bv=afterView;RenderSystem.queueFencedTask(()->{av.close();bv.close();a.close();b.close();});}before=after=null;beforeView=afterView=null;width=height=0;}
 static void close(){release();if(dummy!=null){var texture=dummy;var view=dummyView;RenderSystem.queueFencedTask(()->{view.close();texture.close();});dummy=null;dummyView=null;}}
}
