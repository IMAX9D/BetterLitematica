package dev.betterlitematica.fabric;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import dev.betterlitematica.runtime.UiSectorMesh;

/** Immutable physical-pixel geometry submitted during UI extraction. */
record UiMeshState(UiSectorMesh mesh,float ox,float oy,int color,Matrix3x2f pose,ScreenRectangle scissorArea,ScreenRectangle bounds) implements GuiElementRenderState {
    UiMeshState {pose=new Matrix3x2f(pose);}
    @Override public RenderPipeline pipeline(){return RenderPipelines.GUI;}
    @Override public TextureSetup textureSetup(){return TextureSetup.noTexture();}
    @Override public void buildVertices(VertexConsumer consumer){
        // RenderPearl culls the opposite winding; reverse coverage together with each vertex.
        for(int quad=0;quad<mesh.vertexCount();quad+=4)for(int vertex=0;vertex<4;vertex++){
            int i=quad+(vertex==0?0:4-vertex);
            consumer.addVertexWith2DPose(pose,ox+mesh.x(i),oy+mesh.y(i)).setColor(mesh.color(i,color));
        }
    }
}
