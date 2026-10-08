package dev.betterlitematica.fabric;
import com.mojang.blaze3d.vertex.*;
import com.mojang.renderpearl.api.vertex.VertexFormat;
/** Reusable CPU allocation; a completed mesh owns its slice until uploaded. */
final class ProjectionBuffer implements VertexConsumer,AutoCloseable {
 private final ByteBufferBuilder storage;private BufferBuilder current;
 ProjectionBuffer(int bytes){storage=new ByteBufferBuilder(bytes);}
 void begin(){begin(com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS,DefaultVertexFormat.POSITION_TEX_COLOR);} void beginOverlay(boolean face){begin(face?com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS:com.mojang.renderpearl.api.pipeline.PrimitiveTopology.LINES,face?DefaultVertexFormat.POSITION_COLOR:DefaultVertexFormat.POSITION_COLOR_NORMAL);}
 void begin(com.mojang.renderpearl.api.pipeline.PrimitiveTopology mode,VertexFormat format){if(current!=null)throw new IllegalStateException("Unfinished projection buffer");current=new BufferBuilder(storage,mode,format);}
 boolean building(){return current!=null;}
 MeshData endNullable(){var result=current.build();current=null;return result;} MeshData end(){var result=current.buildOrThrow();current=null;return result;}
 void clear(){if(current!=null){var data=current.build();if(data!=null)data.close();current=null;}storage.clear();}
 @Override public void close(){clear();storage.close();}
 ProjectionBuffer vertex(double x,double y,double z){addVertex((float)x,(float)y,(float)z);return this;}
 ProjectionBuffer uv(float u,float v){setUv(u,v);return this;}
 ProjectionBuffer color(int r,int g,int b,int a){setColor(r,g,b,a);return this;}
 ProjectionBuffer color(float r,float g,float b,float a){setColor(r,g,b,a);return this;}
 @Override public VertexConsumer addVertex(float x,float y,float z){return current.addVertex(x,y,z);}
 @Override public VertexConsumer setColor(int r,int g,int b,int a){return current.setColor(r,g,b,a);}
 @Override public VertexConsumer setColor(int color){return current.setColor(color);}
 @Override public VertexConsumer setUv(float u,float v){return current.setUv(u,v);}
 @Override public VertexConsumer setUv1(int u,int v){return current.setUv1(u,v);}
 @Override public VertexConsumer setUv2(int u,int v){return current.setUv2(u,v);}
@Override public VertexConsumer setUv3(float u,float v){return current.setUv3(u,v);}
 @Override public VertexConsumer setNormal(float x,float y,float z){return current.setNormal(x,y,z);}
 @Override public VertexConsumer setLineWidth(float width){return current.setLineWidth(width);}
}
