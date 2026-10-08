package dev.betterlitematica.fabric;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.vertex.VertexFormat;
/** Immutable persistent GPU vertices, shared sequential indices, no per-frame mesh upload. */
final class ProjectionGpuMesh implements AutoCloseable {
 private int references=1;private boolean closed;final GpuBuffer vertices;final int indexCount;final com.mojang.renderpearl.api.pipeline.PrimitiveTopology mode;
 ProjectionGpuMesh(MeshData data){try(data){vertices=RenderSystem.getDevice().createBuffer(()->"BetterLitematica projection",GpuBuffer.USAGE_VERTEX,data.vertexBuffer());indexCount=data.drawState().indexCount();mode=data.drawState().primitiveTopology();}}
 void retain(){if(references<=0)throw new IllegalStateException("Closed projection mesh");references++;} void release(){if(--references==0)RenderSystem.queueFencedTask(vertices::close);} @Override public void close(){if(!closed){closed=true;release();}}
}
