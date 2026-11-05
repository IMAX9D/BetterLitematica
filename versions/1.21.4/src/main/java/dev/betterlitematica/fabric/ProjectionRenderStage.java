package dev.betterlitematica.fabric;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.util.math.MatrixStack;

final class ProjectionRenderStage {
    private ProjectionRenderStage(){}
    static void draw(ProjectionController controller,WorldRenderContext context){
        if(ProjectionShaderPass.shadowPass())return;
        if(!ProjectionShaderPass.enabled()){controller.render(context);controller.capturePreview();return;}
        var matrices=new MatrixStack();
        if(context.matrixStack()!=null){
            matrices.peek().getPositionMatrix().set(context.matrixStack().peek().getPositionMatrix());
            matrices.peek().getNormalMatrix().set(context.matrixStack().peek().getNormalMatrix());
        }
        var snapshot=new Snapshot(
            context.worldRenderer(),
            matrices,
            context.tickCounter(),
            context.blockOutlines(),
            context.camera(),
            context.gameRenderer(),
            new org.joml.Matrix4f(context.projectionMatrix()),
            new org.joml.Matrix4f(context.positionMatrix()),
            context.world(),
            context.advancedTranslucency(),
            context.consumers(),
            context.frustum());
        ProjectionShaderPass.defer(context.world(),()->{
            try{controller.render(snapshot);controller.capturePreview();}
            finally{EntityOverlayMask.reset();}
        });
    }
    private record Snapshot(
        net.minecraft.client.render.WorldRenderer worldRenderer,
        net.minecraft.client.util.math.MatrixStack matrixStack,
        net.minecraft.client.render.RenderTickCounter tickCounter,
        boolean blockOutlines,
        net.minecraft.client.render.Camera camera,
        net.minecraft.client.render.GameRenderer gameRenderer,
        org.joml.Matrix4f projectionMatrix,
        org.joml.Matrix4f positionMatrix,
        net.minecraft.client.world.ClientWorld world,
        boolean advancedTranslucency,
        net.minecraft.client.render.VertexConsumerProvider consumers,
        net.minecraft.client.render.Frustum frustum) implements WorldRenderContext {}
}
