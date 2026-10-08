package dev.betterlitematica.fabric;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import java.util.*;
/** Captures the modern submit/feature pipeline into bounded textured meshes. */
final class ProjectionModels extends MultiBufferSource.BufferSource implements AutoCloseable {
static float shade(net.minecraft.client.resources.model.geometry.BakedQuad quad){return !quad.materialInfo().shade()?1f:quad.direction()==net.minecraft.core.Direction.UP?1f:quad.direction()==net.minecraft.core.Direction.DOWN?.6f:quad.direction().getAxis()==net.minecraft.core.Direction.Axis.X?.8f:.9f;}
 record Material(Identifier texture,boolean intensity){}
 private final Map<Material,ProjectionVertices> layers=new LinkedHashMap<>();private final ProjectionVertices untextured=new ProjectionVertices();
 private final SubmitNodeStorage submits=new SubmitNodeStorage();private net.minecraft.client.renderer.feature.FeatureRenderDispatcher features;
 ProjectionModels(){super(new ByteBufferBuilder(256),new LinkedHashMap<>());}
 void clear(){layers.clear();untextured.clear();submits.clear();}
 Map<Material,ProjectionVertices> layers(){return layers;}
 int vertices(){return layers.values().stream().mapToInt(ProjectionVertices::size).sum();}
 @Override public VertexConsumer getBuffer(RenderType layer){if(layer.mode()!=VertexFormat.Mode.QUADS)return untextured;var binding=layer.state.textures.get("Sampler0");if(binding==null)return untextured;var material=new Material(binding.location(),layer.name.startsWith("text_intensity"));if(layers.size()>=16&&!layers.containsKey(material))throw new IllegalArgumentException("模型材质超过预算");return layers.computeIfAbsent(material,id->new ProjectionVertices());}
 // Feature dispatch flushes batches normally; these batches are CPU captures, uploaded by the owner.
 @Override public void endLastBatch(){} @Override public void endBatch(){} @Override public void endBatch(RenderType type){}
 @SuppressWarnings({"rawtypes","unchecked"}) void block(net.minecraft.world.level.block.entity.BlockEntity entity){var client=Minecraft.getInstance();net.minecraft.client.renderer.blockentity.BlockEntityRenderer renderer=client.getBlockEntityRenderDispatcher().getRenderer(entity);if(renderer==null)return;var state=renderer.createRenderState();renderer.extractRenderState(entity,state,0,ClientUi.camera(client).position(),null);state.lightCoords=net.minecraft.util.LightCoordsUtil.FULL_BRIGHT;renderer.submit(state,new PoseStack(),submits,ClientUi.renderState(client).levelRenderState.cameraRenderState);finish();}
 @SuppressWarnings({"rawtypes","unchecked"}) void entity(net.minecraft.world.entity.Entity entity){var client=Minecraft.getInstance();net.minecraft.client.renderer.entity.EntityRenderer renderer=client.getEntityRenderDispatcher().getRenderer(entity);var state=renderer.createRenderState(entity,1);state.lightCoords=net.minecraft.util.LightCoordsUtil.FULL_BRIGHT;state.outlineColor=0;var matrices=new PoseStack();var offset=renderer.getRenderOffset(state);matrices.translate(offset.x,offset.y,offset.z);renderer.submit(state,matrices,submits,ClientUi.renderState(client).levelRenderState.cameraRenderState);finish();}
 private void finish(){var client=Minecraft.getInstance();if(features==null)features=new net.minecraft.client.renderer.feature.FeatureRenderDispatcher(submits,client.getModelManager(),this,client.getAtlasManager(),new OutlineBufferSource(),this,client.font,ClientUi.renderState(client));try{features.renderAllFeatures();}finally{submits.clear();features.endFrame();}}
 @Override public void close(){clear();if(features!=null){features.close();features=null;}sharedBuffer.close();}
}
