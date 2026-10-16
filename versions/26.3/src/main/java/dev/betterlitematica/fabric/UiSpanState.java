package dev.betterlitematica.fabric;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;

/** Keep a rounded shape atomic during GUI overlap sorting, including its antialiased edges. */
record UiSpanState(int[] spans,Matrix3x2f pose,ScreenRectangle scissorArea,ScreenRectangle bounds) implements GuiElementRenderState {
    @Override public RenderPipeline pipeline(){return RenderPipelines.GUI;}
    @Override public TextureSetup textureSetup(){return TextureSetup.noTexture();}
    @Override public void buildVertices(VertexConsumer consumer){for(int i=0;i<spans.length;i+=5){
        int l=spans[i],t=spans[i+1],r=spans[i+2],b=spans[i+3],color=spans[i+4];
        consumer.addVertexWith2DPose(pose,l,t).setColor(color);
        consumer.addVertexWith2DPose(pose,l,b).setColor(color);
        consumer.addVertexWith2DPose(pose,r,b).setColor(color);
        consumer.addVertexWith2DPose(pose,r,t).setColor(color);
    }}
}
