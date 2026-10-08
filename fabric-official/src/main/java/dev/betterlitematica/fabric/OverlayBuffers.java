package dev.betterlitematica.fabric;
import com.mojang.blaze3d.vertex.*;
import java.util.*;
/** Captures transient overlays during extraction, with a strict per-frame vertex budget. */
final class OverlayBuffers {
 private static final OverlayBuffers INSTANCE=new OverlayBuffers();
 private final Map<OverlayLayers.Layer,ProjectionBuffer> batches=new LinkedHashMap<>();private ProjectionFrame frame;private int vertices;
 static OverlayBuffers of(ProjectionFrame frame){if(INSTANCE.frame!=frame){INSTANCE.batches.values().forEach(ProjectionBuffer::close);INSTANCE.batches.clear();INSTANCE.vertices=0;INSTANCE.frame=frame;}return INSTANCE;}
 VertexConsumer getBuffer(OverlayLayers.Layer layer){return batches.computeIfAbsent(layer,l->{var buffer=new ProjectionBuffer(32768);buffer.beginOverlay(l.face());return buffer;});}
 void endBatch(OverlayLayers.Layer layer){var buffer=batches.remove(layer);if(buffer==null)return;try(buffer){var data=buffer.endNullable();if(data==null)return;vertices+=data.drawState().vertexCount();if(vertices>2_000_000){data.close();throw new IllegalStateException("高亮顶点超过绘制预算");}var mesh=new ProjectionGpuMesh(data);try{ProjectionDraw.overlay(mesh,frame.projectionMatrix(),layer.face(),layer.through());}finally{mesh.close();}}}
 static void lineBox(PoseStack stack,VertexConsumer output,net.minecraft.world.phys.AABB box,float r,float g,float b,float a){var pose=stack.last();for(int axis=0;axis<3;axis++)for(int bits=0;bits<4;bits++){float[] from={(float)box.minX,(float)box.minY,(float)box.minZ},to=from.clone();int u=(axis+1)%3,v=(axis+2)%3;double[] hi={box.maxX,box.maxY,box.maxZ};if((bits&1)!=0)from[u]=to[u]=(float)hi[u];if((bits&2)!=0)from[v]=to[v]=(float)hi[v];to[axis]=(float)hi[axis];float nx=axis==0?1:0,ny=axis==1?1:0,nz=axis==2?1:0;output.addVertex(pose.pose(),from[0],from[1],from[2]).setColor(r,g,b,a).setNormal(pose,nx,ny,nz);output.addVertex(pose.pose(),to[0],to[1],to[2]).setColor(r,g,b,a).setNormal(pose,nx,ny,nz);}}
}
