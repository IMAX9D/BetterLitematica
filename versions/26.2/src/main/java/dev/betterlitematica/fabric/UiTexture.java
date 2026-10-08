package dev.betterlitematica.fabric;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import java.util.ArrayDeque;

/** Owns raster textures without freeing views still referenced by extracted frames. */
final class UiTexture extends DynamicTexture {
    private static long sequence;
    private static final ArrayDeque<Identifier> retired=new ArrayDeque<>();
    UiTexture(NativeImage image){super(()->"BetterLitematica UI",image);}
    void setFilter(boolean linear,boolean mipmaps){sampler=RenderSystem.getSamplerCache().getClampToEdge(linear?FilterMode.LINEAR:FilterMode.NEAREST,mipmaps);}
    static Identifier register(String name,UiTexture texture){var id=Identifier.fromNamespaceAndPath("betterlitematica",name+"/"+(sequence++));Minecraft.getInstance().getTextureManager().register(id,texture);return id;}
    static void retire(Identifier id){if(id!=null)retired.addLast(id);}
    /** Called on a tick before a new frame is extracted; earlier draws have been submitted. */
    static void collect(){while(!retired.isEmpty()){var id=retired.removeFirst();RenderSystem.queueFencedTask(()->Minecraft.getInstance().getTextureManager().release(id));}}
    void uploadRegion(int x,int y,int width,int height){var image=getPixels();if(image!=null)try(var region=new NativeImage(width,height,false)){image.copyRect(region,x,y,0,0,width,height,false,false);var encoder=RenderSystem.getDevice().createCommandEncoder();encoder.writeToTexture(getTexture(),region,0,0,x,y);}}
}
