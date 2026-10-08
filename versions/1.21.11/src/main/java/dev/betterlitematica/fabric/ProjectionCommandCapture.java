package dev.betterlitematica.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.render.command.*;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.BufferAllocator;
import java.util.LinkedHashMap;
import java.util.function.Consumer;

/** Executes vanilla model, item, text and custom commands into the existing bounded CPU mesh capture. */
final class ProjectionCommandCapture implements AutoCloseable {
    private final BufferAllocator allocator=new BufferAllocator(256);
    private final OrderedRenderCommandQueueImpl queue=new OrderedRenderCommandQueueImpl();
    private ProjectionModels target;
    private final VertexConsumerProvider.Immediate consumers=new VertexConsumerProvider.Immediate(allocator,new LinkedHashMap<>()){
        @Override public VertexConsumer getBuffer(RenderLayer layer){return target.getBuffer(layer);}
    };
    private final OutlineVertexConsumerProvider outlines=new OutlineVertexConsumerProvider(){
        @Override public VertexConsumer getBuffer(RenderLayer layer){return target.getBuffer(layer);}
    };
    private RenderDispatcher dispatcher;
    static CameraRenderState camera(){
        var camera=MinecraftClient.getInstance().gameRenderer.getCamera();var state=new CameraRenderState();
        state.pos=camera.getCameraPos();state.blockPos=camera.getBlockPos();state.entityPos=state.pos;
        state.orientation=new org.joml.Quaternionf(camera.getRotation());state.initialized=true;return state;
    }
    void block(net.minecraft.block.entity.BlockEntity entity,ProjectionModels target){
        var manager=MinecraftClient.getInstance().getBlockEntityRenderDispatcher();var state=manager.getRenderState(entity,0,null);
        state.lightmapCoordinates=LightmapTextureManager.MAX_LIGHT_COORDINATE;
        render(target,queue->manager.render(state,new net.minecraft.client.util.math.MatrixStack(),queue,camera()));
    }
    void render(ProjectionModels target,Consumer<OrderedRenderCommandQueue> submit){
        if(this.target!=null)throw new IllegalStateException("模型捕获不能重入");
        this.target=target;queue.clear();
        try{
            submit.accept(queue);
            // World particle passes render directly to the live framebuffer and cannot enter a
            // static schematic mesh. Refuse that command rather than accidentally painting the world.
            for(var batch:queue.getBatchingQueues().values())if(!batch.getLayeredCustomCommands().isEmpty())throw new IllegalArgumentException("动态粒子不能用作静态投影模型");
            if(dispatcher==null){var client=MinecraftClient.getInstance();dispatcher=new RenderDispatcher(queue,client.getBlockRenderManager(),consumers,client.getAtlasManager(),outlines,consumers,client.textRenderer);}
            dispatcher.render();
        }finally{queue.clear();this.target=null;}
    }
    @Override public void close(){queue.clear();if(dispatcher!=null){dispatcher.close();dispatcher=null;}outlines.plainDrawer.allocator.close();allocator.close();}
}
