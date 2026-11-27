package dev.betterlitematica.fabric;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;

record ProjectionRenderContext(Camera camera,RenderTickCounter tickCounter,MatrixStack matrixStack,Matrix4f positionMatrix,Matrix4f projectionMatrix,Frustum frustum) {
    ProjectionRenderContext withFrustum(Frustum frustum){return new ProjectionRenderContext(camera,tickCounter,matrixStack,positionMatrix,projectionMatrix,frustum);}
}
