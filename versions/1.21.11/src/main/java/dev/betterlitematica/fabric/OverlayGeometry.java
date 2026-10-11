package dev.betterlitematica.fabric;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;

/** Twelve independent box edges for the projection line pipeline. */
final class OverlayGeometry {
    private OverlayGeometry() {}
    static void box(MatrixStack.Entry pose,VertexConsumer output,Box box,float r,float g,float b,float a){box(pose,output,box.minX,box.minY,box.minZ,box.maxX,box.maxY,box.maxZ,r,g,b,a);}
    static void box(MatrixStack.Entry pose,VertexConsumer output,double x0,double y0,double z0,double x1,double y1,double z1,float r,float g,float b,float a){
        for(int axis=0;axis<3;axis++)for(int corner=0;corner<4;corner++){
            float x=(float)((corner&1)==0?x0:x1);
            float y=(float)((corner&(axis==2?2:1))==0?y0:y1);
            float z=(float)((corner&2)==0?z0:z1);
            float nx=axis==0?1:0,ny=axis==1?1:0,nz=axis==2?1:0;
            for(int end=0;end<2;end++){
                float px=axis==0?(float)(end==0?x0:x1):x;
                float py=axis==1?(float)(end==0?y0:y1):y;
                float pz=axis==2?(float)(end==0?z0:z1):z;
                output.vertex(pose,px,py,pz).color(r,g,b,a).normal(pose,nx,ny,nz);
            }
        }
    }
}
