package dev.betterlitematica.fabric;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Arrays;
/** Bounded CPU capture. Attribute setters complete the latest vertex in place. */
final class ProjectionVertices implements VertexConsumer {
 private static final int STRIDE=9,MAX=16384;
 private float[] data=new float[STRIDE*256];private int size;
 void clear(){size=0;}
 int size(){return size;}
 void emit(VertexConsumer output,double dx,double dy,double dz){for(int i=0;i<size;i++){int at=i*STRIDE;output.addVertex((float)(data[at]+dx),(float)(data[at+1]+dy),(float)(data[at+2]+dz)).setUv(data[at+3],data[at+4]).setColor(data[at+5],data[at+6],data[at+7],data[at+8]);}}
 @Override public VertexConsumer addVertex(float x,float y,float z){if(size==MAX)throw new IllegalArgumentException("模型顶点超过单方块预算");int at=size++*STRIDE;if(at+STRIDE>data.length)data=Arrays.copyOf(data,Math.min(STRIDE*MAX,data.length*2));data[at]=x;data[at+1]=y;data[at+2]=z;data[at+3]=data[at+4]=0;data[at+5]=data[at+6]=data[at+7]=data[at+8]=1;return this;}
 @Override public VertexConsumer setColor(int r,int g,int b,int a){int at=(size-1)*STRIDE;data[at+5]=r/255f;data[at+6]=g/255f;data[at+7]=b/255f;data[at+8]=Math.max(1,a)/255f;return this;}
 @Override public VertexConsumer setColor(int color){return setColor((color>>>16)&255,(color>>>8)&255,color&255,color>>>24);}
 @Override public VertexConsumer setUv(float u,float v){int at=(size-1)*STRIDE;data[at+3]=u;data[at+4]=v;return this;}
 @Override public VertexConsumer setUv1(int u,int v){return this;}
 @Override public VertexConsumer setUv2(int u,int v){return this;}
 @Override public VertexConsumer setNormal(float x,float y,float z){return this;}
 @Override public VertexConsumer setLineWidth(float width){return this;}
}
