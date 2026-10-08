package dev.betterlitematica.fabric;

import dev.betterlitematica.fabric.mixin.*;
import net.minecraft.client.render.*;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.util.Identifier;
import java.util.*;

/** Captures built-in entity/BER render output once into bounded textured meshes. */
final class ProjectionModels implements VertexConsumerProvider {
    record Material(Identifier texture,boolean intensity){}
    private final Map<Material,ProjectionVertices> layers=new LinkedHashMap<>();
    private final ProjectionVertices untextured=new ProjectionVertices();
    void clear(){layers.clear();untextured.clear();}
    Map<Material,ProjectionVertices> layers(){return layers;}
    int vertices(){return layers.values().stream().mapToInt(ProjectionVertices::size).sum();}
    @Override public VertexConsumer getBuffer(RenderLayer layer){
        if(layer.getDrawMode()!=VertexFormat.DrawMode.QUADS||!(layer instanceof RenderLayerAccessor access))return untextured;
        var phase=((RenderPhasesAccessor)(Object)access.betterlitematica$phases()).betterlitematica$texture();var texture=((TexturePhaseAccessor)phase).betterlitematica$id();
        if(texture.isEmpty())return untextured;var material=new Material(texture.get(),((RenderPhaseAccessor)layer).betterlitematica$name().startsWith("text_intensity"));if(layers.size()>=16&&!layers.containsKey(material))throw new IllegalArgumentException("模型材质超过预算");
        return layers.computeIfAbsent(material,id->new ProjectionVertices());
    }
}
