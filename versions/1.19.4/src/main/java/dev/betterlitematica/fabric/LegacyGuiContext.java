package dev.betterlitematica.fabric;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

/** Matrix-stack GUI backend with bounded batches for antialiased rounded shapes. */
final class LegacyGuiContext {
    private final MatrixStack matrices;
    private BufferBuilder batch;
    LegacyGuiContext(MatrixStack matrices){this.matrices=matrices;}
    MatrixStack getMatrices(){return matrices;}
    void draw(){}
    void draw(Runnable shape){
        if(batch!=null){shape.run();return;}
        RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        batch=Tessellator.getInstance().getBuffer();batch.begin(VertexFormat.DrawMode.QUADS,VertexFormats.POSITION_COLOR);
        try{shape.run();}finally{var completed=batch;batch=null;BufferRenderer.drawWithGlobalProgram(completed.end());}
    }
    void fill(int left,int top,int right,int bottom,int color){
        if(batch==null){draw(()->fill(left,top,right,bottom,color));return;}
        var matrix=matrices.peek().getPositionMatrix();int a=color>>>24,r=color>>16&255,g=color>>8&255,b=color&255;
        batch.vertex(matrix,left,bottom,0).color(r,g,b,a).next();batch.vertex(matrix,right,bottom,0).color(r,g,b,a).next();
        batch.vertex(matrix,right,top,0).color(r,g,b,a).next();batch.vertex(matrix,left,top,0).color(r,g,b,a).next();
    }
    void drawTexture(Identifier id,int x,int y,float u,float v,int width,int height,int textureWidth,int textureHeight){
        RenderSystem.setShaderTexture(0,id);DrawableHelper.drawTexture(matrices,x,y,u,v,width,height,textureWidth,textureHeight);
    }
    void drawTexture(Identifier id,int x,int y,int width,int height,float u,float v,int sourceWidth,int sourceHeight,int textureWidth,int textureHeight){
        RenderSystem.setShaderTexture(0,id);DrawableHelper.drawTexture(matrices,x,y,width,height,u,v,sourceWidth,sourceHeight,textureWidth,textureHeight);
    }
    void drawItem(ItemStack stack,int x,int y){MinecraftClient.getInstance().getItemRenderer().renderInGuiWithOverrides(matrices,stack,x,y);}
    void enableScissor(int left,int top,int right,int bottom){
        var window=MinecraftClient.getInstance().getWindow();double scale=window.getScaleFactor();
        RenderSystem.enableScissor((int)(left*scale),(int)(window.getFramebufferHeight()-bottom*scale),(int)((right-left)*scale),(int)((bottom-top)*scale));
    }
    void disableScissor(){RenderSystem.disableScissor();}
}
