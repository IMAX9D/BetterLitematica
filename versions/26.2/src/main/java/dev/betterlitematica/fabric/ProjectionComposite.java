package dev.betterlitematica.fabric;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
/** All placements share one nearest-surface image; opacity is applied once per pixel. */
final class ProjectionComposite implements AutoCloseable {
 private RenderTarget target;private ProjectionGpuMesh quad;
 RenderTarget target(){return target;}
 void begin(Minecraft client){var main=ClientUi.target(client);if(target==null||target.width!=main.width||target.height!=main.height){close();target=new TextureTarget("BetterLitematica nearest surface",main.width,main.height,true,com.mojang.blaze3d.GpuFormat.RGBA8_UNORM);}ProjectionDraw.command(()->{var encoder=RenderSystem.getDevice().createCommandEncoder();encoder.clearColorAndDepthTextures(target.getColorTexture(),new org.joml.Vector4f(0,0,0,0),target.getDepthTexture(),0);target.copyDepthFrom(ClientUi.target(client));});ProjectionDraw.target(this);}
 void finish(Minecraft client,float opacity){ProjectionDraw.target(null);if(quad==null){try(var b=new ProjectionBuffer(256)){b.begin();b.vertex(-1,-1,0).uv(0,0).color(255,255,255,255);b.vertex(1,-1,0).uv(1,0).color(255,255,255,255);b.vertex(1,1,0).uv(1,1).color(255,255,255,255);b.vertex(-1,1,0).uv(0,1).color(255,255,255,255);quad=new ProjectionGpuMesh(b.end());}}ProjectionDraw.blit(quad,target.getColorTextureView(),opacity);}
 @Override public void close(){if(target!=null){var old=target;RenderSystem.queueFencedTask(old::destroyBuffers);target=null;}if(quad!=null){quad.close();quad=null;}}
}
