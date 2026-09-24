package dev.betterlitematica.fabric;

import net.minecraft.client.render.VertexConsumer;
import java.util.Arrays;

/** Small reusable capture buffer. Model callbacks cannot grow a section without limit. */
final class ProjectionVertices implements VertexConsumer {
    private static final int STRIDE=9,MAX=16384;
    private float[] data=new float[STRIDE*256];private int size;private double x,y,z;private float u,v,r=1,g=1,b=1,a=1;private boolean fixed;
    void clear(){size=0;fixed=false;r=g=b=a=1;}
    int size(){return size;}
    void emit(VertexConsumer output,double dx,double dy,double dz){for(int i=0;i<size;i++){int at=i*STRIDE;output.vertex(data[at]+dx,data[at+1]+dy,data[at+2]+dz).texture(data[at+3],data[at+4]).color(data[at+5],data[at+6],data[at+7],data[at+8]).next();}}
    @Override public VertexConsumer vertex(double x,double y,double z){this.x=x;this.y=y;this.z=z;return this;}
    @Override public VertexConsumer color(int r,int g,int b,int a){if(!fixed){this.r=r/255f;this.g=g/255f;this.b=b/255f;this.a=a/255f;}return this;}
    @Override public VertexConsumer texture(float u,float v){this.u=u;this.v=v;return this;}
    @Override public VertexConsumer overlay(int u,int v){return this;}
    @Override public VertexConsumer light(int u,int v){return this;}
    @Override public VertexConsumer normal(float x,float y,float z){return this;}
    @Override public void next(){if(size==MAX)throw new IllegalArgumentException("模型顶点超过单方块预算");int at=size++*STRIDE;if(at+STRIDE>data.length)data=Arrays.copyOf(data,Math.min(STRIDE*MAX,data.length*2));data[at]=(float)x;data[at+1]=(float)y;data[at+2]=(float)z;data[at+3]=u;data[at+4]=v;data[at+5]=r;data[at+6]=g;data[at+7]=b;data[at+8]=Math.max(1/255f,a);}
    @Override public void fixedColor(int r,int g,int b,int a){fixed=false;color(r,g,b,a);fixed=true;}
    @Override public void unfixColor(){fixed=false;}
}
