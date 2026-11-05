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
            matrices.peek().getPositionMatrix().load(context.matrixStack().peek().getPositionMatrix());
            matrices.peek().getNormalMatrix().load(context.matrixStack().peek().getNormalMatrix());
        }
        var snapshot=new Snapshot(
            context.worldRenderer(),
            matrices,
            context.tickDelta(),
            context.limitTime(),
            context.blockOutlines(),
            context.camera(),
            context.gameRenderer(),
            context.lightmapTextureManager(),
            new net.minecraft.util.math.Matrix4f(context.projectionMatrix()),
            context.world(),
            context.profiler(),
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
        float tickDelta,
        long limitTime,
        boolean blockOutlines,
        net.minecraft.client.render.Camera camera,
        net.minecraft.client.render.GameRenderer gameRenderer,
        net.minecraft.client.render.LightmapTextureManager lightmapTextureManager,
        net.minecraft.util.math.Matrix4f projectionMatrix,
        net.minecraft.client.world.ClientWorld world,
        net.minecraft.util.profiler.Profiler profiler,
        boolean advancedTranslucency,
        net.minecraft.client.render.VertexConsumerProvider consumers,
        net.minecraft.client.render.Frustum frustum) implements WorldRenderContext {}
}
