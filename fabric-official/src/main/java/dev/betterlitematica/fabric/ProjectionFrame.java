package dev.betterlitematica.fabric;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
/** Camera data copied during extraction, before any deferred projection draw. */
final class ProjectionFrame {
 record Camera(Vec3 getPosition,float getXRot,float getYRot,Quaternionf rotation){}
 private final Camera camera;private final PoseStack poses=new PoseStack();private final Matrix4f projection;private final Frustum frustum;private final float delta;
 ProjectionFrame(LevelExtractionContext context){var state=context.levelState().cameraRenderState;camera=new Camera(state.pos,state.xRot,state.yRot,new Quaternionf(state.orientation));poses.mulPose(new Matrix4f(state.viewRotationMatrix));projection=new Matrix4f(state.projectionMatrix);frustum=new Frustum(state.cullFrustum);delta=context.deltaTracker().getGameTimeDeltaPartialTick(false);}
 Camera camera(){return camera;}PoseStack matrixStack(){return poses;}Matrix4f projectionMatrix(){return projection;}Frustum frustum(){return frustum;}float tickDelta(){return delta;}
}
